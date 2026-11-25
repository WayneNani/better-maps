#pragma once

// better-maps: raster tile layer ("Luftbild"). Draws the loaded tiles of the current view below the map (render
// thread) and tells which parts of the view have imagery, so that the frontend can hide the area fills there.

#include "drape_frontend/frame_values.hpp"
#include "drape_frontend/raster_tile_loader.hpp"
#include "drape_frontend/raster_tiles.hpp"

#include "drape/pointers.hpp"

#include "geometry/rect2d.hpp"
#include "geometry/screenbase.hpp"

#include <chrono>
#include <cstdint>
#include <unordered_map>
#include <vector>

namespace dp
{
class GraphicsContext;
class MeshObject;
class StaticTexture;
}  // namespace dp

namespace gpu
{
class ProgramManager;
}  // namespace gpu

namespace df
{
class RasterRenderer
{
public:
  // GPU budget: 160 tiles of 256 px = 40 MB.
  static size_t constexpr kMaxTextures = 160;
  static size_t constexpr kMaxWantedTiles = 64;
  static int constexpr kMaxZoom = 19;
  static int constexpr kMaxFallbackLevels = 6;

  RasterRenderer();
  ~RasterRenderer();

  // A tile arrived from the loader (render thread).
  void OnTileLoaded(raster::RasterTileImage && image);

  // Computes the tiles of |screen|. Returns true if the tiles to load changed; |toLoad| then holds them, most
  // important first.
  bool UpdateWanted(ScreenBase const & screen, double visualScale, std::vector<raster::RasterTileKey> & toLoad);

  void Render(ref_ptr<dp::GraphicsContext> context, ref_ptr<gpu::ProgramManager> mng, ScreenBase const & screen,
              FrameValues const & frameValues);

  // Whether imagery is drawn over all of |rect| (mercator) within the view of the last frame.
  bool IsCovered(m2::RectD const & rect) const;

  // Drops all tiles (server changed, layer off).
  void Clear();
  // Drops the GPU resources (context lost); the next frames load the tiles again.
  void ClearContextDependentResources();

  size_t GetTextureCount() const;

private:
  struct Tile
  {
    drape_ptr<dp::StaticTexture> m_texture;
    raster::RasterTileImage m_pending;  // decoded, waiting for the upload
    uint64_t m_lastUsedFrame = 0;
  };

  bool IsDrawable(raster::RasterTileKey const & key) const;
  void UploadPending(ref_ptr<dp::GraphicsContext> context);
  void EvictTextures(std::vector<raster::RasterTileKey> const & inUse);
  void BuildMesh(ref_ptr<dp::GraphicsContext> context);

  std::unordered_map<raster::RasterTileKey, Tile, raster::RasterTileKeyHash> m_tiles;
  using Clock = std::chrono::steady_clock;
  std::unordered_map<raster::RasterTileKey, Clock::time_point, raster::RasterTileKeyHash> m_failed;

  std::vector<raster::RasterTileKey> m_wanted;
  std::vector<raster::RasterTileKey> m_lastToLoad;
  int m_zoom = 0;

  // Rects of wanted tiles without imagery in the last frame.
  std::vector<m2::RectD> m_uncovered;
  bool m_coverageValid = false;

  drape_ptr<dp::MeshObject> m_mesh;
  uint64_t m_frame = 0;
};
}  // namespace df
