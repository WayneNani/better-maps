#pragma once

#include <cstdint>
#include <string>

// Map-version-independent segment grid of the crowd-traffic protocol (docs/CROWD_TRAFFIC_PRIVACY.md,
// section 3): Web Mercator ("slippy map") tiles at zoom level 17 and positions on their edges.
// Port of services/crowd-traffic/internal/tiles; the computations must stay identical.
namespace crowd_traffic
{
int constexpr kZoom = 17;
// Number of pixels along a tile edge; edge offsets are in [0, kTileSize).
int constexpr kTileSize = 256;
// Number of tiles along each axis.
int constexpr kNumTiles = 1 << kZoom;
// Latitude limit of the Web Mercator projection.
double constexpr kMaxLat = 85.05112877980659;

enum class Side : uint8_t
{
  None = 0,
  North,
  East,
  South,
  West
};

// "N", "E", "S", "W" (empty for Side::None).
char const * SideName(Side side);
Side Opposite(Side side);

struct Tile
{
  int32_t m_x = 0;
  int32_t m_y = 0;

  bool IsValid() const { return m_x >= 0 && m_x < kNumTiles && m_y >= 0 && m_y < kNumTiles; }
  Tile Neighbor(Side side) const;
  bool operator==(Tile const & rhs) const = default;
  auto operator<=>(Tile const & rhs) const = default;
};

std::string DebugPrint(Tile const & tile);

struct PixelPoint
{
  double m_x = 0.0;
  double m_y = 0.0;
};

struct LatLon
{
  double m_lat = 0.0;
  double m_lon = 0.0;
};

// Converts WGS84 coordinates to global pixel coordinates at zoom level kZoom (x grows eastwards,
// y southwards; tile = floor(pixel / kTileSize)).
PixelPoint Project(double lat, double lon);
// Inverse of Project.
LatLon Unproject(double px, double py);
// The tile containing the given coordinates.
Tile TileAt(double lat, double lon);
// Great-circle distance in metres (same Earth radius as the server).
double DistanceM(double lat1, double lon1, double lat2, double lon2);
}  // namespace crowd_traffic
