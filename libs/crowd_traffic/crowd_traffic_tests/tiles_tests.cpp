#include "testing/testing.hpp"

#include "crowd_traffic/crowd_traffic_tests/go_vectors.hpp"
#include "crowd_traffic/tiles.hpp"

#include <string>

namespace tiles_tests
{
using namespace crowd_traffic;

UNIT_TEST(CrowdTraffic_ProjectionMatchesGo)
{
  for (auto const & p : go_vectors::kProjections)
  {
    auto const px = Project(p.lat, p.lon);
    TEST_ALMOST_EQUAL_ABS(px.m_x, p.px, 1e-5, (p.lat, p.lon));
    TEST_ALMOST_EQUAL_ABS(px.m_y, p.py, 1e-5, (p.lat, p.lon));
    auto const t = TileAt(p.lat, p.lon);
    TEST_EQUAL(t.m_x, p.tileX, (p.lat, p.lon));
    TEST_EQUAL(t.m_y, p.tileY, (p.lat, p.lon));
  }
}

UNIT_TEST(CrowdTraffic_DistanceMatchesGo)
{
  for (auto const & d : go_vectors::kDistances)
    TEST_ALMOST_EQUAL_ABS(DistanceM(d.lat1, d.lon1, d.lat2, d.lon2), d.m, 1e-6, ());
}

UNIT_TEST(CrowdTraffic_UnprojectRoundTrip)
{
  for (double lat : {-80.0, -33.8688, 0.0, 47.2, 52.52, 55.1, 80.0})
  {
    for (double lon : {-179.9, -0.1, 0.0, 13.405, 151.2})
    {
      auto const p = Project(lat, lon);
      auto const ll = Unproject(p.m_x, p.m_y);
      TEST_ALMOST_EQUAL_ABS(ll.m_lat, lat, 1e-9, ());
      TEST_ALMOST_EQUAL_ABS(ll.m_lon, lon, 1e-9, ());
    }
  }
  // Latitudes beyond the Mercator limit are clamped.
  TEST_ALMOST_EQUAL_ABS(Project(89.0, 0).m_y, Project(kMaxLat, 0).m_y, 1e-6, ());
  TEST_ALMOST_EQUAL_ABS(Project(-89.0, 0).m_y, Project(-kMaxLat, 0).m_y, 1e-6, ());
}

UNIT_TEST(CrowdTraffic_TileSize)
{
  // A z17 tile is about 305 m · cos(latitude): ~186 m in Berlin.
  auto const t = TileAt(52.52, 13.405);
  auto const nw = Unproject(t.m_x * kTileSize, t.m_y * kTileSize);
  auto const ne = Unproject((t.m_x + 1) * kTileSize, t.m_y * kTileSize);
  double const w = DistanceM(nw.m_lat, nw.m_lon, ne.m_lat, ne.m_lon);
  TEST_GREATER(w, 180, ());
  TEST_LESS(w, 192, ());
}

UNIT_TEST(CrowdTraffic_SidesAndNeighbors)
{
  TEST_EQUAL(std::string(SideName(Side::North)), "N", ());
  TEST_EQUAL(std::string(SideName(Side::East)), "E", ());
  TEST_EQUAL(std::string(SideName(Side::South)), "S", ());
  TEST_EQUAL(std::string(SideName(Side::West)), "W", ());
  TEST_EQUAL(std::string(SideName(Side::None)), "", ());
  TEST(Opposite(Side::North) == Side::South, ());
  TEST(Opposite(Side::South) == Side::North, ());
  TEST(Opposite(Side::East) == Side::West, ());
  TEST(Opposite(Side::West) == Side::East, ());
  TEST(Opposite(Side::None) == Side::None, ());

  Tile const t{100, 200};
  TEST_EQUAL(t.Neighbor(Side::North), (Tile{100, 199}), ());
  TEST_EQUAL(t.Neighbor(Side::South), (Tile{100, 201}), ());
  TEST_EQUAL(t.Neighbor(Side::East), (Tile{101, 200}), ());
  TEST_EQUAL(t.Neighbor(Side::West), (Tile{99, 200}), ());
  TEST_EQUAL(t.Neighbor(Side::None), t, ());
  TEST(t.IsValid(), ());
  TEST(!(Tile{-1, 0}.IsValid()), ());
  TEST(!(Tile{0, kNumTiles}.IsValid()), ());
  TEST_EQUAL(DebugPrint(t), "17/100/200", ());
}
}  // namespace tiles_tests
