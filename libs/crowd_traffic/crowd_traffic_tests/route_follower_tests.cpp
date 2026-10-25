#include "testing/testing.hpp"

#include "crowd_traffic/crowd_traffic_tests/test_support.hpp"
#include "crowd_traffic/route_follower.hpp"
#include "crowd_traffic/segmenter.hpp"

#include <vector>

namespace route_follower_tests
{
using namespace crowd_traffic;
using namespace crowd_traffic::tests;

// A route with a bend: north, then east, with a motorway link in between.
std::vector<LatLon> const kVertices = {{52.400, 13.300}, {52.405, 13.300}, {52.410, 13.300},
                                       {52.410, 13.305}, {52.410, 13.315}, {52.410, 13.330}};
std::vector<RoadClass> const kClasses = {RoadClass::Primary, RoadClass::Primary, RoadClass::None, RoadClass::Motorway,
                                         RoadClass::Motorway};

LatLon VertexAt(size_t k)
{
  return kVertices.at(k);
}
RoadClass ClassAt(size_t k)
{
  return k < kClasses.size() ? kClasses[k] : RoadClass::None;
}

UNIT_TEST(CrowdTraffic_RouteFollowerInsertsPassedVertices)
{
  RouteFollower f;
  // First fix on segment 0: only the position itself.
  auto s = f.Next(true, 0, {52.401, 13.300}, 100, 5000, VertexAt, ClassAt);
  TEST_EQUAL(s.size(), 1, ());
  TEST(s[0].m_class == RoadClass::Primary, ());
  TEST_EQUAL(s[0].m_remainingRouteM, 5000, ());

  // Next fix on segment 3, after the bend: vertices 1, 2 and 3 are inserted in between.
  s = f.Next(true, 3, {52.410, 13.307}, 160, 2500, VertexAt, ClassAt);
  TEST_EQUAL(s.size(), 4, ());
  TEST_EQUAL(s[0].m_lat, kVertices[1].m_lat, ());
  TEST_EQUAL(s[1].m_lat, kVertices[2].m_lat, ());
  TEST_EQUAL(s[2].m_lon, kVertices[3].m_lon, ());
  TEST(s[0].m_class == RoadClass::Primary, ());
  TEST(s[1].m_class == RoadClass::None, ());  // The link.
  TEST(s[2].m_class == RoadClass::Motorway, ());
  TEST(s[3].m_class == RoadClass::Motorway, ());
  // Times are interpolated by distance and increase; remaining distances decrease.
  for (size_t i = 0; i < s.size(); ++i)
  {
    TEST_GREATER(s[i].m_time, i == 0 ? 100.0 : s[i - 1].m_time, ());
    if (i > 0)
      TEST_LESS(s[i].m_remainingRouteM, s[i - 1].m_remainingRouteM, ());
  }
  TEST_EQUAL(s.back().m_time, 160.0, ());
  TEST_EQUAL(s.back().m_remainingRouteM, 2500.0, ());
  double const d01 = DistanceM(52.401, 13.300, kVertices[1].m_lat, kVertices[1].m_lon);
  double const d12 = DistanceM(kVertices[1].m_lat, kVertices[1].m_lon, kVertices[2].m_lat, kVertices[2].m_lon);
  TEST_ALMOST_EQUAL_ABS((s[1].m_time - s[0].m_time) / (s[0].m_time - 100), d12 / d01, 1e-9, ());
  TEST_ALMOST_EQUAL_ABS(s[0].m_remainingRouteM - s[1].m_remainingRouteM, d12, 1e-6, ());

  // Same segment again: just the position.
  s = f.Next(true, 3, {52.410, 13.309}, 170, 2370, VertexAt, ClassAt);
  TEST_EQUAL(s.size(), 1, ());
  // A new route (rebuild): no vertices of the old one.
  s = f.Next(false, 5, {52.410, 13.331}, 200, 1000, VertexAt, ClassAt);
  TEST_EQUAL(s.size(), 1, ());
  TEST(s[0].m_class == RoadClass::None, ());
  // After a reset (gap) neither.
  f.Reset();
  s = f.Next(true, 4, {52.410, 13.320}, 300, 800, VertexAt, ClassAt);
  TEST_EQUAL(s.size(), 1, ());
}

UNIT_TEST(CrowdTraffic_RouteFollowerTraceFollowsTheRoad)
{
  // Sparse fixes (every 20 s) on a route with a bend: the trace through the inserted vertices
  // has the length of the route, not of the shortcut.
  RouteFollower f;
  Segmenter seg;
  std::vector<Traversal> trav;
  std::vector<std::pair<size_t, LatLon>> const fixes = {
      {0, {52.400, 13.300}}, {1, {52.407, 13.300}}, {3, {52.410, 13.308}}, {4, {52.410, 13.329}}};
  double t = 0;
  for (auto const & [idx, p] : fixes)
  {
    for (auto const & s : f.Next(true, idx, p, t, 0, VertexAt, ClassAt))
      seg.Add({s.m_lat, s.m_lon, s.m_time, s.m_class}, trav);
    t += 20;
  }
  double const routeLen = DistanceM(52.400, 13.300, 52.410, 13.300) + DistanceM(52.410, 13.300, 52.410, 13.329);
  TEST_ALMOST_EQUAL_ABS(seg.GetDistanceM(), routeLen, 1e-3, ());
}
}  // namespace route_follower_tests
