#include "testing/testing.hpp"

#include "ev_planning/ev_planning_tests/test_helpers.hpp"

#include "ev_planning/planner.hpp"

#include "base/math.hpp"

#include <algorithm>
#include <string>
#include <vector>

namespace planner_tests
{
using namespace ev;
using namespace ev_planning_tests;

// The Elroq needs about 0.214 % of its battery per km at 100 km/h on a level road.
double constexpr kSpeedKmh = 100.0;

std::vector<std::string> GetNames(Plan const & plan)
{
  std::vector<std::string> names;
  for (auto const & stop : plan.m_stops)
    names.push_back(stop.m_station.m_name);
  return names;
}

Amenity MakeAmenity(AmenityType type, double distanceM)
{
  Amenity a;
  a.m_type = type;
  a.m_distanceM = distanceM;
  return a;
}

void TestPlanIsConsistent(Plan const & plan, PlanOptions const & options)
{
  TEST(plan.m_meetsMinimums, ());
  TEST_GREATER_OR_EQUAL(plan.m_arrivalSocPct, options.m_minArrivalSocPct - 0.5, ());
  double previous = 0.0;
  double stopTime = 0.0;
  for (auto const & stop : plan.m_stops)
  {
    TEST_GREATER(stop.m_station.m_distanceAlongRouteM, previous, ());
    previous = stop.m_station.m_distanceAlongRouteM;
    TEST_GREATER_OR_EQUAL(stop.m_arrivalSocPct, options.m_minStopSocPct - 0.5, ());
    TEST_GREATER(stop.m_departureSocPct, stop.m_arrivalSocPct, ());
    TEST_LESS_OR_EQUAL(stop.m_departureSocPct, options.m_maxChargeSocPct, ());
    TEST_GREATER(stop.m_chargingTimeSec, 0.0, ());
    TEST(AlmostEqualAbs(stop.m_stopTimeSec, stop.m_chargingTimeSec + kStopOverheadSec, 1e-6), ());
    stopTime += stop.m_stopTimeSec;
  }
  TEST(AlmostEqualAbs(plan.m_stopTimeSec, stopTime, 1e-6), ());
  TEST(AlmostEqualAbs(plan.m_totalTimeSec, plan.m_driveTimeSec + plan.m_stopTimeSec, 1e-6), ());
}

UNIT_TEST(Planner_NoStopNeeded)
{
  auto const & elroq = Elroq();
  auto const route = MakeRoute(elroq, 100.0, kSpeedKmh);
  std::vector<ChargingStation> stations = {MakeStation(1, 50.0, "type2_combo|4|150")};
  PlanOptions options;
  options.m_startSocPct = 80.0;

  auto const result = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(result.m_status, PlanStatus::Ok, ());
  TEST(result.m_plan.m_stops.empty(), ());
  TEST(result.m_alternatives.empty(), ());
  TEST(AlmostEqualAbs(result.m_plan.m_arrivalSocPct, 80.0 - 100.0 * 0.1649 / 77.0 * 100.0, 0.1),
       (result.m_plan.m_arrivalSocPct));
  TEST(AlmostEqualAbs(result.m_plan.m_totalTimeSec, 3600.0, 1e-6), ());
  TestPlanIsConsistent(result.m_plan, options);
}

UNIT_TEST(Planner_OneStop)
{
  auto const & elroq = Elroq();
  // 412 km like Bonn - Nuremberg, a station every 30 km.
  auto const route = MakeRoute(elroq, 412.0, kSpeedKmh);
  std::vector<ChargingStation> stations;
  for (uint32_t i = 1; i * 30.0 < 412.0; ++i)
    stations.push_back(MakeStation(i, i * 30.0, "type2_combo|4|150"));

  PlanOptions options;
  options.m_startSocPct = 82.0;
  auto const result = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(result.m_status, PlanStatus::Ok, ());
  TEST_EQUAL(result.m_plan.m_stops.size(), 1, (GetNames(result.m_plan)));
  TestPlanIsConsistent(result.m_plan, options);
  TEST_EQUAL(result.m_candidatesCount, stations.size(), ());
  TEST_EQUAL(result.m_usableCount, stations.size(), ());

  // The routing time plus the stop.
  auto const & stop = result.m_plan.m_stops.front();
  TEST(AlmostEqualAbs(result.m_plan.m_driveTimeSec, 412.0 / kSpeedKmh * 3600.0 + 60.0, 1e-6), ());
  TEST(AlmostEqualAbs(result.m_plan.m_totalTimeSec, result.m_plan.m_driveTimeSec + stop.m_stopTimeSec, 1e-6), ());
  // Charges just enough: arrival close to the minimum.
  TEST_LESS(result.m_plan.m_arrivalSocPct, options.m_minArrivalSocPct + 1.5, ());
  TEST(AlmostEqualAbs(stop.m_arrivalTimeSec, stop.m_station.m_distanceAlongRouteM / 1000.0 / kSpeedKmh * 3600.0 + 30.0,
                      1e-6),
       ());
  TEST_GREATER(stop.m_averagePowerKw, 100.0, ());
  TEST_LESS_OR_EQUAL(stop.m_averagePowerKw, 135.0, ());

  // "Charge longer": 10 % more, later at the stop, more at the arrival.
  TEST(stop.m_longer, ());
  TEST(AlmostEqualAbs(stop.m_longer->m_departureSocPct, stop.m_departureSocPct + kLongerChargePct, 1e-6), ());
  TEST_GREATER(stop.m_longer->m_extraStopTimeSec, 0.0, ());
  TEST(AlmostEqualAbs(stop.m_longer->m_extraTotalTimeSec, stop.m_longer->m_extraStopTimeSec, 1e-6), ());
  TEST(AlmostEqualAbs(stop.m_longer->m_arrivalSocPct, result.m_plan.m_arrivalSocPct + kLongerChargePct, 1e-6), ());

  // Alternatives with other stations.
  TEST(!result.m_alternatives.empty(), ());
  for (auto const & alternative : result.m_alternatives)
  {
    TEST(!alternative.HasSameStations(result.m_plan), ());
    TestPlanIsConsistent(alternative, options);
    TEST_GREATER_OR_EQUAL(alternative.m_totalTimeSec, result.m_plan.m_totalTimeSec - 1.0, ());
  }
}

UNIT_TEST(Planner_TwoStops)
{
  auto const & elroq = Elroq();
  auto const route = MakeRoute(elroq, 750.0, kSpeedKmh);
  std::vector<ChargingStation> stations;
  for (uint32_t i = 1; i * 25.0 < 750.0; ++i)
    stations.push_back(MakeStation(i, i * 25.0, "type2_combo|4|300"));

  PlanOptions options;
  options.m_startSocPct = 80.0;
  options.m_priority = Priority::FewestStops;
  auto const result = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(result.m_status, PlanStatus::Ok, ());
  // 750 km need 161 %: 60 % from the start, 101 % more, which one stop (10 -> 100 %) can't do.
  TEST_EQUAL(result.m_plan.m_stops.size(), 2, (GetNames(result.m_plan)));
  TestPlanIsConsistent(result.m_plan, options);
}

// Stations for the priorities:
// - C at km 100 (150 kW): the destination is too far for one stop there.
// - A at km 200 (50 kW only): one long stop.
// - B at km 330 (150 kW): fast, but only reachable after a short stop at C or A.
std::vector<ChargingStation> MakePriorityStations()
{
  return {MakeStation(1, 100.0, "type2_combo|2|150"), MakeStation(2, 200.0, "type2_combo|2|50"),
          MakeStation(3, 330.0, "type2_combo|2|150")};
}

UNIT_TEST(Planner_Priorities)
{
  auto const & elroq = Elroq();
  auto const route = MakeRoute(elroq, 500.0, kSpeedKmh);
  auto const stations = MakePriorityStations();

  PlanOptions options;
  options.m_startSocPct = 80.0;

  options.m_priority = Priority::Fastest;
  auto const fastest = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(fastest.m_status, PlanStatus::Ok, ());
  TEST_EQUAL(GetNames(fastest.m_plan), (std::vector<std::string>{"Station 1", "Station 3"}), ());
  TestPlanIsConsistent(fastest.m_plan, options);
  // The first stop only charges what is needed to reach the fast station.
  TEST_LESS(fastest.m_plan.m_stops[0].m_chargingTimeSec, 2.0 * 60.0, ());

  options.m_priority = Priority::FewestStops;
  auto const fewest = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(fewest.m_status, PlanStatus::Ok, ());
  TEST_EQUAL(GetNames(fewest.m_plan), (std::vector<std::string>{"Station 2"}), ());
  TestPlanIsConsistent(fewest.m_plan, options);

  options.m_priority = Priority::LongBreaks;
  auto const longBreaks = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(GetNames(longBreaks.m_plan), (std::vector<std::string>{"Station 2"}), ());

  // Plausible: fewer stops take longer here.
  TEST_LESS(fastest.m_plan.m_totalTimeSec + 10.0 * 60.0, fewest.m_plan.m_totalTimeSec, ());
  TEST_GREATER(fewest.m_plan.m_stops[0].m_chargingTimeSec, 35.0 * 60.0, ());

  // The other priority is an alternative of each.
  TEST(!fastest.m_alternatives.empty(), ());
  bool const hasOneStop = std::any_of(fastest.m_alternatives.begin(), fastest.m_alternatives.end(),
                                      [](Plan const & p) { return p.m_stops.size() == 1; });
  TEST(hasOneStop, ());
  TEST(!fewest.m_alternatives.empty(), ());
  TEST_EQUAL(fewest.m_alternatives.front().m_alternativeReason, "priority", ());
}

UNIT_TEST(Planner_LongBreaksPreferLongerStops)
{
  auto const & elroq = Elroq();
  auto const route = MakeRoute(elroq, 650.0, kSpeedKmh);
  std::vector<ChargingStation> stations;
  for (uint32_t i = 1; i * 20.0 < 650.0; ++i)
    stations.push_back(MakeStation(i, i * 20.0, "type2_combo|4|150"));

  PlanOptions options;
  options.m_startSocPct = 80.0;
  auto const fastest = PlanCharging(elroq, route, stations, options);
  options.m_priority = Priority::LongBreaks;
  auto const longBreaks = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(fastest.m_status, PlanStatus::Ok, ());
  TEST_EQUAL(longBreaks.m_status, PlanStatus::Ok, ());
  TestPlanIsConsistent(longBreaks.m_plan, options);
  TEST_LESS_OR_EQUAL(longBreaks.m_plan.m_stops.size(), fastest.m_plan.m_stops.size(), ());
  TEST_LESS_OR_EQUAL(fastest.m_plan.m_totalTimeSec, longBreaks.m_plan.m_totalTimeSec + 1.0, ());
  double const fastestAverage = fastest.m_plan.m_stopTimeSec / fastest.m_plan.m_stops.size();
  double const longAverage = longBreaks.m_plan.m_stopTimeSec / longBreaks.m_plan.m_stops.size();
  TEST_GREATER_OR_EQUAL(longAverage, fastestAverage, ());
}

UNIT_TEST(Planner_Providers)
{
  auto const & elroq = Elroq();
  auto const route = MakeRoute(elroq, 400.0, kSpeedKmh);
  // The Ionity station has the shorter detour.
  std::vector<ChargingStation> stations = {MakeStation(1, 200.0, "type2_combo|4|300", "IONITY GmbH", 60.0),
                                           MakeStation(2, 201.0, "type2_combo|4|300", "EnBW mobility+", 180.0)};
  PlanOptions options;
  options.m_startSocPct = 80.0;

  auto result = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(GetNames(result.m_plan), (std::vector<std::string>{"Station 1"}), ());
  TEST_EQUAL(result.m_providers.size(), 2, ());
  TEST(!result.m_plan.m_stops[0].m_isPreferredProvider, ());

  // Preferred: worth a longer detour.
  options.m_preferredProviders = {"enbw"};
  result = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(GetNames(result.m_plan), (std::vector<std::string>{"Station 2"}), ());
  TEST(result.m_plan.m_stops[0].m_isPreferredProvider, ());

  // Excluded.
  options.m_preferredProviders.clear();
  options.m_excludedProviders = {"ionity"};
  result = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(GetNames(result.m_plan), (std::vector<std::string>{"Station 2"}), ());
  TEST_EQUAL(result.m_usableCount, 1, ());
  // The providers of all stations, also of the excluded ones, to offer them in the filter.
  TEST_EQUAL(result.m_providers.size(), 2, ());

  // Another station than the planned one ("choose another stop").
  options.m_excludedProviders.clear();
  options.m_excludedStations = {stations[0].GetKey()};
  result = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(GetNames(result.m_plan), (std::vector<std::string>{"Station 2"}), ());
}

UNIT_TEST(Planner_BonusesDoNotSplitStops)
{
  auto const & elroq = Elroq();
  auto const route = MakeRoute(elroq, 412.0, kSpeedKmh);
  // Two stations of a preferred provider with amenities at the same services, and some others.
  std::vector<ChargingStation> stations = {
      MakeStation(1, 300.0, "type2_combo|1|350", "Ionity"), MakeStation(2, 300.01, "type2_combo|1|350", "Ionity"),
      MakeStation(3, 150.0, "type2_combo|4|300", "EnBW"), MakeStation(4, 200.0, "type2_combo|4|300", "EnBW")};
  for (auto & s : stations)
    s.m_amenities = {MakeAmenity(AmenityType::Toilets, 30.0), MakeAmenity(AmenityType::Playground, 50.0)};

  PlanOptions options;
  options.m_startSocPct = 82.0;
  options.m_preferredProviders = {"ionity", "enbw"};
  options.m_preferredAmenities = {AmenityType::Toilets, AmenityType::Playground};
  for (auto const priority : {Priority::Fastest, Priority::FewestStops, Priority::LongBreaks})
  {
    options.m_priority = priority;
    auto const result = PlanCharging(elroq, route, stations, options);
    TEST_EQUAL(result.m_status, PlanStatus::Ok, ());
    // One stop is enough; bonuses make no second one at the same place or a needless one.
    TEST_EQUAL(result.m_plan.m_stops.size(), 1, (priority, GetNames(result.m_plan)));
  }
}

UNIT_TEST(Planner_UnsuitableStations)
{
  auto const & elroq = Elroq();
  auto const route = MakeRoute(elroq, 400.0, kSpeedKmh);
  // The best positions have CHAdeMO only or a slow AC charger.
  std::vector<ChargingStation> stations = {MakeStation(1, 200.0, "chademo|2|50"), MakeStation(2, 201.0, "type2|8|22"),
                                           MakeStation(3, 240.0, "type2_combo|2|150"),
                                           MakeStation(4, 150.0, "type2_combo|2|50")};
  PlanOptions options;
  options.m_startSocPct = 80.0;
  options.m_minPowerKw = 100.0;
  auto const result = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(result.m_status, PlanStatus::Ok, ());
  TEST_EQUAL(result.m_usableCount, 1, ());
  TEST_EQUAL(GetNames(result.m_plan), (std::vector<std::string>{"Station 3"}), ());
}

UNIT_TEST(Planner_Amenities)
{
  auto const & elroq = Elroq();
  auto const route = MakeRoute(elroq, 400.0, kSpeedKmh);
  std::vector<ChargingStation> stations = {MakeStation(1, 200.0, "type2_combo|4|150", "", 60.0),
                                           MakeStation(2, 201.0, "type2_combo|4|150", "", 150.0)};
  stations[0].m_amenities = {MakeAmenity(AmenityType::Food, 50.0)};
  stations[1].m_amenities = {MakeAmenity(AmenityType::Playground, 80.0), MakeAmenity(AmenityType::Toilets, 60.0)};

  PlanOptions options;
  options.m_startSocPct = 80.0;
  auto result = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(GetNames(result.m_plan), (std::vector<std::string>{"Station 1"}), ());

  options.m_preferredAmenities = {AmenityType::Playground, AmenityType::Toilets};
  result = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(GetNames(result.m_plan), (std::vector<std::string>{"Station 2"}), ());
  TEST_EQUAL(result.m_plan.m_stops[0].m_matchedAmenities,
             (std::vector<AmenityType>{AmenityType::Playground, AmenityType::Toilets}), ());

  // A playground too far away gives no bonus.
  stations[1].m_amenities = {MakeAmenity(AmenityType::Playground, 450.0)};
  result = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(GetNames(result.m_plan), (std::vector<std::string>{"Station 1"}), ());
}

UNIT_TEST(Planner_Occupied)
{
  auto const & elroq = Elroq();
  auto const route = MakeRoute(elroq, 400.0, kSpeedKmh);
  std::vector<ChargingStation> stations = {MakeStation(1, 200.0, "type2_combo|4|150", "", 60.0),
                                           MakeStation(2, 210.0, "type2_combo|4|150", "", 60.0)};
  PlanOptions options;
  options.m_startSocPct = 80.0;
  auto const before = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(before.m_plan.m_stops.size(), 1, ());
  // The planned station is occupied: the other one.
  for (auto & s : stations)
    if (s.m_name == before.m_plan.m_stops[0].m_station.m_name)
      s.m_status.m_availability = Availability::Occupied;
  auto const after = PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(after.m_plan.m_stops.size(), 1, ());
  TEST_NOT_EQUAL(after.m_plan.m_stops[0].m_station.m_name, before.m_plan.m_stops[0].m_station.m_name, ());
}

UNIT_TEST(Planner_Unreachable)
{
  auto const & elroq = Elroq();
  auto const route = MakeRoute(elroq, 500.0, kSpeedKmh);
  PlanOptions options;
  options.m_startSocPct = 80.0;

  auto result = PlanCharging(elroq, route, {}, options);
  TEST_EQUAL(result.m_status, PlanStatus::Unreachable, ());
  TEST_EQUAL(result.m_reachableUntilM, 0.0, ());
  TEST_EQUAL(result.m_gapEndM, 500000.0, ());

  // A station at km 100, then nothing within the range of a full battery (420 km).
  auto const longRoute = MakeRoute(elroq, 600.0, kSpeedKmh);
  std::vector<ChargingStation> stations = {MakeStation(1, 100.0, "type2_combo|2|150"),
                                           MakeStation(2, 560.0, "type2_combo|2|150")};
  result = PlanCharging(elroq, longRoute, stations, options);
  TEST_EQUAL(result.m_status, PlanStatus::Unreachable, ());
  TEST_EQUAL(result.m_reachableUntilM, 100000.0, ());
  TEST_EQUAL(result.m_gapEndM, 560000.0, ());

  // Invalid inputs.
  VehicleProfile broken = elroq;
  broken.m_usableCapacityKwh = 0;
  TEST_EQUAL(PlanCharging(broken, route, stations, options).m_status, PlanStatus::InvalidProfile, ());
  TEST_EQUAL(PlanCharging(elroq, RouteEnergy(), stations, options).m_status, PlanStatus::NoRoute, ());
}

UNIT_TEST(Planner_ReevaluateWithRealRoute)
{
  auto const & elroq = Elroq();
  auto const planned = MakeRoute(elroq, 412.0, kSpeedKmh);
  PlanOptions options;
  options.m_startSocPct = 82.0;

  // A stop at km 150 that charges just enough for the planned route.
  auto station = MakeStation(1, 150.0, "type2_combo|4|150", "", 0.0);
  double const departure = options.m_minArrivalSocPct + planned.GetEnergyKwh(150000.0, 412000.0) / 77.0 * 100.0;
  std::vector<StopTarget> stops = {{station, departure}};
  auto const onPlanned = EvaluateStops(elroq, planned, stops, options);
  TEST(onPlanned.m_meetsMinimums, ());
  TEST(AlmostEqualAbs(onPlanned.m_arrivalSocPct, 20.0, 1e-6), ());

  // The real route needs more energy (faster): the arrival falls below the minimum.
  auto const real = MakeRoute(elroq, 412.0, 110.0);
  auto const evaluated = EvaluateStops(elroq, real, stops, options);
  TEST(!evaluated.m_meetsMinimums, (evaluated.m_arrivalSocPct));
  TEST(AlmostEqualAbs(evaluated.m_driveTimeSec, 412.0 / 110.0 * 3600.0, 1e-6), ());

  // Charging more at the stop fixes it.
  auto const adjusted = EvaluateStopsAdjusting(elroq, real, stops, options);
  TEST(adjusted.m_meetsMinimums, (adjusted.m_arrivalSocPct));
  TEST_GREATER(adjusted.m_stops[0].m_departureSocPct, departure, ());
  TEST_GREATER(adjusted.m_stops[0].m_chargingTimeSec, evaluated.m_stops[0].m_chargingTimeSec, ());

  // "Charge longer" for the evaluated plan too.
  auto withLonger = adjusted;
  AddLongerVariants(elroq, real, options, withLonger);
  TEST(withLonger.m_stops[0].m_longer, ());
  TEST_GREATER(withLonger.m_stops[0].m_longer->m_arrivalSocPct, adjusted.m_arrivalSocPct, ());

  // Not reachable even with the stop: the leg before it is too long, a new plan is needed.
  auto const far = MakeRoute(elroq, 412.0, 160.0);
  station.m_distanceAlongRouteM = 300000.0;
  auto const tooFar = EvaluateStopsAdjusting(elroq, far, {{station, 90.0}}, options);
  TEST(!tooFar.m_meetsMinimums, ());
  TEST_LESS(tooFar.m_stops[0].m_arrivalSocPct, options.m_minStopSocPct, ());
}

// "Charge longer" goes on to the following stops and the destination.
UNIT_TEST(Planner_ChargeLongerPropagates)
{
  auto const & elroq = Elroq();
  auto const route = MakeRoute(elroq, 750.0, kSpeedKmh);
  auto const a = MakeStation(1, 250.0, "type2_combo|4|300", "", 0.0);
  auto const b = MakeStation(2, 500.0, "type2_combo|4|300", "", 0.0);
  PlanOptions options;
  options.m_startSocPct = 80.0;
  // 250 km need 53.5 %: A 26.5 -> 70 %, B 16.5 -> 74 %, arrival with 20.5 %.
  Plan plan = EvaluateStops(elroq, route, {{a, 70.0}, {b, 74.0}}, options);
  TEST(plan.m_meetsMinimums, (plan.m_arrivalSocPct));
  AddLongerVariants(elroq, route, options, plan);

  // Longer at A: B arrives with 10 % more and charges 10 % less (shorter), the arrival stays.
  auto const & atA = *plan.m_stops[0].m_longer;
  TEST(AlmostEqualAbs(atA.m_departureSocPct, 80.0, 1e-6), ());
  TEST(AlmostEqualAbs(atA.m_arrivalSocPct, plan.m_arrivalSocPct, 1e-6), ());
  TEST_GREATER(atA.m_extraStopTimeSec, 0.0, ());
  TEST_GREATER(atA.m_laterStopsSavedSec, 0.0, ());
  TEST(AlmostEqualAbs(atA.m_extraTotalTimeSec, atA.m_extraStopTimeSec - atA.m_laterStopsSavedSec, 1e-6), ());
  auto const longerAtA = EvaluateStops(elroq, route, {{a, atA.m_departureSocPct}, {b, 74.0}}, options);
  TEST(AlmostEqualAbs(longerAtA.m_stops[1].m_arrivalSocPct, plan.m_stops[1].m_arrivalSocPct + 10.0, 1e-6), ());
  TEST(AlmostEqualAbs(plan.m_stops[1].m_chargingTimeSec - longerAtA.m_stops[1].m_chargingTimeSec,
                      atA.m_laterStopsSavedSec, 1e-6),
       ());

  // Longer at B (the last stop): the arrival is 10 % higher.
  auto const & atB = *plan.m_stops[1].m_longer;
  TEST(AlmostEqualAbs(atB.m_arrivalSocPct, plan.m_arrivalSocPct + 10.0, 1e-6), ());
  TEST_EQUAL(atB.m_laterStopsSavedSec, 0.0, ());
  TEST(AlmostEqualAbs(atB.m_extraTotalTimeSec, atB.m_extraStopTimeSec, 1e-6), ());

  // More extra than the next stop needs: D only tops up 5.4 %, so it charges nothing then and the rest of the extra
  // reaches the destination.
  auto const shorter = MakeRoute(elroq, 600.0, kSpeedKmh);
  auto const c = MakeStation(3, 250.0, "type2_combo|4|300", "", 0.0);
  auto const d = MakeStation(4, 350.0, "type2_combo|4|300", "", 0.0);
  Plan topUp = EvaluateStops(elroq, shorter, {{c, 90.0}, {d, 74.0}}, options);
  TEST(topUp.m_meetsMinimums, (topUp.m_arrivalSocPct));
  TEST_GREATER(topUp.m_stops[1].m_chargingTimeSec, 0.0, ());
  AddLongerVariants(elroq, shorter, options, topUp);
  auto const & atC = *topUp.m_stops[0].m_longer;
  TEST(AlmostEqualAbs(atC.m_laterStopsSavedSec, topUp.m_stops[1].m_chargingTimeSec, 1e-6), ());
  TEST_GREATER(atC.m_arrivalSocPct, topUp.m_arrivalSocPct + 4.0, ());
  TEST_LESS(atC.m_arrivalSocPct, topUp.m_arrivalSocPct + 10.0, ());
}
}  // namespace planner_tests
