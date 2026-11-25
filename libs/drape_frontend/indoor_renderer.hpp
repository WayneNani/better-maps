#pragma once

// better-maps: indoor maps. Draws the level built by IndoorBuilder on the render thread: the fills, outlines and
// lines above the map's areas and buildings but below user marks and the route, the labels and icons as overlays
// (collision handling with the map's own overlays in the common overlay tree).

#include "drape_frontend/frame_values.hpp"
#include "drape_frontend/indoor_builder.hpp"

#include "drape/pointers.hpp"

#include "geometry/screenbase.hpp"

#include <cstdint>
#include <vector>

namespace dp
{
class GraphicsContext;
class OverlayTree;
}  // namespace dp

namespace gpu
{
class ProgramManager;
}  // namespace gpu

namespace df
{
class IndoorRenderer
{
public:
  // Replaces the shown level if |batch| is newer than it (an empty batch hides the layer).
  void SetRenderData(ref_ptr<dp::GraphicsContext> context, ref_ptr<gpu::ProgramManager> mng,
                     ref_ptr<dp::OverlayTree> tree, IndoorRenderBatch && batch);

  bool HasRenderData() const;
  // Drawn from indoor::kMinDrawZoom on.
  bool IsVisible(int zoomLevel) const;

  void RenderGeometry(ref_ptr<dp::GraphicsContext> context, ref_ptr<gpu::ProgramManager> mng, ScreenBase const & screen,
                      FrameValues const & frameValues);
  void CollectOverlays(ref_ptr<dp::OverlayTree> tree, ScreenBase const & screen);
  void RenderOverlays(ref_ptr<dp::GraphicsContext> context, ref_ptr<gpu::ProgramManager> mng, ScreenBase const & screen,
                      FrameValues const & frameValues);

  // Drops the GPU data (context lost or layer off); the overlays are removed from |tree| if given.
  void Clear(ref_ptr<dp::OverlayTree> tree);

  size_t GetBucketCount() const;

private:
  void Render(ref_ptr<dp::GraphicsContext> context, ref_ptr<gpu::ProgramManager> mng, ScreenBase const & screen,
              FrameValues const & frameValues, std::vector<IndoorRenderData> & data, bool isText);

  uint64_t m_buildId = 0;
  std::vector<IndoorRenderData> m_areas;
  std::vector<IndoorRenderData> m_outlines;
  std::vector<IndoorRenderData> m_lines;
  std::vector<IndoorRenderData> m_texts;
  std::vector<IndoorRenderData> m_symbols;
};
}  // namespace df
