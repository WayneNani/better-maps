#include "drape_frontend/indoor_builder.hpp"

#include "drape_frontend/area_shape.hpp"
#include "drape_frontend/batcher_bucket.hpp"
#include "drape_frontend/indoor_triangulation.hpp"
#include "drape_frontend/line_shape.hpp"
#include "drape_frontend/poi_symbol_shape.hpp"
#include "drape_frontend/shape_view_params.hpp"
#include "drape_frontend/text_shape.hpp"
#include "drape_frontend/tile_key.hpp"
#include "drape_frontend/visual_params.hpp"

#include "shaders/programs.hpp"

#include "drape/batcher.hpp"
#include "drape/texture_manager.hpp"

#include "indexer/map_style.hpp"
#include "indexer/map_style_reader.hpp"

#include "geometry/spline.hpp"

#include "base/logging.hpp"
#include "base/timer.hpp"

#include <string_view>

namespace df
{
using indoor::Feature;
using indoor::GeomType;
using indoor::Kind;

namespace
{
dp::Color Rgb(uint32_t rgb)
{
  return dp::Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 0xFF);
}

struct LineStyle
{
  dp::Color m_color;
  float m_width = 0;  // dp
};

std::optional<LineStyle> GetLineStyle(Kind kind, bool dark)
{
  switch (kind)
  {
  case Kind::Wall: return LineStyle{Rgb(dark ? 0x8A8378 : 0x8C8378), 2.0f};
  case Kind::Steps: return LineStyle{Rgb(dark ? 0x9A8F80 : 0xA89C8A), 3.0f};
  case Kind::Escalator: return LineStyle{Rgb(dark ? 0x7E93A8 : 0x8FA6BD), 3.0f};
  case Kind::Footway: return LineStyle{Rgb(dark ? 0x5A5650 : 0xD8D1C5), 1.5f};
  default: return {};
  }
}

// Overlay priority among the indoor overlays (bigger wins); all of them are above the map's own POIs.
uint16_t GetPriority(Feature const & f)
{
  switch (f.m_kind)
  {
  case Kind::Gate: return 900;
  case Kind::Toilets:
  case Kind::Elevator:
  case Kind::Info: return 800;
  case Kind::Shop:
  case Kind::Food:
  case Kind::Pharmacy: return 700;
  case Kind::Atm:
  case Kind::Ticket:
  case Kind::Luggage: return 600;
  case Kind::Entrance:
  case Kind::Door: return 300;
  default: return f.m_type == GeomType::Point ? 500 : 400;
  }
}

std::string_view Tag(Feature const & f, std::string_view key)
{
  return f.GetTag(key);
}
}  // namespace

IndoorColors GetIndoorAreaColors(Kind kind, bool dark)
{
  // Muted colours, distinct per class; shops, food and gates stand out.
  struct Pair
  {
    uint32_t m_light, m_lightOutline, m_dark, m_darkOutline;
  };
  Pair p;
  switch (kind)
  {
  case Kind::Level: p = {0xF4F1EC, 0xBDB5A9, 0x2B2A28, 0x55504A}; break;
  case Kind::Corridor: p = {0xFCFBF8, 0xD3CCC1, 0x34332F, 0x4C4842}; break;
  case Kind::Area: p = {0xEFECE5, 0xCFC7BB, 0x302F2C, 0x4C4842}; break;
  case Kind::Platform: p = {0xE3E0DA, 0xB9B2A7, 0x3A3936, 0x5A5650}; break;
  case Kind::Room: p = {0xE9E3DA, 0xAFA596, 0x3B3833, 0x625C53}; break;
  case Kind::Service: p = {0xE6E2EE, 0xA9A2BC, 0x38363F, 0x5F5A6E}; break;
  case Kind::Restricted: p = {0xE2DED8, 0xB5ADA2, 0x2F2E2C, 0x4A4640}; break;
  case Kind::Shop: p = {0xF6DDBA, 0xD09A55, 0x4A3B27, 0x8C6A3C}; break;
  case Kind::Food: p = {0xF5D3C2, 0xCC8B6E, 0x4A3229, 0x8A5C48}; break;
  case Kind::Toilets: p = {0xD7E5F3, 0x7FA2C8, 0x2C3A48, 0x4E6C8C}; break;
  case Kind::Stairs:
  case Kind::Elevator:
  case Kind::Escalator:
  case Kind::Steps: p = {0xDEDCD8, 0x9F9A92, 0x3D3C3A, 0x6B6762}; break;
  case Kind::Gate: p = {0xCFE2F5, 0x5E8FC6, 0x253A52, 0x4C78A8}; break;
  case Kind::Wall: p = {0xA59C90, 0xA59C90, 0x6E675E, 0x6E675E}; break;
  default: p = {0xEAE6DF, 0xB3AA9D, 0x393733, 0x5E5951}; break;
  }
  return dark ? IndoorColors{Rgb(p.m_dark), Rgb(p.m_darkOutline)} : IndoorColors{Rgb(p.m_light), Rgb(p.m_lightOutline)};
}

std::string GetIndoorSymbol(Feature const & f)
{
  switch (f.m_kind)
  {
  case Kind::Toilets: return "toilets-m";
  case Kind::Elevator: return "elevator-s";
  case Kind::Info: return "information-m";
  case Kind::Gate: return "airport_gate-m";
  case Kind::Atm: return "atm-m";
  case Kind::Pharmacy: return "pharmacy-m";
  case Kind::Luggage: return "luggage_locker-m";
  case Kind::Ticket: return "ticket-shop-m";
  case Kind::Entrance: return f.HasLabel() ? "entrance-m" : "";
  case Kind::Food:
  {
    auto const amenity = Tag(f, "amenity");
    if (amenity == "cafe")
      return "cafe-m";
    if (amenity == "fast_food")
      return "fastfood-m";
    if (Tag(f, "shop") == "bakery")
      return "bakery-m";
    return "restaurant-m";
  }
  case Kind::Shop:
  {
    auto const shop = Tag(f, "shop");
    if (shop == "kiosk" || shop == "newsagent")
      return "kiosk-m";
    if (shop == "bakery")
      return "bakery-m";
    return "shop-m";
  }
  default: return {};
  }
}

int GetIndoorLabelMinZoom(Feature const & f)
{
  switch (f.m_kind)
  {
  case Kind::Gate:
  case Kind::Toilets:
  case Kind::Elevator:
  case Kind::Info: return indoor::kMinDrawZoom;
  case Kind::Shop:
  case Kind::Food:
  case Kind::Pharmacy:
  case Kind::Ticket:
  case Kind::Platform: return 17;
  default: return 18;
  }
}

std::string GetIndoorLabel(Feature const & f)
{
  if (f.m_kind == Kind::Gate || (f.m_name.empty() && !f.m_ref.empty()))
    return f.m_ref.empty() ? f.m_name : f.m_ref;
  return f.m_name;
}

IndoorRenderBatch IndoorBuilder::Build(ref_ptr<dp::GraphicsContext> context, ref_ptr<dp::TextureManager> textures,
                                       IndoorLevelParams const & params)
{
  m_params = params;
  return BuildImpl(context, textures);
}

IndoorRenderBatch IndoorBuilder::Rebuild(ref_ptr<dp::GraphicsContext> context, ref_ptr<dp::TextureManager> textures)
{
  return BuildImpl(context, textures);
}

IndoorRenderBatch IndoorBuilder::BuildImpl(ref_ptr<dp::GraphicsContext> context, ref_ptr<dp::TextureManager> textures)
{
  IndoorRenderBatch batch;
  batch.m_buildId = ++m_buildId;
  if (m_params.m_venue == nullptr)
    return batch;

  base::Timer timer;
  auto const & venue = *m_params.m_venue;
  double const level = m_params.m_level;
  bool const dark = MapStyleIsDark(GetStyleReader().GetCurrentStyle());
  auto const vs = static_cast<float>(VisualParams::Instance().GetVisualScale());
  m2::PointD const pivot = venue.m_info.m_rect.Center();

  uint32_t constexpr kBatchSize = 65000;
  dp::Batcher batcher(kBatchSize, kBatchSize);
  batcher.SetBatcherHash(static_cast<uint64_t>(BatcherBucket::Default));
  size_t triangles = 0;
  {
    dp::SessionGuard guard(context, batcher,
                           [&batch, &pivot](dp::RenderState const & state, drape_ptr<dp::RenderBucket> && bucket)
    {
      using Type = IndoorRenderData::Type;
      Type type;
      switch (state.GetProgram<gpu::Program>())
      {
      case gpu::Program::Area:
      case gpu::Program::TransparentArea: type = Type::Area; break;
      case gpu::Program::AreaOutline: type = Type::Outline; break;
      case gpu::Program::TextOutlined:
      case gpu::Program::Text: type = Type::Text; break;
      case gpu::Program::Texturing:
      case gpu::Program::MaskedTexturing: type = Type::Symbol; break;
      default: type = Type::Line; break;
      }
      batch.m_data.emplace_back(type, state, pivot, std::move(bucket));
    });

    // Areas in the server's order (floors, corridors, rooms, ...), each with its outline.
    for (auto const & f : venue.m_features)
    {
      if (f.m_type != GeomType::Area || !f.IsOnLevel(level))
        continue;
      auto const colors = GetIndoorAreaColors(f.m_kind, dark);
      for (auto const & polygon : f.m_polygons)
      {
        auto tri = indoor::Triangulate(polygon);
        if (tri.empty())
          continue;
        triangles += tri.size() / 3;
        // Face culling keeps only one winding, which depends on the graphics API's orientation of the screen: add
        // every triangle in both windings (cheap at the size of a venue).
        size_t const n = tri.size();
        tri.reserve(2 * n);
        for (size_t t = 0; t + 2 < n; t += 3)
        {
          tri.push_back(tri[t]);
          tri.push_back(tri[t + 2]);
          tri.push_back(tri[t + 1]);
        }
        BuildingOutline outline;
        outline.m_generateOutline = f.m_kind != Kind::Wall;
        if (outline.m_generateOutline)
        {
          for (auto const & ring : polygon)
          {
            int const start = static_cast<int>(outline.m_vertices.size());
            int const n = static_cast<int>(ring.size());
            for (auto const & p : ring)
              outline.m_vertices.push_back(p);
            for (int i = 0; i < n; ++i)
            {
              outline.m_indices.push_back(start + i);
              outline.m_indices.push_back(start + (i + 1) % n);
            }
          }
        }
        AreaViewParams params;
        params.m_tileCenter = pivot;
        params.m_depthTestEnabled = false;
        params.m_depthLayer = DepthLayer::GeometryLayer;
        params.m_color = colors.m_fill;
        params.m_outlineColor = colors.m_outline;
        AreaShape(std::move(tri), std::move(outline), params).Draw(context, make_ref(&batcher), textures);
      }
    }

    // Walls, steps, escalators, footways.
    for (auto const & f : venue.m_features)
    {
      if (f.m_type != GeomType::Line || !f.IsOnLevel(level))
        continue;
      auto const style = GetLineStyle(f.m_kind, dark);
      if (!style)
        continue;
      for (auto const & line : f.m_lines)
      {
        LineViewParams params;
        params.m_tileCenter = pivot;
        params.m_depthTestEnabled = false;
        params.m_depthLayer = DepthLayer::GeometryLayer;
        params.m_color = style->m_color;
        params.m_width = style->m_width * vs;
        params.m_cap = dp::RoundCap;
        params.m_join = dp::RoundJoin;
        LineShape(m2::SharedSpline(line), params).Draw(context, make_ref(&batcher), textures);
      }
    }

    // Labels and icons, above the map's own POIs.
    dp::FontDecl font(Rgb(dark ? 0xDDD6CC : 0x4A4038), 12.0f * vs, Rgb(dark ? 0x1C1B19 : 0xFFFFFF));
    dp::FontDecl gateFont(Rgb(dark ? 0xA9C8EC : 0x1F4F8A), 13.0f * vs, font.m_outlineColor);
    for (size_t i = 0; i < venue.m_features.size(); ++i)
    {
      auto const & f = venue.m_features[i];
      if (f.m_type == GeomType::Line || !f.IsOnLevel(level))
        continue;
      std::string const label = GetIndoorLabel(f);
      std::string const symbol = GetIndoorSymbol(f);
      // Areas without a label only show their icon when they are a POI kind (e.g. toilets).
      if (label.empty() && symbol.empty())
        continue;
      auto const pos = indoor::LabelPoint(f);
      if (!pos)
        continue;
      uint32_t const index = kIndoorOverlayIndexBase + static_cast<uint32_t>(i);
      uint16_t const priority = GetPriority(f);
      int const minZoom = GetIndoorLabelMinZoom(f);

      m2::PointF symbolSize(0.0f, 0.0f);
      dp::TextureManager::SymbolRegion region;
      bool const hasSymbol = !symbol.empty() && textures->GetSymbolRegionSafe(symbol, region);
      if (hasSymbol)
      {
        symbolSize = region.GetPixelSize();
        PoiSymbolViewParams params;
        params.m_tileCenter = pivot;
        params.m_depthTestEnabled = false;
        params.m_depthLayer = DepthLayer::OverlayLayer;
        params.m_symbolName = symbol;
        params.m_specialDisplacement = SpecialDisplacement::SpecialModeUserMark;
        params.m_specialPriority = priority;
        params.m_startOverlayRank = dp::OverlayRank0;
        params.m_minVisibleScale = minZoom;
        PoiSymbolShape(*pos, params, TileKey(), index, 0 /* subID */).Draw(context, make_ref(&batcher), textures);
      }
      if (!label.empty())
      {
        TextViewParams params;
        params.m_tileCenter = pivot;
        params.m_depthTestEnabled = false;
        params.m_depthLayer = DepthLayer::OverlayLayer;
        params.m_titleDecl.m_primaryText = label;
        params.m_titleDecl.m_primaryTextFont = f.m_kind == Kind::Gate ? gateFont : font;
        params.m_titleDecl.m_anchor = hasSymbol ? dp::Top : dp::Center;
        // Beside an icon the text may give way, the icon stays.
        params.m_titleDecl.m_primaryOptional = hasSymbol;
        params.m_specialDisplacement = SpecialDisplacement::SpecialModeUserMark;
        params.m_specialPriority = priority;
        params.m_startOverlayRank = hasSymbol ? dp::OverlayRank1 : dp::OverlayRank0;
        // Room names need more room than gate refs: from one zoom later unless they are a POI kind.
        params.m_minVisibleScale = std::max(minZoom, hasSymbol ? minZoom + 1 : minZoom);
        if (f.m_kind == Kind::Gate)
          params.m_minVisibleScale = minZoom;
        TextShape(*pos, params, TileKey(), symbolSize, m2::PointF(0.0f, 0.0f), dp::Center, index, 0 /* subID */,
                  0 /* secondarySubID */)
            .Draw(context, make_ref(&batcher), textures);
      }
    }
  }
  LOG(LINFO,
      ("Indoor: built level", indoor::FormatLevel(level), "of a venue with", venue.m_features.size(),
       "features:", batch.m_data.size(), "buckets,", triangles, "triangles in", timer.ElapsedMilliseconds(), "ms"));
  return batch;
}
}  // namespace df
