#include "testing/testing.hpp"

#include "ev_planning/ev_planning_tests/test_helpers.hpp"

#include "ev_planning/detours.hpp"
#include "ev_planning/planner.hpp"

#include "routing/checkpoints.hpp"
#include "routing/route.hpp"
#include "routing/router.hpp"
#include "routing/router_delegate.hpp"

#include "geometry/mercator.hpp"

#include "base/math.hpp"

#include <map>
#include <optional>
#include <string>
#include <vector>

// better-maps: real detours to the stations (DetourProvider) in the planner, and the estimates of RouterDetours.
namespace detour_tests
{
using namespace ev;
using namespace ev_planning_tests;

double constexpr kSpeedKmh = 100.0;

std::vector<std::string> GetNames(Plan const & plan)
{
  std::vector<std::string> names;
  for (auto const & stop : plan.m_stops)
    names.push_back(stop.m_station.m_name);
  return names;
}

// Real detours by station name, counts the calls.
class FakeDetours : public DetourProvider
{
public:
  std::optional<Detour> GetDetour(ChargingStation const & station) override
  {
    ++m_calls[station.m_name];
    auto const it = m_detours.find(station.m_name);
    if (it == m_detours.end())
      return Detour{station.m_detourSec, 0.0};
    return it->second;
  }

  std::optional<double> EstimateDetourSec(ChargingStation const & station) override
  {
    auto const it = m_estimates.find(station.m_name);
    if (it == m_estimates.end())
      return {};
    return it->second;
  }

  size_t GetCalls() const
  {
    size_t calls = 0;
    for (auto const & [name, count] : m_calls)
      calls += count;
    return calls;
  }

  std::map<std::string, Detour> m_detours;
  std::map<std::string, double> m_estimates;
  std::map<std::string, size_t> m_calls;
};

// Motorway services on both sides at km 150, as the search finds them: both about 150 m from the route, so about the
// same estimated detour. "Nord" on the other carriageway is a bit closer, but the real way there is via the next
// junctions (+40 km, 25 min); "Süd" is on the side of the route, with a second provider at the same place.
std::vector<ChargingStation> MakeServices()
{
  auto nord = MakeStation(1, 150.0, "type2_combo|4|300", "Ionity", 40.0);
  nord.m_name = "Rasthof Nord";
  auto sued = MakeStation(2, 150.3, "type2_combo|2|150", "EnBW", 45.0);
  sued.m_name = "Rasthof Süd";
  auto suedToo = MakeStation(3, 150.31, "type2_combo|2|150", "Aral pulse", 45.0);
  suedToo.m_name = "Rasthof Süd 2";
  suedToo.m_point = sued.m_point + m2::PointD(1e-5, 0.0);
  return {nord, sued, suedToo};
}

UNIT_TEST(Planner_RealDetourAvoidsTheOtherCarriageway)
{
  auto const & elroq = Elroq();
  auto const route = MakeRoute(elroq, 412.0, kSpeedKmh);
  auto const stations = MakeServices();
  PlanOptions options;
  options.m_startSocPct = 60.0;

  // With the estimates only: the services on the other carriageway.
  auto const estimated = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(estimated.m_status, PlanStatus::Ok, ());
  TEST_EQUAL(GetNames(estimated.m_plan), (std::vector<std::string>{"Rasthof Nord"}), ());
  TEST(!estimated.m_plan.m_stops[0].m_station.m_detourChecked, ());
  TEST_EQUAL(estimated.m_detourChecks, 0, ());

  // With the real detours: Nord costs 25 min more on the road, Süd is next to the route.
  FakeDetours detours;
  detours.m_detours["Rasthof Nord"] = {25.0 * 60.0, 40000.0};
  detours.m_detours["Rasthof Süd"] = {150.0, 300.0};
  detours.m_detours["Rasthof Süd 2"] = {150.0, 300.0};
  auto const real = PlanCharging(elroq, route, stations, options, &detours);
  TEST_EQUAL(real.m_status, PlanStatus::Ok, ());
  TEST(real.m_plan.m_meetsMinimums, ());
  TEST_EQUAL(real.m_plan.m_stops.size(), 1, ());
  auto const & stop = real.m_plan.m_stops[0];
  TEST(stop.m_station.m_name.starts_with("Rasthof Süd"), (stop.m_station.m_name));
  TEST(stop.m_station.m_detourChecked, ());
  TEST(AlmostEqualAbs(stop.m_station.m_detourSec, 150.0, 1e-6), ());
  TEST(AlmostEqualAbs(stop.m_station.m_detourDistanceM, 300.0, 1e-6), ());
  // The time of the plan includes the real detour: half of it on each leg.
  double const routeSec = 412.0 / kSpeedKmh * 3600.0;
  TEST(AlmostEqualAbs(real.m_plan.m_driveTimeSec, routeSec + 150.0, 1e-6), (real.m_plan.m_driveTimeSec));

  // Nord was checked once, both Süd stations together once (same place): the router runs rarely.
  TEST_EQUAL(detours.m_calls["Rasthof Nord"], 1, ());
  TEST_EQUAL(detours.m_calls["Rasthof Süd"] + detours.m_calls["Rasthof Süd 2"], 1, ());
  TEST_EQUAL(real.m_detourChecks, 2, ());

  // The alternatives have real detours too: Nord is not faster with its real detour.
  for (auto const & alternative : real.m_alternatives)
  {
    for (auto const & s : alternative.m_stops)
    {
      TEST(s.m_station.m_detourChecked, (s.m_station.m_name));
      if (s.m_station.m_name == "Rasthof Nord")
        TEST(AlmostEqualAbs(s.m_station.m_detourSec, 25.0 * 60.0, 1e-6), ());
    }
    TEST_GREATER_OR_EQUAL(alternative.m_totalTimeSec, real.m_plan.m_totalTimeSec - 1.0, ());
  }
}

UNIT_TEST(Planner_RealDetourEnergy)
{
  auto const & elroq = Elroq();
  auto const route = MakeRoute(elroq, 412.0, kSpeedKmh);
  auto station = MakeStation(1, 150.0, "type2_combo|4|150", "", 0.0);
  PlanOptions options;
  options.m_startSocPct = 82.0;
  auto const direct = EvaluateStops(elroq, route, {{station, 60.0}}, options);

  // 20 km more on the motorway in 12 min: more energy than 12 min at the detour speed of the estimates.
  station.m_detourSec = 12.0 * 60.0;
  auto const estimated = EvaluateStops(elroq, route, {{station, 60.0}}, options);
  station.m_detourChecked = true;
  station.m_detourDistanceM = 20000.0;
  auto const real = EvaluateStops(elroq, route, {{station, 60.0}}, options);
  TEST_LESS(real.m_arrivalSocPct, estimated.m_arrivalSocPct, ());
  TEST_LESS(estimated.m_arrivalSocPct, direct.m_arrivalSocPct, ());
  // About 20 km at 100 km/h: 4.3 % of the battery, half of it on the way to the station, half after it.
  double const drop = direct.m_stops[0].m_arrivalSocPct - real.m_stops[0].m_arrivalSocPct + direct.m_arrivalSocPct -
                      real.m_arrivalSocPct;
  TEST(drop > 3.5 && drop < 5.0, (drop));
}

UNIT_TEST(Planner_DetourEstimatesAndLimit)
{
  auto const & elroq = Elroq();
  auto const route = MakeRoute(elroq, 412.0, kSpeedKmh);
  std::vector<ChargingStation> stations;
  for (uint32_t i = 1; i * 10.0 < 400.0; ++i)
    stations.push_back(MakeStation(i, i * 10.0, "type2_combo|4|150", "", 60.0));
  PlanOptions options;
  options.m_startSocPct = 60.0;

  // A better estimate makes the planner avoid a station without checking it.
  auto const plain = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(plain.m_status, PlanStatus::Ok, ());
  auto const chosen = plain.m_plan.m_stops[0].m_station.m_name;
  FakeDetours estimates;
  estimates.m_estimates[chosen] = 30.0 * 60.0;
  auto const withEstimates = PlanCharging(elroq, route, stations, options, &estimates);
  TEST_NOT_EQUAL(withEstimates.m_plan.m_stops[0].m_station.m_name, chosen, ());
  TEST_EQUAL(estimates.m_calls.count(chosen), 0, ());

  // Every station is much worse than estimated: the checks end after kMaxDetourChecks, with a plan.
  FakeDetours worse;
  for (auto const & s : stations)
    worse.m_detours[s.m_name] = {s.m_detourSec + 300.0 + s.m_featureId.m_index, 1000.0};
  auto const limited = PlanCharging(elroq, route, stations, options, &worse);
  TEST_EQUAL(limited.m_status, PlanStatus::Ok, ());
  TEST_LESS_OR_EQUAL(worse.GetCalls(), kMaxDetourChecks, ());
  TEST_EQUAL(limited.m_detourChecks, worse.GetCalls(), ());
}

UNIT_TEST(Planner_StationsAtOnePlace)
{
  auto const & elroq = Elroq();
  auto const route = MakeRoute(elroq, 412.0, kSpeedKmh);
  // The same station mapped twice (a point and an area), and another place.
  auto a1 = MakeStation(1, 150.0, "type2_combo|4|300", "Ionity");
  auto a2 = MakeStation(2, 150.0, "type2_combo|4|300", "Ionity");
  a2.m_point = a1.m_point + m2::PointD(2e-5, 0.0);
  auto const b = MakeStation(3, 200.0, "type2_combo|4|150", "EnBW", 900.0);
  std::vector<ChargingStation> const stations = {a1, a2, b};
  PlanOptions options;
  options.m_startSocPct = 60.0;

  auto result = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(result.m_plan.m_stops.size(), 1, ());
  TEST_LESS(result.m_plan.m_stops[0].m_station.m_featureId.m_index, 3, ());
  // No alternative at the same place.
  TEST(!result.m_alternatives.empty(), ());
  for (auto const & alternative : result.m_alternatives)
    TEST_EQUAL(GetNames(alternative), (std::vector<std::string>{"Station 3"}), ());

  // "Choose another stop" excludes the place, not only the station.
  options.m_excludedStations = {result.m_plan.m_stops[0].m_station.GetKey()};
  result = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(GetNames(result.m_plan), (std::vector<std::string>{"Station 3"}), ());
}

// A route eastwards along the 50th parallel: 10 km of motorway, then 10 km of a primary road.
RouteLine MakeLine()
{
  std::vector<m2::PointD> points;
  std::vector<double> distances;
  std::vector<double> times;
  std::vector<ftypes::HighwayClass> classes;
  for (int i = 0; i <= 20; ++i)
  {
    points.push_back(mercator::FromLatLon(50.0, 7.0 + i / 71.5));
    distances.push_back(i * 1000.0);
    times.push_back(i * 36.0);
    classes.push_back(i <= 10 ? ftypes::HighwayClass::Motorway : ftypes::HighwayClass::Primary);
  }
  return RouteLine(points, distances, times, classes);
}

UNIT_TEST(RouteLine_Interpolation)
{
  auto const line = MakeLine();
  TEST(!line.IsEmpty(), ());
  TEST(AlmostEqualAbs(line.GetLengthM(), 20000.0, 1e-9), ());
  TEST(AlmostEqualAbs(line.GetTimeSecAt(2500.0), 90.0, 1e-9), ());
  TEST(AlmostEqualAbs(line.GetTimeSecAt(-5.0), 0.0, 1e-9), ());
  TEST(AlmostEqualAbs(line.GetTimeSecAt(1e6), 720.0, 1e-9), ());
  auto const p = line.GetPointAt(2500.0);
  TEST(AlmostEqualAbs(p, mercator::FromLatLon(50.0, 7.0 + 2.5 / 71.5), 1e-9), ());
  auto const direction = line.GetDirectionAt(2500.0);
  TEST_GREATER(direction.x, 0.0, ());
  TEST(AlmostEqualAbs(direction.y, 0.0, 1e-12), ());
  TEST_EQUAL(line.GetHighwayClassAt(5000.0), ftypes::HighwayClass::Motorway, ());
  TEST_EQUAL(line.GetHighwayClassAt(15000.0), ftypes::HighwayClass::Primary, ());
}

// Only the parts of RouterDetours that need no router.
class NoRouter : public routing::IRouter
{
public:
  std::string GetName() const override { return "none"; }
  void SetGuides(routing::GuidesTracks &&) override {}
  routing::RouterResultCode CalculateRoute(routing::Checkpoints const &, m2::PointD const &, bool,
                                           routing::RouterDelegate const &, routing::Route &) override
  {
    ++m_calls;
    return routing::RouterResultCode::RouteNotFound;
  }
  bool FindClosestProjectionToRoad(m2::PointD const &, m2::PointD const &, double, routing::EdgeProj &) override
  {
    return false;
  }

  size_t m_calls = 0;
};

UNIT_TEST(RouterDetours_EstimateBySide)
{
  NoRouter router;
  routing::RouterDelegate delegate;
  RouterDetours detours(router, delegate, MakeLine());

  // 150 m off the motorway at km 5: on the right (south, services of the own carriageway) and on the left.
  ChargingStation station;
  station.m_distanceAlongRouteM = 5000.0;
  station.m_distanceFromRouteM = 150.0;
  station.m_detourSec = 40.0;
  station.m_point = mercator::FromLatLon(49.99865, 7.0 + 5.0 / 71.5);
  auto const right = detours.EstimateDetourSec(station);
  TEST(right, ());
  TEST(AlmostEqualAbs(*right, 40.0 + RouterDetours::kLeaveFastRoadSec, 1e-9), ());

  station.m_point = mercator::FromLatLon(50.00135, 7.0 + 5.0 / 71.5);
  auto const left = detours.EstimateDetourSec(station);
  TEST(left, ());
  TEST(AlmostEqualAbs(*left, 40.0 + RouterDetours::kLeaveFastRoadSec + RouterDetours::kOtherSideOfMotorwaySec, 1e-9),
       ());

  // On the primary road one can turn left: the estimate of the search stays.
  station.m_distanceAlongRouteM = 15000.0;
  station.m_point = mercator::FromLatLon(50.00135, 7.0 + 15.0 / 71.5);
  TEST(!detours.EstimateDetourSec(station), ());

  // No route: the estimate stays too.
  TEST(!detours.GetDetour(station), ());
  TEST_EQUAL(router.m_calls, 1, ());
}
}  // namespace detour_tests
