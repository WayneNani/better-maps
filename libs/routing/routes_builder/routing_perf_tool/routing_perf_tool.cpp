// better-maps: measures how long the car router takes for long routes, cold (first route of a process) and warm
// (the same router again, as the app reuses it), e.g.:
//
//   routing_perf_tool --data_path=data --mwm_path=maps \
//     --routes="50.7295477,7.1057999;49.453861,11.0772885 50.9375,6.9603;48.1374,11.5755" --repeat=3
//
// |maps| contains the country maps in a version directory (maps/261018/*.mwm), like on a device. All maps there are
// registered, as in the app, and one IndexRouter builds all routes in turn. For each route the time, length, ETA,
// number of geometry points, a hash of the geometry and the memory (RSS) of the process are printed.
//
// --evict_page_cache drops the maps from the page cache of the OS first (posix_fadvise), like after a reboot.
//
// --route_server_url=http://127.0.0.1:18100 uses server-assisted routing (services/route-server) with the token from
// the environment variable BM_ROUTE_SERVER_TOKEN (not a flag: the process list would show it). For each route it
// prints whether the server's route was used or why the router fell back, and the times of request, matching and
// refinement.

#include "routing/checkpoints.hpp"
#include "routing/index_router.hpp"
#include "routing/route.hpp"
#include "routing/router_delegate.hpp"
#include "routing/server_route.hpp"

#include "routing_common/num_mwm_id.hpp"

#include "storage/country_info_getter.hpp"
#include "storage/country_parent_getter.hpp"
#include "storage/routing_helpers.hpp"
#include "storage/storage.hpp"

#include "traffic/traffic_cache.hpp"

#include "indexer/classificator_loader.hpp"
#include "indexer/data_source.hpp"

#include "coding/files_container.hpp"

#include "platform/local_country_file.hpp"
#include "platform/platform.hpp"

#include "geometry/latlon.hpp"
#include "geometry/mercator.hpp"

#include "base/file_name_utils.hpp"
#include "base/logging.hpp"
#include "base/string_utils.hpp"
#include "base/timer.hpp"

#include "defines.hpp"

#include <fcntl.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

#include <gflags/gflags.h>

DEFINE_string(data_path, "", "Path to the data directory (resources, countries.txt)");
DEFINE_string(mwm_path, "", "Path to a directory with the mwm files in a version directory, e.g. 261018/");
DEFINE_string(routes, "50.7295477,7.1057999;49.453861,11.0772885",
              "Routes, separated by spaces, each as \"lat,lon;lat,lon[;lat,lon...]\" (default: Bonn - Nuremberg)");
DEFINE_int32(repeat, 1, "How often all routes are built in turn with the same router");
DEFINE_bool(evict_page_cache, false, "Drop the maps from the page cache of the OS before the first route");
DEFINE_string(read_sections, "",
              "Read these sections (e.g. \"cross_mwm,routing\") of all maps before the first route, after "
              "--evict_page_cache, to see what reading them ahead would save");
DEFINE_bool(verbose, false, "Log the router (LINFO), otherwise only warnings");
DEFINE_string(route_server_url, "",
              "Server-assisted routing with this server (token from the environment variable BM_ROUTE_SERVER_TOKEN)");
DEFINE_double(route_server_timeout, routing::HttpServerRouteSource::kDefaultTimeoutSec,
              "Timeout of a request to the routing server, s");

namespace
{
using namespace std;

vector<m2::PointD> ParseRoute(string const & s)
{
  vector<m2::PointD> points;
  for (auto const & point : strings::Tokenize(s, ";"))
  {
    auto const parts = strings::Tokenize(point, ",");
    CHECK_EQUAL(parts.size(), 2, (s));
    double lat, lon;
    CHECK(strings::to_double(parts[0], lat) && strings::to_double(parts[1], lon), (s));
    points.push_back(mercator::FromLatLon(lat, lon));
  }
  CHECK_GREATER_OR_EQUAL(points.size(), 2, (s));
  return points;
}

// Resident and peak resident memory of the process, MB.
pair<double, double> GetRssMb()
{
  ifstream in("/proc/self/status");
  string line;
  double rss = 0, hwm = 0;
  while (getline(in, line))
  {
    if (line.rfind("VmRSS:", 0) == 0)
      rss = stod(line.substr(6)) / 1024.0;
    else if (line.rfind("VmHWM:", 0) == 0)
      hwm = stod(line.substr(6)) / 1024.0;
  }
  return {rss, hwm};
}

vector<platform::LocalCountryFile> FindMaps()
{
  vector<platform::LocalCountryFile> maps;
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
      maps.push_back(mwm);
    }
  }
  return maps;
}

void EvictFromPageCache(vector<platform::LocalCountryFile> const & maps)
{
  for (auto const & mwm : maps)
  {
    auto const path = mwm.GetPath(MapFileType::Map);
    int const fd = open(path.c_str(), O_RDONLY);
    if (fd < 0)
      continue;
    posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
    close(fd);
  }
}

// Reads |tags| of all |maps| (into the page cache of the OS).
void ReadSections(vector<platform::LocalCountryFile> const & maps, string const & tags)
{
  base::Timer timer;
  uint64_t bytes = 0;
  vector<char> buffer(1 << 20);
  for (auto const & mwm : maps)
  {
    FilesContainerR const cont(mwm.GetPath(MapFileType::Map));
    for (auto const & tag : strings::Tokenize(tags, ","))
    {
      if (!cont.IsExist(string(tag)))
        continue;
      auto const reader = cont.GetReader(string(tag));
      for (uint64_t pos = 0; pos < reader.Size(); pos += buffer.size())
      {
        auto const size = static_cast<size_t>(min<uint64_t>(buffer.size(), reader.Size() - pos));
        reader.Read(pos, buffer.data(), size);
        bytes += size;
      }
    }
  }
  cout << fixed << setprecision(3) << "read " << tags << ": " << bytes / 1e6 << " MB in " << timer.ElapsedSeconds()
       << " s" << endl;
}

uint64_t HashPoints(vector<m2::PointD> const & points)
{
  uint64_t h = 1469598103934665603ULL;
  for (auto const & p : points)
  {
    for (double const v : {p.x, p.y})
    {
      auto const q = static_cast<int64_t>(v * 1e7);
      h = (h ^ static_cast<uint64_t>(q)) * 1099511628211ULL;
    }
  }
  return h;
}
}  // namespace

int main(int argc, char ** argv)
{
  gflags::SetUsageMessage("Measures the time of the car router for long routes.");
  gflags::ParseCommandLineFlags(&argc, &argv, true);

  base::g_LogLevel = FLAGS_verbose ? base::LINFO : base::LWARNING;

  if (!FLAGS_data_path.empty())
    GetPlatform().SetResourceDir(FLAGS_data_path);
  if (!FLAGS_mwm_path.empty())
    GetPlatform().SetWritableDirForTests(FLAGS_mwm_path);

  vector<vector<m2::PointD>> routes;
  for (auto const & r : strings::Tokenize(FLAGS_routes, " "))
    routes.push_back(ParseRoute(string(r)));

  base::Timer setupTimer;
  classificator::Load();
  auto const maps = FindMaps();
  if (FLAGS_evict_page_cache)
    EvictFromPageCache(maps);
  if (!FLAGS_read_sections.empty())
    ReadSections(maps, FLAGS_read_sections);

  FrozenDataSource dataSource;
  for (auto const & mwm : maps)
    dataSource.RegisterMap(mwm);

  // Like the app: all countries of countries.txt get a NumMwmId.
  storage::CountryParentGetter const parentGetter;
  auto const infoGetter = storage::CountryInfoReader::CreateCountryInfoGetter(GetPlatform());
  auto const numMwmIds = routing::CreateNumMwmIds(parentGetter.GetStorageForTesting());
  traffic::TrafficCache const trafficCache;

  routing::IndexRouter router(routing::VehicleType::Car, false /* loadAltitudes */, parentGetter,
                              [&](m2::PointD const & p) { return infoGetter->GetRegionCountryId(p); },
                              [&](string const & id) { return infoGetter->GetLimitRectForLeaf(id); }, numMwmIds,
                              routing::MakeNumMwmTree(*numMwmIds, *infoGetter), trafficCache, dataSource);
  if (!FLAGS_route_server_url.empty())
  {
    char const * token = getenv("BM_ROUTE_SERVER_TOKEN");
    CHECK(token && *token, ("Set BM_ROUTE_SERVER_TOKEN for --route_server_url"));
    auto source = make_shared<routing::HttpServerRouteSource>();
    source->SetServer(FLAGS_route_server_url, token);
    source->SetTimeout(FLAGS_route_server_timeout);
    router.SetServerRouteSource(source);
  }
  auto [rss0, hwm0] = GetRssMb();
  cout << fixed << setprecision(3) << "setup " << setupTimer.ElapsedSeconds() << " s, " << maps.size()
       << " maps, RSS " << rss0 << " MB" << endl;

  routing::RouterDelegate delegate;
  for (int run = 0; run < FLAGS_repeat; ++run)
  {
    for (size_t i = 0; i < routes.size(); ++i)
    {
      routing::Route route("" /* router */, 0 /* routeId */);
      auto const statsBefore = router.GetServerRoutingStats();
      base::Timer timer;
      auto const code = router.CalculateRoute(routing::Checkpoints(vector<m2::PointD>(routes[i])),
                                              m2::PointD::Zero(), false /* adjustToPrevRoute */, delegate, route);
      double const sec = timer.ElapsedSeconds();
      auto const [rss, hwm] = GetRssMb();
      auto const & points = route.GetPoly().GetPoints();
      cout << fixed << setprecision(3) << "run " << run << " route " << i << ": " << sec << " s, code "
           << DebugPrint(code) << ", " << setprecision(1) << route.GetTotalDistanceMeters() << " m, "
           << route.GetTotalTimeSec() << " s ETA, " << points.size() << " points, hash " << hex
           << HashPoints(points) << dec << ", RSS " << rss << " MB (peak " << hwm << " MB)" << endl;
      auto const & stats = router.GetServerRoutingStats();
      if (stats.m_attempts != statsBefore.m_attempts)
      {
        cout << "  server: "
             << (stats.m_used != statsBefore.m_used ? string("used") : "fallback (" + stats.m_lastFallback + ")")
             << setprecision(3) << ", request " << stats.m_requestSec << " s, match " << stats.m_matchSec
             << " s, refine " << stats.m_refineSec << " s; server route " << setprecision(1) << stats.m_serverDistanceM
             << " m, " << stats.m_serverDurationSec << " s" << endl;
      }
    }
  }
  return 0;
}
