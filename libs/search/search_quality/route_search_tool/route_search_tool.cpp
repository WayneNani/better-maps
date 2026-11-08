// better-maps: measures the search along a route (search::Mode::Route) with real maps.
//
// Builds a car route between two points with the routing of the app (or reads one from a file), then searches
// each query along it and prints the response times and the first results, e.g.:
//
//   route_search_tool --data_path=data --mwm_path=maps --from=50.7374,7.0982 --to=49.4521,11.0767 \
//     --route_out=bonn_nuernberg.route --queries="Tankstelle;Restaurant;Toilette" --locale=de
//
// |maps| contains the country maps in a version directory (maps/261018/*.mwm), like on a device. The router of a
// debug build checks that World.mwm is not among them, so build the route with a data path without World.mwm, then
// search with --route_in and World.mwm in the version directory.

#include "search/search_quality/helpers.hpp"

#include "search/search_tests_support/test_search_engine.hpp"
#include "search/search_tests_support/test_search_request.hpp"

#include "search/result.hpp"
#include "search/route_corridor.hpp"
#include "search/search_params.hpp"

#include "routing/routes_builder/routes_builder.hpp"
#include "routing/vehicle_mask.hpp"

#include "indexer/classificator_loader.hpp"
#include "indexer/data_source.hpp"

#include "platform/helpers.hpp"
#include "platform/local_country_file_utils.hpp"
#include "platform/platform.hpp"

#include "geometry/latlon.hpp"
#include "geometry/mercator.hpp"

#include "base/file_name_utils.hpp"
#include "base/logging.hpp"
#include "base/string_utils.hpp"
#include "base/timer.hpp"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <numeric>
#include <sstream>
#include <string>
#include <vector>

#include <gflags/gflags.h>

DEFINE_string(data_path, "", "Path to the data directory (resources)");
DEFINE_string(mwm_path, "", "Path to a directory with the mwm files in a version directory, e.g. 261018/");
DEFINE_string(from, "50.7374,7.0982", "Route start as \"lat,lon\" (default: Bonn)");
DEFINE_string(to, "49.4521,11.0767", "Route finish as \"lat,lon\" (default: Nuremberg)");
DEFINE_string(route_in, "", "Read the route from this file (lines \"lat lon time_sec\") instead of building it");
DEFINE_string(route_out, "", "Write the built route to this file (lines \"lat lon time_sec\")");
DEFINE_string(queries,
              "Tankstelle;Ladestation;Restaurant;Café;Bäckerei;Supermarkt;Toilette;Wertstoffcontainer;Parkplatz",
              "Queries separated by ';'");
DEFINE_bool(categorial, true, "Search the queries as categories (like the category buttons of the app)");
DEFINE_string(locale, "de", "Locale of the queries");
DEFINE_double(half_width_m, 1000, "Half width of the corridor in meters");
DEFINE_double(passed_m, 0, "Distance already travelled along the route in meters");
DEFINE_int32(repeat, 3, "Number of times each query is searched; the minimum and the median time are reported");
DEFINE_int32(top, 5, "Number of results to print for each query");
DEFINE_int32(num_threads, 1, "Number of search engine threads");

namespace
{
using namespace search;
using namespace search::search_quality;
using namespace search::tests_support;
using namespace std;

ms::LatLon ParseLatLon(string const & s)
{
  auto const parts = strings::Tokenize(s, ",");
  CHECK_EQUAL(parts.size(), 2, (s));
  double lat, lon;
  CHECK(strings::to_double(parts[0], lat) && strings::to_double(parts[1], lon), (s));
  return {lat, lon};
}

RouteCorridor::Params ReadRoute(string const & path)
{
  RouteCorridor::Params params;
  ifstream in(path);
  CHECK(in.is_open(), ("Can't open", path));
  double lat, lon, time;
  while (in >> lat >> lon >> time)
  {
    params.m_points.push_back(mercator::FromLatLon(lat, lon));
    params.m_timesSec.push_back(time);
  }
  return params;
}

RouteCorridor::Params BuildRoute(ms::LatLon const & from, ms::LatLon const & to)
{
  using routing::routes_builder::RoutesBuilder;
  base::Timer timer;
  RoutesBuilder builder(1 /* threadsNumber */);
  auto const result = builder.ProcessTask(RoutesBuilder::Params(routing::VehicleType::Car, from, to));
  CHECK(result.IsCodeOK() && !result.GetRoutes().empty(), ("No route:", result.m_code));
  auto const & route = result.GetRoutes().front();
  cout << "Route built in " << timer.ElapsedSeconds() << " s: " << route.m_distance / 1000.0 << " km, "
       << route.GetETA() / 60.0 << " min" << endl;

  // The builder has no times for the points: spread the total time over the distance.
  RouteCorridor::Params params;
  params.m_points = route.m_followedPolyline.GetPolyline().GetPoints();
  double distanceM = 0;
  params.m_timesSec.push_back(0);
  for (size_t i = 1; i < params.m_points.size(); ++i)
  {
    distanceM += mercator::DistanceOnEarth(params.m_points[i - 1], params.m_points[i]);
    params.m_timesSec.push_back(distanceM);
  }
  for (auto & t : params.m_timesSec)
    t *= distanceM > 0 ? route.GetETA() / distanceM : 0;
  return params;
}

void WriteRoute(RouteCorridor::Params const & params, string const & path)
{
  ofstream out(path);
  CHECK(out.is_open(), ("Can't open", path));
  out << setprecision(9);
  for (size_t i = 0; i < params.m_points.size(); ++i)
  {
    auto const ll = mercator::ToLatLon(params.m_points[i]);
    out << ll.m_lat << ' ' << ll.m_lon << ' ' << params.m_timesSec[i] << '\n';
  }
}

// Registers the maps of the version directories in --mwm_path, like the routing does (e.g. "261018/*.mwm").
void RegisterAllMaps(FrozenDataSource & dataSource)
{
  size_t count = 0;
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
      if (dataSource.RegisterMap(mwm).second == MwmSet::RegResult::Success)
        ++count;
    }
  }
  cout << "Maps: " << count << endl;
}
}  // namespace

int main(int argc, char * argv[])
{
  platform::ChangeMaxNumberOfOpenFiles(kMaxOpenFiles);
  gflags::SetUsageMessage("Measures the search along a route.");
  gflags::ParseCommandLineFlags(&argc, &argv, true);
  base::ScopedLogLevelChanger const logLevel(base::LWARNING);

  SetPlatformDirs(FLAGS_data_path, FLAGS_mwm_path);
  classificator::Load();

  auto routeParams =
      FLAGS_route_in.empty() ? BuildRoute(ParseLatLon(FLAGS_from), ParseLatLon(FLAGS_to)) : ReadRoute(FLAGS_route_in);
  if (!FLAGS_route_out.empty())
    WriteRoute(routeParams, FLAGS_route_out);

  FrozenDataSource dataSource;
  RegisterAllMaps(dataSource);
  auto engine = InitSearchEngine(dataSource, FLAGS_locale, FLAGS_num_threads);
  engine->InitAffiliations();

  routeParams.m_halfWidthM = FLAGS_half_width_m;
  routeParams.m_passedDistanceM = FLAGS_passed_m;
  base::Timer corridorTimer;
  auto const corridor = make_shared<RouteCorridor const>(routeParams);
  cout << "Corridor: " << routeParams.m_points.size() << " points, " << corridor->GetLengthM() / 1000.0
       << " km ahead, half width " << FLAGS_half_width_m << " m, " << corridor->GetCoveringRects().size()
       << " rects, built in " << corridorTimer.ElapsedSeconds() * 1000 << " ms" << endl
       << endl;

  cout << fixed << setprecision(3);
  vector<double> medians;
  for (auto const & query : strings::Tokenize(FLAGS_queries, ";"))
  {
    vector<double> times;
    vector<Result> results;
    for (int i = 0; i < max(FLAGS_repeat, 1); ++i)
    {
      SearchParams params;
      params.m_query = string(query) + (FLAGS_categorial ? " " : "");
      params.m_inputLocale = FLAGS_locale;
      params.m_mode = Mode::Route;
      params.m_categorialRequest = FLAGS_categorial;
      params.m_routeCorridor = corridor;
      params.m_viewport = corridor->GetLimitRect();
      params.m_maxNumResults = SearchParams::kDefaultNumResultsAlongRoute;
      params.m_needAddress = true;
      TestSearchRequest request(*engine, params);
      request.Run();
      times.push_back(chrono::duration<double>(request.ResponseTime()).count());
      results = request.Results();
    }
    sort(times.begin(), times.end());
    double const median = times[times.size() / 2];
    medians.push_back(median);
    cout << query << ": " << results.size() << " results, min " << times.front() << " s, median " << median << " s"
         << endl;
    for (size_t i = 0; i < min<size_t>(results.size(), FLAGS_top); ++i)
    {
      auto const & r = results[i];
      auto const & pos = r.GetRoutePosition();
      cout << "  in " << setprecision(1) << pos->m_distanceAlongRouteM / 1000.0 << " km, " << setprecision(0)
           << pos->m_distanceFromRouteM << " m off, detour ~" << pos->m_detourSec / 60.0 << " min: " << r.GetString()
           << " (" << r.GetLocalizedFeatureType() << ")" << setprecision(3) << endl;
    }
    if (!results.empty())
    {
      cout << "  last in " << setprecision(1) << results.back().GetRoutePosition()->m_distanceAlongRouteM / 1000.0
           << " km" << setprecision(3) << endl;
    }
  }

  if (medians.empty())
    return 0;
  sort(medians.begin(), medians.end());
  cout << endl
       << "Median of the medians: " << medians[medians.size() / 2] << " s, maximum: " << medians.back() << " s" << endl;
  return 0;
}
