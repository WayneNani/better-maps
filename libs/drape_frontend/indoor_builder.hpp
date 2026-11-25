#pragma once

// better-maps: indoor maps. Builds the render buckets of one level of a venue on the backend (resource upload)
// thread: filled rooms/corridors/areas with outlines, walls and steps as lines, labels and POI icons as overlays.

#include "drape_frontend/indoor_data.hpp"

#include "drape/color.hpp"
#include "drape/pointers.hpp"
#include "drape/render_bucket.hpp"
#include "drape/render_state.hpp"

#include "geometry/point2d.hpp"

#include <cstdint>
#include <string>
#include <vector>

namespace dp
{
class GraphicsContext;
class TextureManager;
}  // namespace dp

namespace df
{
// What the indoor layer shows: one level of one venue, or nothing (no venue).
struct IndoorLevelParams
{
  indoor::VenuePtr m_venue;
  double m_level = 0.0;
};

struct IndoorRenderData
{
  enum class Type : uint8_t
  {
    Area,     // fills, drawn first
    Outline,  // room outlines and thin lines
    Line,     // walls, steps
    Text,     // overlays
    Symbol    // overlays
  };

  IndoorRenderData(Type type, dp::RenderState const & state, m2::PointD const & pivot,
                   drape_ptr<dp::RenderBucket> && bucket)
    : m_type(type)
    , m_state(state)
    , m_pivot(pivot)
    , m_bucket(std::move(bucket))
  {}

  Type m_type = Type::Area;
  dp::RenderState m_state;
  m2::PointD m_pivot;
  drape_ptr<dp::RenderBucket> m_bucket;
};

struct IndoorRenderBatch
{
  // Increases with every build; the frontend keeps only the newest.
  uint64_t m_buildId = 0;
  std::vector<IndoorRenderData> m_data;
};

// Styling of the indoor layer, light or dark.
struct IndoorColors
{
  dp::Color m_fill;
  dp::Color m_outline;
};
IndoorColors GetIndoorAreaColors(indoor::Kind kind, bool dark);
// Symbol for a point feature (or the label of an area), empty if none fits.
std::string GetIndoorSymbol(indoor::Feature const & feature);
// Minimum zoom of a feature's label or icon.
int GetIndoorLabelMinZoom(indoor::Feature const & feature);
// Text shown for a feature: gates and rooms by ref when they have one, else by name.
std::string GetIndoorLabel(indoor::Feature const & feature);

// Overlay ids of the indoor layer: OverlayID(kIndoorOverlayIndexBase + feature index), unique and not on the map.
uint32_t constexpr kIndoorOverlayIndexBase = 1000000;

class IndoorBuilder
{
public:
  // Remembers what to show (for rebuilds after a style change or context loss) and builds it.
  IndoorRenderBatch Build(ref_ptr<dp::GraphicsContext> context, ref_ptr<dp::TextureManager> textures,
                          IndoorLevelParams const & params);
  // Builds the remembered level again (empty batch if there is none).
  IndoorRenderBatch Rebuild(ref_ptr<dp::GraphicsContext> context, ref_ptr<dp::TextureManager> textures);

  bool HasLevel() const { return m_params.m_venue != nullptr; }

private:
  IndoorRenderBatch BuildImpl(ref_ptr<dp::GraphicsContext> context, ref_ptr<dp::TextureManager> textures);

  IndoorLevelParams m_params;
  uint64_t m_buildId = 0;
};
}  // namespace df
