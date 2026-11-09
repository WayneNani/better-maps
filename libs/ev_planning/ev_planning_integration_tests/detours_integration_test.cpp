#include "testing/testing.hpp"

#include "ev_planning/candidates.hpp"
#include "ev_planning/detours.hpp"
#include "ev_planning/energy_model.hpp"
#include "ev_planning/planner.hpp"
#include "ev_planning/route_points.hpp"
#include "ev_planning/vehicle_profile.hpp"

#include "generator/generator_tests_support/test_generator.hpp"
#include "generator/routing_index_generator.hpp"

#include "search/route_corridor.hpp"

#include "routing/checkpoints.hpp"
#include "routing/index_router.hpp"
#include "routing/route.hpp"
#include "routing/router_delegate.hpp"
#include "routing/routing_callbacks.hpp"

#include "routing_common/num_mwm_id.hpp"

#include "traffic/traffic_cache.hpp"

#include "indexer/data_source.hpp"
#include "indexer/feature.hpp"

#include "platform/local_country_file.hpp"
#include "platform/platform.hpp"

#include "geometry/mercator.hpp"
#include "geometry/tree4d.hpp"

#include "base/file_name_utils.hpp"

#include "defines.hpp"

#include <cmath>
#include <fstream>
#include <map>
#include <memory>
#include <optional>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

// better-maps: real detours to charging stations with the router, on a generated map with a motorway whose services
// serve one direction each.
namespace detours_integration_test
{
using namespace std;

// Positions in km: x eastwards, y northwards from 50° N, 8° E.
m2::PointD ToLatLon(double xKm, double yKm)
{
  return {50.0 + yKm / 111.2, 8.0 + xKm / 71.7};
}

m2::PointD ToMercator(double xKm, double yKm)
{
  auto const ll = ToLatLon(xKm, yKm);
  return mercator::FromLatLon(ll.x, ll.y);
}

// A tiny OSM file.
class OsmWriter
{
public:
  using Tags = vector<pair<string, string>>;

  uint64_t Node(double xKm, double yKm, Tags const & tags = {})
  {
    auto const ll = ToLatLon(xKm, yKm);
    uint64_t const id = ++m_lastId;
    m_out << "  <node id='" << id << "' lat='" << ll.x << "' lon='" << ll.y << "'>\n";
    WriteTags(tags);
    m_out << "  </node>\n";
    return id;
  }

  void Way(vector<uint64_t> const & nodes, Tags const & tags)
  {
    m_ways << "  <way id='" << ++m_lastId << "'>\n";
    for (auto const id : nodes)
      m_ways << "    <nd ref='" << id << "' />\n";
    for (auto const & [k, v] : tags)
      m_ways << "    <tag k='" << k << "' v='" << v << "' />\n";
    m_ways << "  </way>\n";
  }

  string Get() const
  {
    return "<?xml version='1.0' encoding='UTF-8'?>\n<osm version='0.6' generator='test'>\n" + m_out.str() +
           m_ways.str() + "</osm>\n";
  }

private:
  void WriteTags(Tags const & tags)
  {
    for (auto const & [k, v] : tags)
      m_out << "    <tag k='" << k << "' v='" << v << "' />\n";
  }

  uint64_t m_lastId = 0;
  ostringstream m_out;
  ostringstream m_ways;
};

// A motorway of 40 km eastwards with two carriageways 40 m apart, junctions at km 5 and 35 (a primary road crosses on
// a bridge, links to and from both carriageways) and services at km 20 on both sides:
//
//                         Nord (300 kW, 150 m from the route)
//        J1                 _/‾‾‾\_                  J2
//   <====|=================/=======\================|====  westbound
//   ====>|=================\=======/================|====> eastbound (the route, km 1 -> km 39)
//        |                  ‾\___/‾                 |
//                         Süd (150 kW, 220 m from the route)
//
// The search estimates a shorter detour to Nord, but only the westbound carriageway leads there: on to J2, back, and
// after the stop again on to J1 and back, almost 60 km more.
string MakeOsm()
{
  OsmWriter osm;
  OsmWriter::Tags const motorway = {{"highway", "motorway"}, {"oneway", "yes"}, {"ref", "A 99"}};
  OsmWriter::Tags const link = {{"highway", "motorway_link"}, {"oneway", "yes"}};
  OsmWriter::Tags const service = {{"highway", "service"}, {"oneway", "yes"}};
  double constexpr kE = -0.02;
  double constexpr kW = 0.02;

  // Carriageways with a node every 100 m (the router takes small networks for dead ends).
  map<int, uint64_t> eastNodes;
  map<int, uint64_t> westNodes;
  for (int i = 0; i <= 400; ++i)
  {
    eastNodes[i] = osm.Node(i / 10.0, kE);
    westNodes[i] = osm.Node(i / 10.0, kW);
  }
  auto const east = [&eastNodes](double x) { return eastNodes.at(static_cast<int>(lround(x * 10.0))); };
  auto const west = [&westNodes](double x) { return westNodes.at(static_cast<int>(lround(x * 10.0))); };
  vector<uint64_t> eastWay;
  for (auto const & [x, id] : eastNodes)
    eastWay.push_back(id);
  vector<uint64_t> westWay;
  for (auto it = westNodes.rbegin(); it != westNodes.rend(); ++it)
    westWay.push_back(it->second);
  osm.Way(eastWay, motorway);
  osm.Way(westWay, motorway);
  // Turning loops at the ends, so that the network has no dead ends.
  osm.Way({east(40.0), osm.Node(40.3, 0.0), west(40.0)}, link);
  osm.Way({west(0.0), osm.Node(-0.3, 0.0), east(0.0)}, link);

  // Junctions.
  for (double const j : {5.0, 35.0})
  {
    auto const south = osm.Node(j, -0.5);
    auto const north = osm.Node(j, 0.5);
    osm.Way({osm.Node(j, -2.0), south}, {{"highway", "primary"}});
    osm.Way({south, north}, {{"highway", "primary"}, {"bridge", "yes"}, {"layer", "1"}});
    osm.Way({north, osm.Node(j, 2.0)}, {{"highway", "primary"}});
    osm.Way({east(j - 0.6), south}, link);
    osm.Way({south, east(j + 0.6)}, link);
    osm.Way({west(j + 0.6), north}, link);
    osm.Way({north, west(j - 0.6)}, link);
  }

  // Services.
  auto const s1 = osm.Node(19.7, -0.15);
  auto const s2 = osm.Node(20.3, -0.15);
  osm.Way({east(19.4), s1}, link);
  osm.Way({s1, osm.Node(20.0, -0.18), s2}, service);
  osm.Way({s2, east(20.6)}, link);
  auto const n1 = osm.Node(20.3, 0.15);
  auto const n2 = osm.Node(19.7, 0.15);
  osm.Way({west(20.6), n1}, link);
  osm.Way({n1, osm.Node(20.0, 0.18), n2}, service);
  osm.Way({n2, west(19.4)}, link);

  osm.Node(20.1, -0.24,
           {{"amenity", "charging_station"},
            {"name", "Rasthof Süd"},
            {"operator", "EnBW"},
            {"socket:type2_combo", "4"},
            {"socket:type2_combo:output", "150"}});
  osm.Node(19.9, 0.13,
           {{"amenity", "charging_station"},
            {"name", "Rasthof Nord"},
            {"operator", "IONITY"},
            {"socket:type2_combo", "6"},
            {"socket:type2_combo:output", "300"}});
  return osm.Get();
}

// RouterDetours without its better estimates: the planner trusts the estimates of the search, as before.
class RouterOnly : public ev::DetourProvider
{
public:
  explicit RouterOnly(ev::RouterDetours & detours) : m_detours(detours) {}
  optional<ev::Detour> GetDetour(ev::ChargingStation const & station) override { return m_detours.GetDetour(station); }

private:
  ev::RouterDetours & m_detours;
};

class DetoursTest : public generator::tests_support::TestRawGenerator
{
public:
  static char constexpr kMwmName[] = "Motorway";

  DetoursTest()
  {
    string const osmPath = base::JoinPath(GetPlatform().TmpDir(), "ev_detours_test.osm");
    {
      ofstream out(osmPath);
      out << MakeOsm();
    }
    BuildFB(osmPath, kMwmName);
    BuildFeatures(kMwmName);
    BuildRouting(kMwmName, "Germany");
    Platform::RemoveFileIfExists(osmPath);
    // The router needs the cross-mwm section, with one country for the whole world it has no transitions.
    auto const & info = GetGenInfo();
    CHECK(generator::tests_support::MakeFakeBordersFile(info.m_tmpDir, kMwmName), ());
    string const mwmPath = GetMwmPath(kMwmName);
    routing_builder::BuildRoutingCrossMwmSection(info.m_tmpDir, mwmPath, kMwmName, info.m_intermediateDir,
                                                 [](string const &) { return string(); },
                                                 mwmPath + OSM2FEATURE_FILE_EXTENSION);

    auto const localFile = platform::LocalCountryFile::MakeTemporary(GetMwmPath(kMwmName));
    auto const result = m_dataSource.RegisterMap(localFile);
    CHECK_EQUAL(result.second, MwmSet::RegResult::Success, ());
    m_mwmId = result.first;

    m_numMwmIds = make_shared<routing::NumMwmIds>();
    m_numMwmIds->RegisterFile(localFile.GetCountryFile());
    auto tree = make_unique<m4::Tree<routing::NumMwmId>>();
    m2::RectD const rect(ToMercator(-10.0, -10.0), ToMercator(50.0, 10.0));
    tree->Add(m_numMwmIds->GetId(localFile.GetCountryFile()), rect);
    m_router =
        make_unique<routing::IndexRouter>(routing::VehicleType::Car, false /* loadAltitudes */, [](string const &)
    { return string(); }, [](m2::PointD const &) { return string(kMwmName); }, [rect](string const &) { return rect; },
                                          m_numMwmIds, std::move(tree), m_trafficCache, m_dataSource);
  }

  // The route from km 1 to km 39 on the eastbound carriageway.
  routing::Route BuildRoute()
  {
    routing::Route route("" /* router */, 0 /* routeId */);
    routing::Checkpoints const checkpoints(ToMercator(1.0, -0.02), ToMercator(39.0, -0.02));
    auto const code = m_router->CalculateRoute(checkpoints, ToMercator(2.0, -0.02) - ToMercator(1.0, -0.02),
                                               false /* adjustToPrevRoute */, m_delegate, route);
    TEST_EQUAL(code, routing::RouterResultCode::NoError, ());
    return route;
  }

  // The charging stations of the map with their position along |route|, as the search along the route finds them.
  vector<ev::ChargingStation> FindStations(routing::Route const & route)
  {
    search::RouteCorridor::Params params;
    params.m_points = route.GetFollowedPolyline().GetPolyline().GetPoints();
    params.m_timesSec.push_back(0.0);
    for (auto const & s : route.GetRouteSegments())
      params.m_timesSec.push_back(s.GetTimeFromBeginningSec());
    TEST_EQUAL(params.m_points.size(), params.m_timesSec.size(), ());
    params.m_halfWidthM = ev::kStationCorridorHalfWidthM;
    search::RouteCorridor const corridor(params);

    vector<ev::ChargingStation> stations;
    FeaturesLoaderGuard guard(m_dataSource, m_mwmId);
    for (uint32_t i = 0; i < guard.GetNumFeatures(); ++i)
    {
      auto ft = guard.GetFeatureByIndex(i);
      if (!ft || !ev::IsCarChargingStation(*ft))
        continue;
      auto station = ev::ReadChargingStation(*ft);
      auto const position = corridor.Locate(station.m_point);
      TEST(position, (station.m_name));
      station.m_distanceAlongRouteM = position->m_distanceAlongRouteM;
      station.m_timeAlongRouteSec = position->m_timeAlongRouteSec;
      station.m_distanceFromRouteM = position->m_distanceFromRouteM;
      station.m_detourSec = position->m_detourSec;
      stations.push_back(std::move(station));
    }
    sort(stations.begin(), stations.end(), [](ev::ChargingStation const & a, ev::ChargingStation const & b)
    { return a.m_distanceAlongRouteM < b.m_distanceAlongRouteM; });
    return stations;
  }

  static ev::ChargingStation Get(vector<ev::ChargingStation> const & stations, string const & name)
  {
    for (auto const & s : stations)
      if (s.m_name == name)
        return s;
    CHECK(false, (name));
    UNREACHABLE();
  }

  static vector<string> StopNames(ev::Plan const & plan)
  {
    vector<string> names;
    for (auto const & stop : plan.m_stops)
      names.push_back(stop.m_station.m_name);
    return names;
  }

protected:
  FrozenDataSource m_dataSource;
  MwmSet::MwmId m_mwmId;
  shared_ptr<routing::NumMwmIds> m_numMwmIds;
  traffic::TrafficCache m_trafficCache;
  routing::RouterDelegate m_delegate;
  unique_ptr<routing::IndexRouter> m_router;
};

UNIT_CLASS_TEST(DetoursTest, RealDetoursOfServicesOnBothSides)
{
  auto const route = BuildRoute();
  TEST_ALMOST_EQUAL_ABS(route.GetTotalDistanceMeters(), 38000.0, 500.0, ());

  auto const stations = FindStations(route);
  TEST_EQUAL(stations.size(), 2, ());
  auto const nord = Get(stations, "Rasthof Nord");
  auto const sued = Get(stations, "Rasthof Süd");
  // Both next to the route at km 19 (from the start at km 1), Nord even closer: the search estimates a shorter
  // detour to it.
  TEST_ALMOST_EQUAL_ABS(nord.m_distanceAlongRouteM, 19000.0, 300.0, ());
  TEST_ALMOST_EQUAL_ABS(sued.m_distanceAlongRouteM, 19000.0, 300.0, ());
  TEST_LESS(nord.m_distanceFromRouteM, sued.m_distanceFromRouteM, ());
  TEST_LESS(nord.m_detourSec, sued.m_detourSec, ());

  ev::RouterDetours detours(*m_router, m_delegate,
                            ev::RouteLine::FromSegments(route.GetRouteSegments(), 0.0 /* passedM */,
                                                        route.GetFollowedPolyline().GetPolyline().Front()));

  // The real detour to Nord: via J2 (km 35) and J1 (km 5), almost 60 km and half an hour more.
  auto const toNord = detours.GetDetour(nord);
  TEST(toNord, ());
  TEST_GREATER(toNord->m_distanceM, 50000.0, ());
  TEST_GREATER(toNord->m_timeSec, 20.0 * 60.0, ());
  // To Süd: the services next to the carriageway of the route, little more than the way through them.
  auto const toSued = detours.GetDetour(sued);
  TEST(toSued, ());
  TEST_LESS(toSued->m_distanceM, 1000.0, ());
  TEST_LESS(toSued->m_timeSec, 5.0 * 60.0, ());
  TEST_EQUAL(detours.GetRoutesCount(), 2, ());

  // The better estimate without the router: Nord is on the other side of the motorway.
  auto const nordEstimate = detours.EstimateDetourSec(nord);
  auto const suedEstimate = detours.EstimateDetourSec(sued);
  TEST(nordEstimate && suedEstimate, ());
  TEST_GREATER(*nordEstimate, *suedEstimate + ev::RouterDetours::kOtherSideOfMotorwaySec - 60.0, ());
  TEST_LESS(*suedEstimate, 5.0 * 60.0, ());
}

UNIT_CLASS_TEST(DetoursTest, PlanAvoidsTheServicesOfTheOtherCarriageway)
{
  auto const route = BuildRoute();
  auto const stations = FindStations(route);
  auto const & elroq = *ev::FindVehicleTemplate("skoda_elroq_85");
  auto const energy = ev::ComputeRouteEnergy(ev::EnergyModel(elroq), ev::MakeRoutePoints(route.GetRouteSegments(), {}));

  // 38 km on the motorway need about 9 %: a stop is needed with 25 % at the start.
  ev::PlanOptions options;
  options.m_startSocPct = 25.0;

  // With the estimates of the search: Nord, the services on the other side.
  auto const estimated = ev::PlanCharging(elroq, energy, stations, options);
  TEST_EQUAL(estimated.m_status, ev::PlanStatus::Ok, ());
  TEST_EQUAL(StopNames(estimated.m_plan), (vector<string>{"Rasthof Nord"}), ());

  auto const line = ev::RouteLine::FromSegments(route.GetRouteSegments(), 0.0 /* passedM */,
                                                route.GetFollowedPolyline().GetPolyline().Front());

  // With the real detours of the router only: Nord first, its real detour, then Süd.
  {
    ev::RouterDetours detours(*m_router, m_delegate, line);
    RouterOnly routerOnly(detours);
    auto const result = ev::PlanCharging(elroq, energy, stations, options, &routerOnly);
    TEST_EQUAL(result.m_status, ev::PlanStatus::Ok, ());
    TEST_EQUAL(StopNames(result.m_plan), (vector<string>{"Rasthof Süd"}), ());
    TEST(result.m_plan.m_meetsMinimums, ());
    auto const & stop = result.m_plan.m_stops[0].m_station;
    TEST(stop.m_detourChecked, ());
    TEST_LESS(stop.m_detourSec, 5.0 * 60.0, ());
    TEST_EQUAL(result.m_detourChecks, 2, ());
    // The alternative with Nord shows its real detour.
    TEST_EQUAL(result.m_alternatives.size(), 1, ());
    auto const & alternative = result.m_alternatives[0];
    TEST_EQUAL(StopNames(alternative), (vector<string>{"Rasthof Nord"}), ());
    TEST(alternative.m_stops[0].m_station.m_detourChecked, ());
    TEST_GREATER(alternative.m_stops[0].m_station.m_detourDistanceM, 50000.0, ());
    TEST_GREATER(alternative.m_totalTimeSec, result.m_plan.m_totalTimeSec + 15.0 * 60.0, ());
  }

  // As the app plans: the better estimates avoid Nord right away, only Süd needs the router (once more for the
  // alternative).
  {
    ev::RouterDetours detours(*m_router, m_delegate, line);
    auto const result = ev::PlanCharging(elroq, energy, stations, options, &detours);
    TEST_EQUAL(StopNames(result.m_plan), (vector<string>{"Rasthof Süd"}), ());
    TEST(result.m_plan.m_stops[0].m_station.m_detourChecked, ());
    TEST_LESS_OR_EQUAL(detours.GetRoutesCount(), 2, ());
    for (auto const & alternative : result.m_alternatives)
      for (auto const & stop : alternative.m_stops)
        TEST(stop.m_station.m_detourChecked, (stop.m_station.m_name));
  }
}
}  // namespace detours_integration_test
