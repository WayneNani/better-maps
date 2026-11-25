#include "drape_frontend/raster_renderer.hpp"

#include "drape_frontend/render_state_extension.hpp"

#include "shaders/program_manager.hpp"
#include "shaders/program_params.hpp"
#include "shaders/programs.hpp"

#include "drape/glsl_types.hpp"
#include "drape/graphics_context.hpp"
#include "drape/hw_texture.hpp"
#include "drape/mesh_object.hpp"
#include "drape/static_texture.hpp"

#include "base/logging.hpp"

#include <algorithm>
#include <unordered_set>

namespace df
{
using raster::RasterTileKey;

namespace
{
// Retry a failed tile after this time (404: the server has nothing there).
auto constexpr kRetryAfterError = std::chrono::seconds(30);
auto constexpr kRetryAfterNotFound = std::chrono::minutes(10);

std::array<RasterTileKey, 4> Children(RasterTileKey const & k)
{
  return {RasterTileKey(k.m_z + 1, 2 * k.m_x, 2 * k.m_y), RasterTileKey(k.m_z + 1, 2 * k.m_x + 1, 2 * k.m_y),
          RasterTileKey(k.m_z + 1, 2 * k.m_x, 2 * k.m_y + 1), RasterTileKey(k.m_z + 1, 2 * k.m_x + 1, 2 * k.m_y + 1)};
}
}  // namespace

RasterRenderer::RasterRenderer() = default;
RasterRenderer::~RasterRenderer() = default;

void RasterRenderer::OnTileLoaded(raster::RasterTileImage && image)
{
  auto const key = image.m_key;
  if (image.m_failed)
  {
    m_failed[key] = Clock::now() + (image.m_httpCode == 404 ? kRetryAfterNotFound : kRetryAfterError);
    return;
  }
  m_failed.erase(key);
  auto & tile = m_tiles[key];
  tile.m_texture.reset();
  tile.m_pending = std::move(image);
  tile.m_lastUsedFrame = m_frame;
}

bool RasterRenderer::IsDrawable(RasterTileKey const & key) const
{
  auto const it = m_tiles.find(key);
  return it != m_tiles.end() && it->second.m_texture != nullptr;
}

bool RasterRenderer::UpdateWanted(ScreenBase const & screen, double visualScale, std::vector<RasterTileKey> & toLoad)
{
  // A 256 px tile is drawn 256..512 px wide depending on the screen density: sharp enough, and a phone screen needs
  // 20-40 tiles.
  double const tilePx = 256.0 * std::clamp(visualScale / 1.5, 1.0, 2.0);
  int const preferredZoom = raster::ZoomForScale(screen.GetScale(), tilePx, 0, kMaxZoom);
  m_wanted = raster::WantedTiles(screen.ClipRect(), screen.GetOrg(), preferredZoom, kMaxWantedTiles, m_zoom);

  auto const now = Clock::now();
  std::vector<RasterTileKey> missing;
  for (auto const & k : m_wanted)
  {
    if (m_tiles.contains(k))
      continue;
    if (auto const it = m_failed.find(k); it != m_failed.end() && it->second > now)
      continue;
    missing.push_back(k);
  }
  if (missing == m_lastToLoad)
    return false;
  m_lastToLoad = missing;
  toLoad = std::move(missing);
  return true;
}

void RasterRenderer::BuildMesh(ref_ptr<dp::GraphicsContext> context)
{
  // A unit square (0,0)-(1,1) in tile coordinates (y up); the model-view matrix of each tile maps it onto the tile.
  // Texture row 0 is the top of the image. Both windings, so that face culling keeps one of them whatever the API's
  // orientation of the screen is.
  struct V
  {
    float x, y, u, v;
  };
  V const bl{0, 0, 0, 1}, br{1, 0, 1, 1}, tl{0, 1, 0, 0}, tr{1, 1, 1, 0};
  std::vector<V> const corners = {bl, tl, tr, bl, tr, br, bl, tr, tl, bl, br, tr};
  std::vector<float> vertices;
  vertices.reserve(corners.size() * 8);
  for (auto const & c : corners)
  {
    // a_position (vec4), a_normal (vec2), a_colorTexCoords (vec2) of the Texturing program.
    vertices.insert(vertices.end(), {c.x, c.y, 0.0f, 0.0f, 0.0f, 0.0f, c.u, c.v});
  }
  m_mesh = make_unique_dp<dp::MeshObject>(context, dp::MeshObject::DrawPrimitive::Triangles, "RasterTile");
  m_mesh->SetBuffer(0 /* bufferInd */, std::move(vertices), sizeof(float) * 8 /* stride */);
  m_mesh->SetAttribute("a_position", 0 /* bufferInd */, 0 /* offset */, 4 /* componentsCount */);
  m_mesh->SetAttribute("a_normal", 0 /* bufferInd */, sizeof(float) * 4 /* offset */, 2 /* componentsCount */);
  m_mesh->SetAttribute("a_colorTexCoords", 0 /* bufferInd */, sizeof(float) * 6 /* offset */, 2 /* componentsCount */);
}

void RasterRenderer::UploadPending(ref_ptr<dp::GraphicsContext> context)
{
  for (auto & [key, tile] : m_tiles)
  {
    if (tile.m_texture != nullptr || tile.m_pending.m_rgba.empty())
      continue;
    dp::Texture::Params p;
    p.m_allocator = dp::GetDefaultAllocator(context);
    p.m_format = dp::TextureFormat::RGBA8;
    p.m_width = tile.m_pending.m_width;
    p.m_height = tile.m_pending.m_height;
    p.m_filter = dp::TextureFilter::Linear;
    p.m_wrapSMode = dp::TextureWrapping::ClampToEdge;
    p.m_wrapTMode = dp::TextureWrapping::ClampToEdge;
    tile.m_texture = make_unique_dp<dp::StaticTexture>();
    tile.m_texture->Create(context, p, make_ref(tile.m_pending.m_rgba.data()));
    tile.m_pending = {};
  }
}

void RasterRenderer::Render(ref_ptr<dp::GraphicsContext> context, ref_ptr<gpu::ProgramManager> mng,
                            ScreenBase const & screen, FrameValues const & frameValues)
{
  ++m_frame;
  UploadPending(context);
  if (m_mesh == nullptr)
    BuildMesh(context);

  // What to draw for each wanted tile: the tile itself, else the nearest loaded ancestor (blurry while loading) and
  // loaded children (after zooming out).
  std::vector<RasterTileKey> draw;
  std::unordered_set<RasterTileKey, raster::RasterTileKeyHash> drawSet;
  auto const add = [&](RasterTileKey const & k)
  {
    if (drawSet.insert(k).second)
      draw.push_back(k);
  };
  m_uncovered.clear();
  for (auto const & k : m_wanted)
  {
    if (IsDrawable(k))
    {
      add(k);
      continue;
    }
    bool covered = false;
    for (int level = 1; level <= kMaxFallbackLevels && !covered; ++level)
    {
      auto const a = raster::Ancestor(k, level);
      if (a && IsDrawable(*a))
      {
        add(*a);
        covered = true;
      }
    }
    int children = 0;
    if (k.m_z < kMaxZoom)
    {
      for (auto const & c : Children(k))
      {
        if (IsDrawable(c))
        {
          add(c);
          ++children;
        }
      }
    }
    if (!covered && children < 4)
      m_uncovered.push_back(raster::TileRect(k));
  }
  std::stable_sort(draw.begin(), draw.end(),
                   [](RasterTileKey const & a, RasterTileKey const & b) { return a.m_z < b.m_z; });

  if (!draw.empty())
  {
    auto state = CreateRenderState(gpu::Program::Texturing, DepthLayer::GeometryLayer);
    state.SetDepthTestEnabled(false);
    state.SetBlending(dp::Blending(true));
    auto program = mng->GetProgram(gpu::Program::Texturing);
    gpu::MapProgramParams params;
    frameValues.SetTo(params);
    params.m_opacity = 1.0f;
    for (auto const & k : draw)
    {
      auto & tile = m_tiles[k];
      tile.m_lastUsedFrame = m_frame;
      auto const rect = raster::TileRect(k);
      math::Matrix<float, 4, 4> const mv(screen.GetModelView(rect.LeftBottom(), 1.0 / rect.SizeX()));
      params.m_modelView = glsl::make_mat4(mv.m_data);
      state.SetColorTexture(make_ref(tile.m_texture));
      m_mesh->Render(context, program, state, mng->GetParamsSetter(), params);
    }
  }
  // Without wanted tiles (empty view) nothing is covered.
  m_coverageValid = !m_wanted.empty();

  for (auto const & k : m_wanted)
    if (auto it = m_tiles.find(k); it != m_tiles.end())
      it->second.m_lastUsedFrame = m_frame;
  EvictTextures(draw);
}

void RasterRenderer::EvictTextures(std::vector<RasterTileKey> const & inUse)
{
  if (m_tiles.size() <= kMaxTextures)
    return;
  std::unordered_set<RasterTileKey, raster::RasterTileKeyHash> const keep(inUse.begin(), inUse.end());
  std::vector<std::pair<uint64_t, RasterTileKey>> candidates;
  for (auto const & [key, tile] : m_tiles)
    if (!keep.contains(key))
      candidates.emplace_back(tile.m_lastUsedFrame, key);
  std::sort(candidates.begin(), candidates.end(),
            [](auto const & a, auto const & b) { return a.first != b.first ? a.first < b.first : a.second < b.second; });
  for (auto const & c : candidates)
  {
    if (m_tiles.size() <= kMaxTextures)
      break;
    m_tiles.erase(c.second);
  }
}

bool RasterRenderer::IsCovered(m2::RectD const & rect) const
{
  if (!m_coverageValid)
    return false;
  m2::RectD inner = rect;
  inner.Inflate(-rect.SizeX() * 1e-6, -rect.SizeY() * 1e-6);
  for (auto const & u : m_uncovered)
    if (u.IsIntersect(inner))
      return false;
  return true;
}

void RasterRenderer::Clear()
{
  m_tiles.clear();
  m_failed.clear();
  m_wanted.clear();
  m_lastToLoad.clear();
  m_uncovered.clear();
  m_coverageValid = false;
}

void RasterRenderer::ClearContextDependentResources()
{
  m_tiles.clear();
  m_mesh.reset();
  m_lastToLoad.clear();
  m_uncovered.clear();
  m_coverageValid = false;
}

size_t RasterRenderer::GetTextureCount() const
{
  size_t n = 0;
  for (auto const & [key, tile] : m_tiles)
    n += tile.m_texture != nullptr ? 1 : 0;
  return n;
}
}  // namespace df
