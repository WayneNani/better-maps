#include "testing/testing.hpp"

#include "search/route_corridor.hpp"

#include "geometry/mercator.hpp"

#include "base/math.hpp"

#include <algorithm>
#include <cmath>
#include <numeric>
#include <vector>

namespace route_corridor_tests
{
using search::RouteCorridor;
using search::RoutePosition;
using search::SpreadAlongRoute;
using std::vector;

double constexpr kLat = 50.0;

// A point |km| east of 7°E and |northM| north of 50°N.
m2::PointD At(double km, double northM = 0.0)
{
  double const metersPerLonDegree = 111320.0 * std::cos(math::DegToRad(kLat));
  return mercator::FromLatLon(kLat + northM / 111200.0, 7.0 + km * 1000.0 / metersPerLonDegree);
}

// A straight route of |lengthKm| eastwards, with a point every kilometer.
RouteCorridor::Params StraightRoute(double lengthKm, double halfWidthM)
{
  RouteCorridor::Params params;
  for (double km = 0; km <= lengthKm; km += 1.0)
    params.m_points.push_back(At(km));
  params.m_halfWidthM = halfWidthM;
  return params;
}

bool AlmostEqual(double a, double b, double eps)
{
  return std::abs(a - b) <= eps;
}

UNIT_TEST(RouteCorridor_InsideOutside)
{
  RouteCorridor const corridor(StraightRoute(30, 1000));
  TEST(!corridor.IsEmpty(), ());
  TEST(AlmostEqual(corridor.GetLengthM(), 30000, 300), (corridor.GetLengthM()));

  auto const left = corridor.Locate(At(10.3, 500));
  TEST(left, ());
  TEST(AlmostEqual(left->m_distanceFromRouteM, 500, 10), (*left));
  TEST(AlmostEqual(left->m_distanceAlongRouteM, 10300, 100), (*left));

  auto const right = corridor.Locate(At(20, -900));
  TEST(right, ());
  TEST(AlmostEqual(right->m_distanceFromRouteM, 900, 10), (*right));

  TEST(!corridor.Locate(At(10, 1100)), ());
  TEST(!corridor.Locate(At(10, -1500)), ());
  // Beyond the route end: in the corridor around the destination only.
  TEST(corridor.Locate(At(30.5)), ());
  TEST(!corridor.Locate(At(32)), ());

  // A narrower corridor, e.g. for a pedestrian.
  RouteCorridor const narrow(StraightRoute(30, 150));
  TEST(!narrow.Locate(At(10.3, 500)), ());
  auto const near = narrow.Locate(At(10.3, 100));
  TEST(near, ());
  TEST(AlmostEqual(near->m_distanceFromRouteM, 100, 5), (*near));
}

UNIT_TEST(RouteCorridor_OnlyAhead)
{
  auto params = StraightRoute(30, 1000);
  params.m_passedDistanceM = 10000;
  RouteCorridor const corridor(params);
  TEST(AlmostEqual(corridor.GetLengthM(), 20000, 300), (corridor.GetLengthM()));

  // Already passed.
  TEST(!corridor.Locate(At(5, 200)), ());
  TEST(!corridor.Locate(At(9.7)), ());
  TEST(!corridor.Locate(At(9.7, 300)), ());

  // Just being passed by: a bit behind or beside the current position.
  auto const beside = corridor.Locate(At(9.99, -300));
  TEST(beside, ());
  TEST(AlmostEqual(beside->m_distanceAlongRouteM, 0, 20), (*beside));

  // Ahead: the distance is counted from the current position.
  auto const ahead = corridor.Locate(At(15, 200));
  TEST(ahead, ());
  TEST(AlmostEqual(ahead->m_distanceAlongRouteM, 5000, 100), (*ahead));
}

UNIT_TEST(RouteCorridor_Empty)
{
  {
    auto params = StraightRoute(5, 1000);
    params.m_passedDistanceM = 6000;
    RouteCorridor const corridor(params);
    TEST(corridor.IsEmpty(), ());
    TEST(!corridor.Locate(At(4.9)), ());
  }
  {
    RouteCorridor::Params params;
    params.m_points = {At(1)};
    TEST(RouteCorridor(params).IsEmpty(), ());
  }
  {
    // Times must match the points.
    auto params = StraightRoute(5, 1000);
    params.m_timesSec = {0, 1};
    TEST(RouteCorridor(params).IsEmpty(), ());
  }
}

UNIT_TEST(RouteCorridor_TimesAndDetour)
{
  RouteCorridor::Params params;
  params.m_points = {At(0), At(20), At(30)};
  params.m_timesSec = {0, 1000, 1600};
  params.m_halfWidthM = 1000;
  params.m_passedDistanceM = 10000;
  params.m_detourSpeedMps = 10;
  params.m_detourCircuity = 1.5;
  RouteCorridor const corridor(params);

  auto const position = corridor.Locate(At(25, 500));
  TEST(position, ());
  // 500 s from 10 km to 20 km, then half of 600 s to 25 km.
  TEST(AlmostEqual(position->m_timeAlongRouteSec, 800, 10), (*position));
  TEST(AlmostEqual(position->m_distanceAlongRouteM, 15000, 150), (*position));
  // There and back: 2 * 500 m * 1.5 at 10 m/s.
  TEST(AlmostEqual(position->m_detourSec, 150, 3), (*position));
  TEST(AlmostEqual(position->GetTimeToPlaceSec(), 875, 12), (*position));

  // Without times.
  params.m_timesSec.clear();
  auto const withoutTimes = RouteCorridor(params).Locate(At(25, 500));
  TEST(withoutTimes, ());
  TEST_EQUAL(withoutTimes->m_timeAlongRouteSec, 0.0, ());
}

UNIT_TEST(RouteCorridor_FirstPass)
{
  // There and back on two parallel roads 400 m apart: east on the southern road, west on the northern one.
  RouteCorridor::Params params;
  params.m_points = {At(0), At(10), At(10, 400), At(0, 400)};
  params.m_halfWidthM = 1000;
  RouteCorridor const corridor(params);

  // Near the northern road, but not much nearer than to the southern one: located on the way there.
  auto const between = corridor.Locate(At(5, 210));
  TEST(between, ());
  TEST(AlmostEqual(between->m_distanceAlongRouteM, 5000, 100), (*between));
  TEST(AlmostEqual(between->m_distanceFromRouteM, 210, 10), (*between));

  // Much nearer to the northern road: located on the way back.
  auto const north = corridor.Locate(At(5, 350));
  TEST(north, ());
  TEST(AlmostEqual(north->m_distanceAlongRouteM, 15400, 150), (*north));
  TEST(AlmostEqual(north->m_distanceFromRouteM, 50, 10), (*north));
}

UNIT_TEST(RouteCorridor_CoveringRects)
{
  // A diagonal route: the covering rects must contain the whole corridor.
  RouteCorridor::Params params;
  for (int i = 0; i <= 40; ++i)
    params.m_points.push_back(At(i, i * 700.0));
  params.m_halfWidthM = 1500;
  RouteCorridor const corridor(params);

  auto const rects = corridor.GetCoveringRects();
  TEST(!rects.empty(), ());
  for (double km = 0; km <= 40; km += 0.25)
  {
    for (double side : {-1450.0, -700.0, 0.0, 700.0, 1450.0})
    {
      // Perpendicular to the route direction (1 km east, 700 m north).
      double const norm = std::hypot(1000.0, 700.0);
      auto const p = At(km - side * 700.0 / norm / 1000.0, km * 700.0 + side * 1000.0 / norm);
      TEST(corridor.Locate(p), (km, side));
      bool covered = false;
      for (auto const & r : rects)
        covered = covered || r.IsPointInside(p);
      TEST(covered, (km, side));
      TEST(corridor.GetLimitRect().IsPointInside(p), (km, side));
    }
  }
}

UNIT_TEST(SpreadAlongRoute_Smoke)
{
  // Fewer places than the limit: all of them, ordered along the route.
  {
    vector<double> const distances = {500, 100, 300};
    auto const selected = SpreadAlongRoute(distances, {0, 0, 0}, 1000, 10);
    TEST_EQUAL(selected, (vector<size_t>{1, 2, 0}), ());
  }
  TEST(SpreadAlongRoute({1, 2}, {0, 0}, 10, 0).empty(), ());
}

UNIT_TEST(SpreadAlongRoute_TownDoesNotDisplaceTheRest)
{
  // 100 places in a town at the start of a 100 km route, then one place every 10 km. With 30 places, the route is
  // split into 7 parts of about 14 km.
  vector<double> distances;
  for (int i = 0; i < 100; ++i)
    distances.push_back(i * 10.0);
  for (int km = 20; km < 100; km += 10)
    distances.push_back(km * 1000.0);
  vector<double> const priorities(distances.size(), 0.0);

  auto const selected = SpreadAlongRoute(distances, priorities, 100000, 30);
  TEST_EQUAL(selected.size(), 30, ());
  TEST(
      std::is_sorted(selected.begin(), selected.end(), [&](size_t l, size_t r) { return distances[l] < distances[r]; }),
      ());
  // All places outside the town's part of the route are kept, the rest are the first places of the town.
  for (size_t i = 100; i < distances.size(); ++i)
    TEST(std::find(selected.begin(), selected.end(), i) != selected.end(), (i));
  for (size_t i = 0; i < 30 - 8; ++i)
    TEST_EQUAL(selected[i], i, ());
}

UNIT_TEST(SpreadAlongRoute_Priorities)
{
  // In the same part of the route, places with smaller priorities (e.g. nearer to the route) are taken first.
  vector<double> const distances = {100, 200, 300, 400};
  vector<double> const priorities = {900, 10, 500, 20};
  auto const selected = SpreadAlongRoute(distances, priorities, 1000, 2);
  TEST_EQUAL(selected, (vector<size_t>{1, 3}), ());
}
}  // namespace route_corridor_tests
