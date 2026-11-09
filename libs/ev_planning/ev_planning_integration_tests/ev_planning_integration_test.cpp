#include "testing/testing.hpp"

#include "ev_planning/candidates.hpp"
#include "ev_planning/energy_model.hpp"
#include "ev_planning/plan_json.hpp"
#include "ev_planning/planner.hpp"
#include "ev_planning/vehicle_profile.hpp"

#include "search/result.hpp"
#include "search/route_corridor.hpp"
#include "search/search_params.hpp"
#include "search/search_tests_support/helpers.hpp"
#include "search/search_tests_support/test_search_request.hpp"

#include "generator/generator_tests_support/test_feature.hpp"
#include "generator/generator_tests_support/test_mwm_builder.hpp"

#include "indexer/feature_meta.hpp"

#include "geometry/mercator.hpp"

#include <algorithm>
#include <ctime>
#include <memory>
#include <string>
#include <vector>

// better-maps: charging planner with stations and amenities from test maps.
namespace ev_planning_integration_test
{
using namespace generator::tests_support;
using namespace search::tests_support;
using namespace std;

using feature::Metadata;

// Wednesday, 2026-10-21 at 12:00 local time.
time_t Noon()
{
  tm t = {};
  t.tm_year = 2026 - 1900;
  t.tm_mon = 10 - 1;
  t.tm_mday = 21;
  t.tm_hour = 12;
  t.tm_isdst = -1;
  return mktime(&t);
}

// A route of 333 km eastwards along the equator (0.01 in mercator is about 1.1 km) at 100 km/h. Around km 133:
//
//   playground (78 m from Alpha)       far station (5.5 km off)
//   Alpha (EnBW, 150 kW, 220 m off)  Beta (Ionity, 300 kW, 110 m off) + restaurant + toilets
//   ===o==============================================================================> 3.0
//   CHAdeMO only, AC 22 kW, bicycle station: not for the planner.
class EvPlanningTest : public SearchTest
{
public:
  static double constexpr kLengthX = 3.0;

  EvPlanningTest()
    : m_alpha({1.2, 0.002}, "Alpha Laden", "en")
    , m_beta({1.25, 0.001}, "Beta Laden", "en")
    , m_chademo({1.22, 0.001}, "Chademo Laden", "en")
    , m_ac({1.21, 0.001}, "AC Laden", "en")
    , m_bicycle({1.23, 0.001}, "Bike Laden", "en")
    , m_far({1.24, 0.05}, "Far Laden", "en")
    , m_playground({1.2, 0.0027}, "Spielplatz", "en")
    , m_restaurant({1.2508, 0.001}, "Rasthof Beta", "en")
    , m_toilets({1.2503, 0.0012}, "WC", "en")
  {
    m_alpha.SetTypes({{"amenity", "charging_station"}});
    m_alpha.GetMetadata().Set(Metadata::FMD_CHARGE_SOCKETS, "type2_combo|4|150;type2|2|22");
    m_alpha.GetMetadata().Set(Metadata::FMD_OPERATOR, "EnBW Energie Baden-Württemberg AG");
    m_alpha.GetMetadata().Set(Metadata::FMD_OPEN_HOURS, "24/7");
    m_alpha.GetMetadata().Set(Metadata::FMD_CAPACITY, "8");

    m_beta.SetTypes({{"amenity", "charging_station", "motorcar"}});
    m_beta.GetMetadata().Set(Metadata::FMD_CHARGE_SOCKETS, "type2_combo|6|300");
    m_beta.GetMetadata().Set(Metadata::FMD_OPERATOR, "IONITY GmbH");

    m_chademo.SetTypes({{"amenity", "charging_station"}});
    m_chademo.GetMetadata().Set(Metadata::FMD_CHARGE_SOCKETS, "chademo|2|50");

    m_ac.SetTypes({{"amenity", "charging_station"}});
    m_ac.GetMetadata().Set(Metadata::FMD_CHARGE_SOCKETS, "type2|4|22");

    m_bicycle.SetTypes({{"amenity", "charging_station", "bicycle"}});
    m_bicycle.GetMetadata().Set(Metadata::FMD_CHARGE_SOCKETS, "type2_combo|2|150");

    m_far.SetTypes({{"amenity", "charging_station"}});
    m_far.GetMetadata().Set(Metadata::FMD_CHARGE_SOCKETS, "type2_combo|2|150");

    m_playground.SetTypes({{"leisure", "playground"}});
    m_restaurant.SetTypes({{"amenity", "restaurant"}});
    m_restaurant.GetMetadata().Set(Metadata::FMD_OPEN_HOURS, "Mo-Su 11:00-22:00");
    m_toilets.SetTypes({{"amenity", "toilets"}});

    m_mwmId = BuildCountry("Wonderland", [&](TestMwmBuilder & builder)
    {
      for (auto * f :
           {&m_alpha, &m_beta, &m_chademo, &m_ac, &m_bicycle, &m_far, &m_playground, &m_restaurant, &m_toilets})
      {
        builder.Add(*f);
      }
    });
    SetViewport(m2::RectD(-0.01, -0.01, 0.01, 0.01));
  }

  static vector<m2::PointD> RoutePolyline()
  {
    vector<m2::PointD> points;
    for (int i = 0; i <= 300; ++i)
      points.emplace_back(i * kLengthX / 300.0, 0.0);
    return points;
  }

  // The route with the travel times at 100 km/h, for the energy model and the corridor.
  static vector<ev::RoutePoint> RoutePoints()
  {
    vector<ev::RoutePoint> result;
    double distanceM = 0.0;
    auto const points = RoutePolyline();
    for (size_t i = 0; i < points.size(); ++i)
    {
      if (i > 0)
        distanceM += mercator::DistanceOnEarth(points[i - 1], points[i]);
      ev::RoutePoint p;
      p.m_distanceM = distanceM;
      p.m_timeSec = distanceM / 1000.0 / 100.0 * 3600.0;
      p.m_altitudeM = 50.0;
      p.m_roadKind = ev::RoadKind::Fast;
      result.push_back(p);
    }
    return result;
  }

  static shared_ptr<search::RouteCorridor const> MakeCorridor()
  {
    search::RouteCorridor::Params params;
    params.m_points = RoutePolyline();
    for (auto const & p : RoutePoints())
      params.m_timesSec.push_back(p.m_timeSec);
    params.m_halfWidthM = ev::kStationCorridorHalfWidthM;
    return make_shared<search::RouteCorridor const>(params);
  }

  // Stations along the route as the app finds them: the search along the route, then the amenities.
  vector<ev::ChargingStation> FindStations(ev::VehicleProfile const & profile, double minPowerKw)
  {
    auto params = ev::MakeStationSearchParams(MakeCorridor(), ev::MakeChargingStationFilter(profile, minPowerKw));
    params.m_openingHoursTime = Noon();
    auto const results = MakeRequest(params)->Results();
    auto stations = ev::MakeCandidates(m_dataSource, results);
    ev::AmenityFinder finder(m_dataSource);
    finder.AddAmenities(stations, Noon());
    return stations;
  }

  static ev::ChargingStation const * Find(vector<ev::ChargingStation> const & stations, string const & name)
  {
    for (auto const & s : stations)
      if (s.m_name == name)
        return &s;
    return nullptr;
  }

  static vector<string> StopNames(ev::Plan const & plan)
  {
    vector<string> names;
    for (auto const & stop : plan.m_stops)
      names.push_back(stop.m_station.m_name);
    return names;
  }

protected:
  TestPOI m_alpha;
  TestPOI m_beta;
  TestPOI m_chademo;
  TestPOI m_ac;
  TestPOI m_bicycle;
  TestPOI m_far;
  TestPOI m_playground;
  TestPOI m_restaurant;
  TestPOI m_toilets;
  MwmSet::MwmId m_mwmId;
};

UNIT_CLASS_TEST(EvPlanningTest, CandidateStations)
{
  auto const & elroq = *ev::FindVehicleTemplate("skoda_elroq_85");
  auto const stations = FindStations(elroq, 50.0);

  // Only the stations the car can use with enough power, in the corridor.
  vector<string> names;
  for (auto const & s : stations)
    names.push_back(s.m_name);
  TEST_EQUAL(names, (vector<string>{"Alpha Laden", "Beta Laden"}), ());

  auto const & alpha = *Find(stations, "Alpha Laden");
  TEST_EQUAL(alpha.m_featureId.m_mwmId, m_mwmId, ());
  TEST_EQUAL(alpha.GetProviderName(), "EnBW", ());
  TEST(alpha.HasProvider("enbw"), ());
  TEST_EQUAL(alpha.m_sockets.size(), 2, ());
  TEST_EQUAL(alpha.m_sockets[0].type, "type2_combo", ());
  TEST_EQUAL(alpha.m_sockets[0].power, 150.0, ());
  TEST_EQUAL(alpha.m_capacity, 8, ());
  TEST_EQUAL(alpha.m_openingHours, "24/7", ());
  double const kmPerX = mercator::DistanceOnEarth({0, 0}, {1, 0}) / 1000.0;
  TEST_ALMOST_EQUAL_ABS(alpha.m_distanceAlongRouteM / 1000.0, 1.2 * kmPerX, 0.1, ());
  TEST_ALMOST_EQUAL_ABS(alpha.m_distanceFromRouteM, 0.002 * kmPerX * 1000.0, 5.0, ());
  TEST_GREATER(alpha.m_detourSec, 0.0, ());
  TEST_ALMOST_EQUAL_ABS(alpha.m_timeAlongRouteSec, alpha.m_distanceAlongRouteM / 1000.0 / 100.0 * 3600.0, 5.0, ());
  TEST_EQUAL(alpha.GetKey(), "Wonderland:" + to_string(alpha.m_featureId.m_index), ());

  // Amenities nearby.
  TEST_EQUAL(alpha.m_amenities.size(), 1, (alpha.m_amenities));
  TEST_EQUAL(alpha.m_amenities[0].m_type, ev::AmenityType::Playground, ());
  TEST_EQUAL(alpha.m_amenities[0].m_name, "Spielplatz", ());
  TEST_ALMOST_EQUAL_ABS(alpha.m_amenities[0].m_distanceM, 0.0007 * kmPerX * 1000.0, 2.0, ());

  auto const & beta = *Find(stations, "Beta Laden");
  TEST_EQUAL(beta.GetProviderName(), "Ionity", ());
  vector<ev::AmenityType> types;
  for (auto const & a : beta.m_amenities)
    types.push_back(a.m_type);
  TEST_EQUAL(types, (vector<ev::AmenityType>{ev::AmenityType::Toilets, ev::AmenityType::Food}), (beta.m_amenities));
  // The restaurant opens at 11:00, the arrival is about 13:20.
  TEST(beta.m_amenities[1].m_openAtArrival, ());
  TEST(*beta.m_amenities[1].m_openAtArrival, ());

  // A higher minimum power keeps only the 300 kW station.
  auto const fast = FindStations(elroq, 200.0);
  TEST_EQUAL(fast.size(), 1, ());
  TEST_EQUAL(fast[0].m_name, "Beta Laden", ());
}

UNIT_CLASS_TEST(EvPlanningTest, PlanWithPreferences)
{
  auto const & elroq = *ev::FindVehicleTemplate("skoda_elroq_85");
  auto const stations = FindStations(elroq, 50.0);
  auto const route = ev::ComputeRouteEnergy(ev::EnergyModel(elroq), RoutePoints());

  ev::PlanOptions options;
  options.m_startSocPct = 70.0;
  options.m_departureTime = Noon();

  // Beta: closer to the route.
  auto result = ev::PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(result.m_status, ev::PlanStatus::Ok, ());
  TEST_EQUAL(StopNames(result.m_plan), (vector<string>{"Beta Laden"}), ());
  TEST_EQUAL(result.m_providers.size(), 2, ());
  TEST_GREATER_OR_EQUAL(result.m_plan.m_arrivalSocPct, 19.5, ());
  TEST_EQUAL(result.m_alternatives.size(), 1, ());
  TEST_EQUAL(StopNames(result.m_alternatives[0]), (vector<string>{"Alpha Laden"}), ());

  // With children: the playground at Alpha.
  options.m_preferredAmenities = {ev::AmenityType::Playground};
  result = ev::PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(StopNames(result.m_plan), (vector<string>{"Alpha Laden"}), ());
  TEST_EQUAL(result.m_plan.m_stops[0].m_matchedAmenities, (vector<ev::AmenityType>{ev::AmenityType::Playground}), ());

  // Ionity excluded.
  options.m_preferredAmenities.clear();
  options.m_excludedProviders = {"ionity"};
  result = ev::PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(StopNames(result.m_plan), (vector<string>{"Alpha Laden"}), ());

  // Both excluded: the destination is out of reach.
  options.m_excludedProviders = {"ionity", "enbw"};
  result = ev::PlanCharging(elroq, route, stations, options);
  TEST_EQUAL(result.m_status, ev::PlanStatus::Unreachable, ());

  // Amenities only for the stations of the plans when no amenities are preferred.
  auto withoutAmenities = stations;
  for (auto & s : withoutAmenities)
    s.m_amenities.clear();
  ev::AmenityFinder finder(m_dataSource);
  options.m_excludedProviders = {"ionity"};
  result = ev::PlanChargingWithAmenities(elroq, route, withoutAmenities, options, finder);
  TEST_EQUAL(StopNames(result.m_plan), (vector<string>{"Alpha Laden"}), ());
  TEST_EQUAL(result.m_plan.m_stops[0].m_station.m_amenities.size(), 1, ());
  TEST(withoutAmenities[0].m_amenities.empty(), ());

  // The JSON for the app.
  options.m_excludedProviders.clear();
  auto const json = ev::ToJson(ev::PlanCharging(elroq, route, stations, options));
  TEST(json.find(R"("name":"Beta Laden")") != string::npos, (json));
  TEST(json.find(R"("kind":"restaurant")") != string::npos, (json));
}
}  // namespace ev_planning_integration_test
