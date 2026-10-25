#include "crowd_traffic/tiles.hpp"

#include <algorithm>
#include <cmath>
#include <numbers>
#include <sstream>

namespace crowd_traffic
{
char const * SideName(Side side)
{
  switch (side)
  {
  case Side::North: return "N";
  case Side::East: return "E";
  case Side::South: return "S";
  case Side::West: return "W";
  case Side::None: return "";
  }
  return "";
}

Side Opposite(Side side)
{
  switch (side)
  {
  case Side::North: return Side::South;
  case Side::South: return Side::North;
  case Side::East: return Side::West;
  case Side::West: return Side::East;
  case Side::None: return Side::None;
  }
  return Side::None;
}

Tile Tile::Neighbor(Side side) const
{
  Tile t = *this;
  switch (side)
  {
  case Side::North: --t.m_y; break;
  case Side::South: ++t.m_y; break;
  case Side::East: ++t.m_x; break;
  case Side::West: --t.m_x; break;
  case Side::None: break;
  }
  return t;
}

std::string DebugPrint(Tile const & tile)
{
  std::ostringstream out;
  out << kZoom << "/" << tile.m_x << "/" << tile.m_y;
  return out.str();
}

PixelPoint Project(double lat, double lon)
{
  lat = std::max(-kMaxLat, std::min(kMaxLat, lat));
  double constexpr world = static_cast<double>(kNumTiles) * kTileSize;
  double const s = std::sin(lat * std::numbers::pi / 180);
  return {(lon + 180) / 360 * world, (0.5 - std::log((1 + s) / (1 - s)) / (4 * std::numbers::pi)) * world};
}

LatLon Unproject(double px, double py)
{
  double constexpr world = static_cast<double>(kNumTiles) * kTileSize;
  double const n = std::numbers::pi - 2 * std::numbers::pi * py / world;
  return {180 / std::numbers::pi * std::atan(std::sinh(n)), px / world * 360 - 180};
}

Tile TileAt(double lat, double lon)
{
  auto const p = Project(lat, lon);
  return {static_cast<int32_t>(std::floor(p.m_x / kTileSize)), static_cast<int32_t>(std::floor(p.m_y / kTileSize))};
}

double DistanceM(double lat1, double lon1, double lat2, double lon2)
{
  double constexpr r = 6371008.8;
  double constexpr rad = std::numbers::pi / 180;
  double const dLat = (lat2 - lat1) * rad;
  double const dLon = (lon2 - lon1) * rad;
  double const a = std::sin(dLat / 2) * std::sin(dLat / 2) +
                   std::cos(lat1 * rad) * std::cos(lat2 * rad) * std::sin(dLon / 2) * std::sin(dLon / 2);
  return 2 * r * std::asin(std::min(1.0, std::sqrt(a)));
}
}  // namespace crowd_traffic
