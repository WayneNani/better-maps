// better-maps: plans the charging stops of an electric vehicle on a real route and measures the time it takes, e.g.:
//
//   ev_planning_tool --data_path=data --mwm_path=maps --from=50.7374,7.0982 --to=49.4521,11.0767
//     --vehicle=skoda_elroq_85 --start_soc=82
//
// |maps| contains the country maps in a version directory (maps/261018/*.mwm), like on a device.
//
// The steps are those of the app: the route (built here, the app has it already), its altitudes, the search for
// charging stations along it, their amenities and the planner. Times are reported for each step.
//
// --detour_report compares the estimated detours of all stations next to a motorway with the router, e.g. to
// calibrate RouterDetours::EstimateDetourSec().

#include "ev_planning/candidates.hpp"
#include "ev_planning/detours.hpp"
#include "ev_planning/energy_model.hpp"
#include "ev_planning/plan_json.hpp"
#include "ev_planning/planner.hpp"
#include "ev_planning/route_points.hpp"
#include "ev_planning/services.hpp"
#include "ev_planning/vehicle_profile.hpp"

#include "search/search_quality/helpers.hpp"

#include "search/search_tests_support/test_search_engine.hpp"
#include "search/search_tests_support/test_search_request.hpp"

#include "routing/index_router.hpp"
#include "routing/router_delegate.hpp"
#include "routing/routes_builder/routes_builder.hpp"
#include "routing/vehicle_mask.hpp"

#include "routing_common/num_mwm_id.hpp"

#include "storage/country_info_getter.hpp"
#include "storage/country_parent_getter.hpp"
#include "storage/routing_helpers.hpp"
#include "storage/storage.hpp"

#include "traffic/traffic_cache.hpp"

#include "indexer/classificator_loader.hpp"
#include "indexer/data_source.hpp"
#include "indexer/ftypes_matcher.hpp"

#include "platform/helpers.hpp"
#include "platform/local_country_file_utils.hpp"
#include "platform/platform.hpp"

#include "geometry/latlon.hpp"
#include "geometry/mercator.hpp"

#include "base/file_name_utils.hpp"
#include "base/logging.hpp"
#include "base/string_utils.hpp"
#include "base/timer.hpp"

#include "defines.hpp"

#include <cmath>
#include <cstdio>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <map>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include <gflags/gflags.h>

DEFINE_string(data_path, "", "Path to the data directory (resources)");
DEFINE_string(mwm_path, "", "Path to a directory with the mwm files in a version directory, e.g. 261018/");
DEFINE_string(route_mwm_path, "",
              "Maps for the route if they differ from --mwm_path. The router of a debug build needs maps without "
              "World.mwm, the search needs World.mwm");
DEFINE_string(from, "50.7374,7.0982", "Route start as \"lat,lon\" (default: Bonn)");
DEFINE_string(to, "49.4521,11.0767", "Route finish as \"lat,lon\" (default: Nuremberg)");
DEFINE_string(vehicle, "skoda_elroq_85", "Vehicle template id");
DEFINE_string(profile, "", "Vehicle profile as a JSON file, instead of --vehicle");
DEFINE_double(start_soc, 82, "State of charge at the start, %");
DEFINE_double(min_arrival_soc, 20, "Minimum state of charge at the destination, %");
DEFINE_double(min_stop_soc, 10, "Minimum state of charge at the arrival at a charging stop, %");
DEFINE_string(priority, "fastest", "fastest, fewest_stops or long_breaks");
DEFINE_double(min_power_kw, 50, "Minimum power of a charging station, kW");
DEFINE_string(preferred, "", "Preferred providers, e.g. \"enbw,ionity\"");
DEFINE_string(excluded, "", "Excluded providers, e.g. \"tesla\"");
DEFINE_string(amenities, "", "Preferred amenities, e.g. \"toilets,playground\"");
DEFINE_int32(repeat, 3, "Number of times the planning (without the route) runs; the minimum and median are reported");
DEFINE_string(json_out, "", "Write the result as JSON (like the app gets it) to this file");
DEFINE_bool(detour_report, false,
            "Compare the estimated detours of the stations next to a motorway with the router (calibration)");
DEFINE_bool(services, true,
            "Tell motorway services of the other carriageway from other stations on the other side of a motorway "
            "(false: one estimate for both, as before)");
DEFINE_bool(real_detours, true,
            "Real detours of the stations of the plans with the router, as the app does (false: only the estimates "
            "from the distance to the route)");

namespace
{
using namespace std;

ms::LatLon ParseLatLon(string const & s)
{
  auto const parts = strings::Tokenize(s, ",");
  CHECK_EQUAL(parts.size(), 2, (s));
  double lat, lon;
  CHECK(strings::to_double(parts[0], lat) && strings::to_double(parts[1], lon), (s));
  return {lat, lon};
}

vector<string> ParseList(string const & s)
{
  vector<string> result;
  for (auto const & part : strings::Tokenize(s, ","))
    result.emplace_back(part);
  return result;
}

// Registers the maps of the version directories in --mwm_path, like the routing does (e.g. "261018/*.mwm").
void RegisterAllMaps(FrozenDataSource & dataSource)
{
  Platform::TFilesWithType dirs;
  Platform::GetFilesByType(GetPlatform().WritableDir(), Platform::EFileType::Directory, dirs);
  for (auto const & [dir, type] : dirs)
  {
    int64_t version;
    if (!strings::to_int64(dir, version))
      continue;
    auto const path = base::JoinPath(GetPlatform().WritableDir(), dir);
    vector<string> files;
    Platform::GetFilesByExt(path, DATA_FILE_EXTENSION, files);
    for (auto const & file : files)
    {
      platform::LocalCountryFile mwm(path, platform::CountryFile(base::FilenameWithoutExt(file)), version);
      mwm.SyncWithDisk();
      dataSource.RegisterMap(mwm);
    }
  }
}

string FormatDuration(double sec)
{
  int const minutes = static_cast<int>(sec / 60.0 + 0.5);
  ostringstream out;
  out << minutes / 60 << ":" << setw(2) << setfill('0') << minutes % 60 << " h";
  return out.str();
}

void PrintPlan(ev::Plan const & plan, string const & title)
{
  cout << title << ": " << FormatDuration(plan.m_totalTimeSec) << " (" << FormatDuration(plan.m_driveTimeSec)
       << " driving, " << static_cast<int>(plan.m_stopTimeSec / 60.0 + 0.5) << " min at " << plan.m_stops.size()
       << " stops), " << static_cast<int>(plan.m_distanceM / 1000.0) << " km, arrival with " << fixed << setprecision(0)
       << plan.m_arrivalSocPct << " %" << (plan.m_meetsMinimums ? "" : " (below the minimum!)") << endl;
  for (auto const & stop : plan.m_stops)
  {
    auto const & s = stop.m_station;
    cout << "  km " << setprecision(0) << s.m_distanceAlongRouteM / 1000.0 << ": " << s.GetTitle() << " ("
         << s.GetProviderName() << "), " << stop.m_power.m_powerKw << " kW " << (stop.m_power.m_isDc ? "DC" : "AC")
         << (stop.m_power.m_isPowerKnown ? "" : " (assumed)") << ", " << s.m_distanceFromRouteM << " m off the route"
         << endl
         << "    detour " << s.m_detourSec / 60.0 << " min"
         << (s.m_detourChecked ? ", " + to_string(static_cast<int>(s.m_detourDistanceM)) + " m (router)"
                               : " (estimate)")
         << endl
         << "    arrive " << stop.m_arrivalSocPct << " % -> " << stop.m_departureSocPct << " %, charging "
         << stop.m_chargingTimeSec / 60.0 << " min (avg " << stop.m_averagePowerKw << " kW)";
    if (stop.m_longer)
    {
      cout << ", longer to " << stop.m_longer->m_departureSocPct << " %: +" << stop.m_longer->m_extraStopTimeSec / 60.0
           << " min";
      for (auto const index : stop.m_longer->m_droppedStops)
        cout << ", stop at " << plan.m_stops[index].m_station.GetTitle() << " not needed";
      cout << ", arrival with " << stop.m_longer->m_arrivalSocPct << " %";
    }
    cout << endl;
    if (!s.m_amenities.empty())
    {
      cout << "    nearby:";
      for (auto const & a : s.m_amenities)
      {
        cout << " " << a.m_kind << (a.m_name.empty() ? "" : " \"" + a.m_name + "\"") << " " << a.m_distanceM << " m"
             << (a.m_openAtArrival ? (*a.m_openAtArrival ? " (open)" : " (closed)") : "") << ";";
      }
      cout << endl;
    }
  }
}

double Median(vector<double> v)
{
  if (v.empty())
    return 0.0;
  sort(v.begin(), v.end());
  return v.size() % 2 ? v[v.size() / 2] : (v[v.size() / 2 - 1] + v[v.size() / 2]) / 2.0;
}

// The estimated detours of the stations next to a motorway (before: one estimate for the other side, after: motorway
// services of the other carriageway and other stations told apart) and the real ones from the router.
void PrintDetourReport(DataSource const & dataSource, routing::routes_builder::RoutesBuilder::Route const & route,
                       vector<ev::ChargingStation> const & stations, routing::IndexRouter && router)
{
  routing::RouterDelegate delegate;
  auto const line =
      ev::RouteLine::FromSegments(route.m_segments, 0.0 /* passedM */, route.m_followedPolyline.GetPolyline().Front());
  ev::ServicesFinder servicesFinder(dataSource);
  ev::RouterDetours before(router, delegate, line);
  ev::RouterDetours after(router, delegate, line, &servicesFinder);
  // Errors of the estimates (estimate - router) in minutes by group.
  map<string, pair<vector<double>, vector<double>>> errors;
  cout << endl
       << "Detours of the stations next to a motorway, min (km, side R/L, off the route, kind, search, estimate "
          "before, after, router):"
       << endl;
  for (auto const & s : stations)
  {
    double const position = clamp(s.m_distanceAlongRouteM, 0.0, line.GetLengthM());
    if (line.GetHighwayClassAt(position) != ftypes::HighwayClass::Motorway)
      continue;
    bool const left = m2::CrossProduct(line.GetDirectionAt(position), s.m_point - line.GetPointAt(position)) > 0.0;
    bool const atServices = servicesFinder.IsAtMotorwayServices(s);
    string const kind = left && s.m_distanceFromRouteM > ev::RouterDetours::kOnRouteM
                            ? (atServices ? "L services" : "L other")
                            : (atServices ? "R services" : "R other");
    auto const estimateBefore = before.EstimateDetourSec(s);
    auto const estimateAfter = after.EstimateDetourSec(s);
    auto const real = after.GetDetour(s);
    cout << fixed << setprecision(1) << "km " << setw(5) << s.m_distanceAlongRouteM / 1000.0 << (left ? " L " : " R ")
         << setw(4) << static_cast<int>(s.m_distanceFromRouteM) << " m " << setw(10) << kind << " | " << setw(4)
         << s.m_detourSec / 60.0 << " | " << setw(4) << *estimateBefore / 60.0 << " | " << setw(4)
         << *estimateAfter / 60.0 << " | " << setw(4) << (real ? real->m_timeSec / 60.0 : -1.0) << " | "
         << s.GetTitle() << " |";
    for (auto const & place : servicesFinder.Find(s.m_point))
      cout << " [" << (place.m_isArea ? "area " : "point ") << static_cast<int>(place.m_distanceM) << " m \""
           << place.m_name << "\"]";
    cout << endl;
    if (real)
    {
      errors[kind].first.push_back((*estimateBefore - real->m_timeSec) / 60.0);
      errors[kind].second.push_back((*estimateAfter - real->m_timeSec) / 60.0);
    }
  }
  cout << "Median error of the estimates (estimate - router), min:" << endl;
  for (auto const & [kind, e] : errors)
  {
    vector<double> absBefore, absAfter;
    for (double const x : e.first)
      absBefore.push_back(fabs(x));
    for (double const x : e.second)
      absAfter.push_back(fabs(x));
    cout << "  " << kind << " (" << e.first.size() << "): before " << Median(e.first) << " (abs " << Median(absBefore)
         << "), after " << Median(e.second) << " (abs " << Median(absAfter) << ")" << endl;
  }
}
}  // namespace

int main(int argc, char * argv[])
{
  platform::ChangeMaxNumberOfOpenFiles(search::search_quality::kMaxOpenFiles);
  gflags::SetUsageMessage("Plans charging stops along a route.");
  gflags::ParseCommandLineFlags(&argc, &argv, true);
  base::ScopedLogLevelChanger const logLevel(base::LWARNING);

  search::search_quality::SetPlatformDirs(FLAGS_data_path,
                                          FLAGS_route_mwm_path.empty() ? FLAGS_mwm_path : FLAGS_route_mwm_path);
  classificator::Load();

  ev::VehicleProfile profile;
  if (!FLAGS_profile.empty())
  {
    ifstream in(FLAGS_profile);
    stringstream buffer;
    buffer << in.rdbuf();
    auto const read = ev::VehicleProfileFromJson(buffer.str());
    CHECK(read, ("Can't read", FLAGS_profile));
    profile = *read;
  }
  else
  {
    auto const * found = ev::FindVehicleTemplate(FLAGS_vehicle);
    CHECK(found, ("Unknown vehicle", FLAGS_vehicle));
    profile = *found;
  }
  cout << DebugPrint(profile) << endl;

  // The route, with its segments.
  using routing::routes_builder::RoutesBuilder;
  base::Timer timer;
  RoutesBuilder builder(1 /* threadsNumber */);
  auto const routeResult = builder.ProcessTask(
      RoutesBuilder::Params(routing::VehicleType::Car, ParseLatLon(FLAGS_from), ParseLatLon(FLAGS_to)));
  CHECK(routeResult.IsCodeOK() && !routeResult.GetRoutes().empty(), ("No route:", routeResult.m_code));
  auto const & route = routeResult.GetRoutes().front();
  cout << "Route built in " << timer.ElapsedSeconds() << " s: " << route.m_distance / 1000.0 << " km, "
       << FormatDuration(route.GetETA()) << ", " << route.m_segments.size() << " segments" << endl;

  if (!FLAGS_route_mwm_path.empty())
    search::search_quality::SetPlatformDirs(FLAGS_data_path, FLAGS_mwm_path);
  FrozenDataSource dataSource;
  RegisterAllMaps(dataSource);
  auto engine = search::search_quality::InitSearchEngine(dataSource, "en", 1 /* numThreads */);
  engine->InitAffiliations();
  auto const numMwmIds = builder.GetNumMwmIds();

  ev::PlanOptions options;
  options.m_startSocPct = FLAGS_start_soc;
  options.m_minArrivalSocPct = FLAGS_min_arrival_soc;
  options.m_minStopSocPct = FLAGS_min_stop_soc;
  options.m_priority = ev::PriorityFromString(FLAGS_priority).value_or(ev::Priority::Fastest);
  options.m_minPowerKw = FLAGS_min_power_kw;
  options.m_preferredProviders = ParseList(FLAGS_preferred);
  options.m_excludedProviders = ParseList(FLAGS_excluded);
  for (auto const & a : ParseList(FLAGS_amenities))
    if (auto const type = ev::AmenityTypeFromString(a))
      options.m_preferredAmenities.push_back(*type);

  // The router for the real detours, on the maps of the search (the app has one router for all maps too).
  auto const infoGetter = storage::CountryInfoReader::CreateCountryInfoGetter(GetPlatform());
  storage::CountryParentGetter const parentGetter;
  auto detourMwmIds = make_shared<routing::NumMwmIds>();
  {
    vector<shared_ptr<MwmInfo>> infos;
    dataSource.GetMwmsInfo(infos);
    for (auto const & info : infos)
    {
      auto const & file = info->GetLocalFile().GetCountryFile();
      if (file.GetName() != WORLD_FILE_NAME && file.GetName() != WORLD_COASTS_FILE_NAME &&
          parentGetter.GetStorageForTesting().IsLeaf(file.GetName()))
        detourMwmIds->RegisterFile(file);
    }
  }
  traffic::TrafficCache const trafficCache;

  vector<double> altitudesMs, searchMs, candidatesMs, plannerMs, totalMs, detourMs;
  vector<size_t> detourRoutes;
  ev::PlanResult result;
  vector<ev::ChargingStation> stations;
  for (int run = 0; run < max(FLAGS_repeat, 1); ++run)
  {
    base::Timer total;

    // Altitudes of the route features (the car router does not load them).
    base::Timer step;
    ev::SegmentAltitudes altitudes(
        dataSource, [&](routing::NumMwmId id) { return dataSource.GetMwmIdByCountryFile(numMwmIds->GetFile(id)); });
    auto const points = ev::MakeRoutePoints(
        route.m_segments, [&](routing::RouteSegment const & s) { return altitudes.GetEndAltitudeM(s.GetSegment()); });
    auto const routeEnergy = ev::ComputeRouteEnergy(ev::EnergyModel(profile), points);
    altitudesMs.push_back(step.ElapsedMilliseconds());

    // Charging stations along the route.
    step.Reset();
    search::RouteCorridor::Params corridorParams;
    corridorParams.m_points = route.m_followedPolyline.GetPolyline().GetPoints();
    if (route.m_segments.size() + 1 == corridorParams.m_points.size())
    {
      corridorParams.m_timesSec.push_back(0.0);
      for (auto const & s : route.m_segments)
        corridorParams.m_timesSec.push_back(s.GetTimeFromBeginningSec());
    }
    corridorParams.m_halfWidthM = ev::kStationCorridorHalfWidthM;
    auto const corridor = make_shared<search::RouteCorridor const>(corridorParams);
    auto params = ev::MakeStationSearchParams(corridor, ev::MakeChargingStationFilter(profile, options.m_minPowerKw));
    search::tests_support::TestSearchRequest request(*engine, params);
    request.Run();
    searchMs.push_back(step.ElapsedMilliseconds());

    step.Reset();
    stations = ev::MakeCandidates(dataSource, request.Results());
    candidatesMs.push_back(step.ElapsedMilliseconds());

    // A new finder and router in each run: the app keeps them only for one planning.
    step.Reset();
    ev::AmenityFinder finder(dataSource);
    ev::ServicesFinder services(dataSource);
    routing::IndexRouter router(routing::VehicleType::Car, false /* loadAltitudes */, parentGetter,
                                [&](m2::PointD const & p) { return infoGetter->GetRegionCountryId(p); },
                                [&](string const & id) { return infoGetter->GetLimitRectForLeaf(id); }, detourMwmIds,
                                routing::MakeNumMwmTree(*detourMwmIds, *infoGetter), trafficCache, dataSource);
    routing::RouterDelegate delegate;
    ev::RouterDetours detours(router, delegate,
                              ev::RouteLine::FromSegments(route.m_segments, 0.0 /* passedM */,
                                                          route.m_followedPolyline.GetPolyline().Front()),
                              FLAGS_services ? &services : nullptr);
    result = ev::PlanChargingWithAmenities(profile, routeEnergy, stations, options, finder,
                                           FLAGS_real_detours ? &detours : nullptr);
    plannerMs.push_back(step.ElapsedMilliseconds());
    detourMs.push_back(detours.GetRoutingSec() * 1000.0);
    detourRoutes.push_back(detours.GetRoutesCount());
    totalMs.push_back(total.ElapsedMilliseconds());

    if (run == 0)
    {
      double up = 0.0, down = 0.0;
      for (size_t i = 1; i < points.size(); ++i)
      {
        if (!ev::IsKnownAltitude(points[i].m_altitudeM) || !ev::IsKnownAltitude(points[i - 1].m_altitudeM))
          continue;
        double const d = points[i].m_altitudeM - points[i - 1].m_altitudeM;
        (d > 0 ? up : down) += d;
      }
      cout << "Route energy: " << routeEnergy.GetTotalEnergyKwh() << " kWh ("
           << routeEnergy.GetTotalEnergyKwh() / route.m_distance * 1e5 << " kWh/100 km), ascent " << up
           << " m, descent " << -down << " m" << endl;
    }
  }

  auto const stats = [](string const & name, vector<double> v)
  {
    sort(v.begin(), v.end());
    cout << "  " << name << ": min " << v.front() << " ms, median " << v[v.size() / 2] << " ms" << endl;
  };
  cout << endl << "Planning time (" << max(FLAGS_repeat, 1) << " runs, the first without caches):" << endl;
  cout << fixed << setprecision(0);
  stats("altitudes and energy", altitudesMs);
  stats("search for stations", searchMs);
  stats("station data", candidatesMs);
  stats("planner with alternatives and amenities", plannerMs);
  stats("  of it real detours (router)", detourMs);
  cout << "  real detours: " << detourRoutes.front() << " routes" << endl;
  stats("total", totalMs);
  cout << "  first run total: " << totalMs.front() << " ms" << endl;

  cout << endl
       << "Stations along the route: " << result.m_candidatesCount << ", usable: " << result.m_usableCount << endl;
  cout << "Providers:";
  for (size_t i = 0; i < min<size_t>(result.m_providers.size(), 12); ++i)
    cout << " " << result.m_providers[i].m_provider.m_name << " (" << result.m_providers[i].m_stations << ")";
  cout << endl << endl;

  cout << "Status: " << DebugPrint(result.m_status) << endl;
  if (result.m_status == ev::PlanStatus::Unreachable)
  {
    cout << "Reachable until km " << result.m_reachableUntilM / 1000.0 << ", next station at km "
         << result.m_gapEndM / 1000.0 << endl;
  }
  if (result.m_status == ev::PlanStatus::Ok)
  {
    PrintPlan(result.m_plan, "Plan (" + FLAGS_priority + ")");
    for (auto const & alternative : result.m_alternatives)
      PrintPlan(alternative,
                "Alternative (" + alternative.m_alternativeReason + ", " + DebugPrint(alternative.m_priority) + ")");
  }

  if (FLAGS_detour_report)
    PrintDetourReport(dataSource, route, stations,
                      routing::IndexRouter(routing::VehicleType::Car, false /* loadAltitudes */, parentGetter,
                                           [&](m2::PointD const & p) { return infoGetter->GetRegionCountryId(p); },
                                           [&](string const & id) { return infoGetter->GetLimitRectForLeaf(id); },
                                           detourMwmIds, routing::MakeNumMwmTree(*detourMwmIds, *infoGetter),
                                           trafficCache, dataSource));

  if (!FLAGS_json_out.empty())
  {
    ofstream out(FLAGS_json_out);
    out << ev::ToJson(result);
  }
  return 0;
}
