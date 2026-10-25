// Client side of the end-to-end test tools/better-maps/crowd-e2e.sh (not a unit test).
//
// Reads map-matched trips (crowd-vectors traces), runs every trip through its own reporting
// client (as on separate phones, with secure randomness) and writes the binary HTTP requests of
// all bundles as hex lines. The requests are then encrypted and sent through the OHTTP relay by
// the Java sender of the app.
//
// Environment:
//   CROWD_E2E_TRACES   input file ("T <road>" lines followed by "<lat> <lon> <unix s> <class>")
//   CROWD_E2E_OUT      output file, one hex-encoded request per line
//   CROWD_E2E_WINDOW   window start written into the reports (the server's current window;
//                      the server runs with a short window, the simulation with 15 minutes)
//   CROWD_E2E_NOW      Date of the requests (Unix seconds)
// Without CROWD_E2E_TRACES the test does nothing.

#include "testing/testing.hpp"

#include "crowd_traffic/client.hpp"
#include "crowd_traffic/crowd_traffic_tests/test_support.hpp"
#include "crowd_traffic/protocol.hpp"

#include "base/logging.hpp"

#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

namespace e2e_client
{
using namespace crowd_traffic;
using namespace crowd_traffic::tests;

UNIT_TEST(CrowdTrafficE2E_WriteRequests)
{
  char const * traces = std::getenv("CROWD_E2E_TRACES");
  if (traces == nullptr)
  {
    LOG(LINFO, ("CROWD_E2E_TRACES not set, skipping"));
    return;
  }
  char const * outPath = std::getenv("CROWD_E2E_OUT");
  char const * window = std::getenv("CROWD_E2E_WINDOW");
  char const * now = std::getenv("CROWD_E2E_NOW");
  TEST(outPath && window && now, ("CROWD_E2E_OUT, CROWD_E2E_WINDOW and CROWD_E2E_NOW are required"));

  std::ifstream in(traces);
  TEST(in, (traces));
  std::vector<std::pair<std::string, std::vector<TracePoint>>> trips;
  std::string line;
  while (std::getline(in, line))
  {
    if (line.empty())
      continue;
    if (line[0] == 'T')
    {
      trips.emplace_back(line.substr(2), std::vector<TracePoint>{});
      continue;
    }
    std::istringstream ls(line);
    TracePoint p;
    int cls = 0;
    ls >> p.m_lat >> p.m_lon >> p.m_time >> cls;
    TEST(ls && !trips.empty(), (line));
    p.m_class = ClassFromInt(cls);
    trips.back().second.push_back(p);
  }
  TEST(!trips.empty(), ());

  int64_t const serverWindow = std::atoll(window);
  int64_t const date = std::atoll(now);
  std::ofstream out(outPath);
  size_t bundles = 0, reports = 0;
  ClientStats total;
  for (auto const & [road, pts] : trips)
  {
    auto client = MakeClient(std::make_unique<SecureRandom>());
    for (auto b : RunTrip(*client, pts))
    {
      for (auto & r : b.m_reports)
        r.m_window = serverWindow;
      auto const req = EncodeRequest(b.m_reports, date);
      TEST_EQUAL(req.size(), kPadTo, ());
      out << ToHex(req) << "\n";
      ++bundles;
      reports += b.m_reports.size();
    }
    auto const s = client->GetStats();
    total.m_trips += s.m_trips;
    total.m_traversals += s.m_traversals;
    total.m_droppedRoadClass += s.m_droppedRoadClass;
    total.m_droppedStart += s.m_droppedStart;
    total.m_droppedDestination += s.m_droppedDestination;
    total.m_droppedSampling += s.m_droppedSampling;
    total.m_bundles += s.m_bundles;
    total.m_reports += s.m_reports;
  }
  TEST(out, (outPath));
  LOG(LINFO, ("Trips:", trips.size(), "bundles:", bundles, "reports:", reports));
  LOG(LINFO, ("Client statistics:", DebugPrint(total)));
  TEST_GREATER(bundles, 0, ());
}
}  // namespace e2e_client
