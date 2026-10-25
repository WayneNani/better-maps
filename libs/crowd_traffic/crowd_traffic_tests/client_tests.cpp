#include "testing/testing.hpp"

#include "crowd_traffic/client.hpp"
#include "crowd_traffic/crowd_traffic_tests/test_support.hpp"

#include <algorithm>
#include <cmath>
#include <map>
#include <numbers>
#include <set>
#include <string>
#include <vector>

namespace client_tests
{
using namespace crowd_traffic;
using namespace crowd_traffic::tests;

std::vector<TracePoint> PlanPoints(go_vectors::PlanCase const & c)
{
  std::vector<TracePoint> pts;
  for (size_t i = 0; i < c.numPts; ++i)
    pts.push_back(ToTracePoint(c.pts[i]));
  return pts;
}

// Berlin, eastwards along a latitude: 20 km on a primary road at 60 km/h (one sample per
// second) starting at 2026-10-26 08:00:07 UTC.
double constexpr kT0 = 1793001607.0;
std::vector<TracePoint> LongTrip(double kmh = 60, RoadClass cls = RoadClass::Primary, double t0 = kT0)
{
  return StraightTrip(52.45, 13.10, 52.45, 13.10 + 20000.0 / (111320.0 * std::cos(52.45 * std::numbers::pi / 180)), kmh,
                      t0, cls);
}

std::set<SegmentKey> KeysOf(std::vector<Bundle> const & bundles)
{
  std::set<SegmentKey> res;
  for (auto const & b : bundles)
    for (auto const & r : b.m_reports)
      res.insert(r.Key());
  return res;
}

// The complete client rules give exactly the bundles of the Go reference client (PlanTrip) when
// both use the same deterministic randomness: same windows, send times, segments, order and
// buckets.
UNIT_TEST(CrowdTraffic_PlansMatchGo)
{
  for (auto const & c : go_vectors::kPlanCases)
  {
    auto client = MakeClient(std::make_unique<FixedRandom>());
    auto const bundles = RunTrip(*client, PlanPoints(c));
    TEST_EQUAL(bundles.size(), c.numBundles, (c.name, DebugPrint(client->GetStats())));
    for (size_t i = 0; i < std::min(bundles.size(), c.numBundles); ++i)
    {
      auto const & got = bundles[i];
      auto const & want = c.bundles[i];
      TEST_EQUAL(got.m_window, want.window, (c.name, i));
      TEST_ALMOST_EQUAL_ABS(got.m_sendAt, want.sendAt, 1e-3, (c.name, i));
      TEST_EQUAL(got.m_reports.size(), want.numReports, (c.name, i));
      for (size_t j = 0; j < std::min(got.m_reports.size(), want.numReports); ++j)
        TEST_EQUAL(EncodeBundleJson({got.m_reports[j]}), EncodeBundleJson({ToReport(want.reports[j])}), (c.name, i, j));
      // And therefore byte-identical requests.
      std::vector<Report> wantReports;
      for (size_t j = 0; j < want.numReports; ++j)
        wantReports.push_back(ToReport(want.reports[j]));
      TEST(EncodeRequest(got.m_reports, 0) == EncodeRequest(wantReports, 0), (c.name, i));
    }
  }
}

UNIT_TEST(CrowdTraffic_StartAndDestinationTrimming)
{
  auto const pts = LongTrip();
  // Deterministic: start trim = 1000 + 0.5 · 500 = 1250 m.
  auto client = MakeClient(std::make_unique<FixedRandom>());
  auto const bundles = RunTrip(*client, pts);
  TEST(!bundles.empty(), ());
  auto const & start = pts.front();
  auto const & dest = pts.back();
  // Recompute the traversals to check every reported segment.
  auto const trav = Segment(pts);
  auto const keys = KeysOf(bundles);
  for (auto const & t : trav)
  {
    if (keys.count(t.Key()) == 0)
      continue;
    TEST_GREATER_OR_EQUAL(t.m_distIn, 1250.0, ());
    TEST_GREATER_OR_EQUAL(DistanceM(start.m_lat, start.m_lon, t.m_in.m_lat, t.m_in.m_lon), 1000.0, ());
    TEST_GREATER_OR_EQUAL(DistanceM(dest.m_lat, dest.m_lon, t.m_out.m_lat, t.m_out.m_lon), 1000.0, ());
    TEST_GREATER_OR_EQUAL(Segment(pts).back().m_distOut - t.m_distOut, 1500.0 - 200.0, ());
  }
  auto const stats = client->GetStats();
  TEST_GREATER(stats.m_droppedStart, 0, ());
  TEST_GREATER(stats.m_droppedDestination, 0, ());
  TEST_EQUAL(stats.m_trips, 1, ());

  // With a different random start trim, the first reported segment moves.
  auto client2 = MakeClient(std::make_unique<FixedRandom>(0.999));
  ClientConfig cfg;
  auto const bundles2 = RunTrip(*client2, pts);
  double minIn = 1e9, minIn2 = 1e9;
  for (auto const & t : trav)
  {
    if (KeysOf(bundles).count(t.Key()))
      minIn = std::min(minIn, t.m_distIn);
    if (KeysOf(bundles2).count(t.Key()))
      minIn2 = std::min(minIn2, t.m_distIn);
  }
  TEST_GREATER_OR_EQUAL(minIn2, cfg.m_startTrimMaxM - 1, ());
  TEST_LESS(minIn, minIn2, ());
}

UNIT_TEST(CrowdTraffic_IntermediateStopsAreTrimmed)
{
  auto const pts = LongTrip();
  auto const & mid = pts[pts.size() / 2];
  auto client = MakeClient(std::make_unique<FixedRandom>());
  client->StartTrip({pts.back().m_lat, pts.back().m_lon}, {{mid.m_lat, mid.m_lon}});
  double driven = 0;
  std::vector<double> cum(pts.size(), 0);
  for (size_t i = 1; i < pts.size(); ++i)
    cum[i] = cum[i - 1] + DistanceM(pts[i - 1].m_lat, pts[i - 1].m_lon, pts[i].m_lat, pts[i].m_lon);
  std::vector<Bundle> bundles;
  for (size_t i = 0; i < pts.size(); ++i)
  {
    client->AddSample({pts[i].m_lat, pts[i].m_lon, pts[i].m_time, pts[i].m_class, cum.back() - cum[i]});
    for (auto & b : client->TakeDue(pts[i].m_time))
      bundles.push_back(std::move(b));
    driven = cum[i];
  }
  client->EndTrip();
  while (auto n = client->GetNextDueTime())
    for (auto & b : client->TakeDue(*n))
      bundles.push_back(std::move(b));
  TEST_GREATER(driven, 19000, ());
  for (auto const & t : Segment(pts))
  {
    if (KeysOf(bundles).count(t.Key()))
    {
      TEST_GREATER_OR_EQUAL(DistanceM(mid.m_lat, mid.m_lon, t.m_in.m_lat, t.m_in.m_lon), 1000.0, ());
      TEST_GREATER_OR_EQUAL(DistanceM(mid.m_lat, mid.m_lon, t.m_out.m_lat, t.m_out.m_lon), 1000.0, ());
    }
  }

  // Changing the stops during the trip applies to later segments.
  auto client2 = MakeClient(std::make_unique<FixedRandom>());
  client2->UpdateDestination({0, 0}, {});  // No trip: ignored.
  client2->StartTrip({0, 0});
  client2->UpdateDestination({pts.back().m_lat, pts.back().m_lon}, {});
  TEST(client2->IsTripActive(), ());
}

UNIT_TEST(CrowdTraffic_UnknownRemainingDistanceIsTrimmed)
{
  auto const pts = LongTrip();
  auto client = MakeClient(std::make_unique<FixedRandom>());
  client->StartTrip({pts.back().m_lat, pts.back().m_lon});
  for (auto const & p : pts)
    client->AddSample({p.m_lat, p.m_lon, p.m_time, p.m_class, -1.0});
  client->EndTrip();
  while (auto n = client->GetNextDueTime())
    TEST(client->TakeDue(*n).empty(), ());
  TEST_EQUAL(client->GetStats().m_bundles, 0, ());
}

UNIT_TEST(CrowdTraffic_ReleaseRequiresContinuation)
{
  auto const pts = LongTrip();
  std::vector<double> cum(pts.size(), 0);
  for (size_t i = 1; i < pts.size(); ++i)
    cum[i] = cum[i - 1] + DistanceM(pts[i - 1].m_lat, pts[i - 1].m_lon, pts[i].m_lat, pts[i].m_lon);
  // The navigation ends (unplanned) after 8 km although the destination is 20 km away.
  size_t const stop =
      static_cast<size_t>(std::find_if(cum.begin(), cum.end(), [](double d) { return d > 8000; }) - cum.begin());
  auto client = MakeClient(std::make_unique<FixedRandom>());
  client->StartTrip({pts.back().m_lat, pts.back().m_lon});
  std::vector<Bundle> bundles;
  for (size_t i = 0; i <= stop; ++i)
  {
    client->AddSample({pts[i].m_lat, pts[i].m_lon, pts[i].m_time, pts[i].m_class, cum.back() - cum[i]});
    for (auto & b : client->TakeDue(pts[i].m_time))
      bundles.push_back(std::move(b));
  }
  client->EndTrip();
  TEST(!client->IsTripActive(), ());
  while (auto n = client->GetNextDueTime())
    for (auto & b : client->TakeDue(*n))
      bundles.push_back(std::move(b));
  // Everything reported lies at least 500 m before the end of the trip (or was left 3 min
  // before), i.e. the last segments before the unplanned stop were dropped.
  TEST(!bundles.empty(), ());
  double const endTime = pts[stop].m_time;
  for (auto const & t : Segment(std::vector<TracePoint>(pts.begin(), pts.begin() + stop + 1)))
    if (KeysOf(bundles).count(t.Key()))
      TEST(t.m_distOut + 500 <= cum[stop] + 1e-6 || t.m_tOut + 180 <= endTime, ());
  TEST_GREATER(client->GetStats().m_droppedUnreleased, 0, ());
}

UNIT_TEST(CrowdTraffic_ReleaseByTimeWhileStanding)
{
  // A segment completed shortly before the vehicle stops in a jam is released 3 minutes later
  // while the navigation keeps running, even without new samples.
  auto pts = LongTrip();
  std::vector<double> cum(pts.size(), 0);
  for (size_t i = 1; i < pts.size(); ++i)
    cum[i] = cum[i - 1] + DistanceM(pts[i - 1].m_lat, pts[i - 1].m_lon, pts[i].m_lat, pts[i].m_lon);
  size_t const stop =
      static_cast<size_t>(std::find_if(cum.begin(), cum.end(), [](double d) { return d > 6000; }) - cum.begin());
  ClientConfig cfg;
  cfg.m_maxPerWindow = 100;  // No sampling: every released segment is reported.
  auto client = MakeClient(std::make_unique<FixedRandom>(), cfg);
  client->StartTrip({pts.back().m_lat, pts.back().m_lon});
  for (size_t i = 0; i <= stop; ++i)
    client->AddSample({pts[i].m_lat, pts[i].m_lon, pts[i].m_time, pts[i].m_class, cum.back() - cum[i]});
  auto const before = client->GetStats();
  // Wall clock advances, the trip continues; take everything that becomes due.
  std::vector<Bundle> bundles;
  for (double now = pts[stop].m_time; now < pts[stop].m_time + 1800; now += 5)
    for (auto & b : client->TakeDue(now))
      bundles.push_back(std::move(b));
  TEST(client->IsTripActive(), ());
  // The last completed segment (less than 500 m before the stop) was released by time.
  auto const trav = Segment(std::vector<TracePoint>(pts.begin(), pts.begin() + stop + 1));
  bool lastReported = false;
  for (auto const & t : trav)
    if (t.m_distOut + 500 > cum[stop] && KeysOf(bundles).count(t.Key()))
      lastReported = true;
  TEST(lastReported, (DebugPrint(before), DebugPrint(client->GetStats())));
}

UNIT_TEST(CrowdTraffic_WindowsSendTimesAndSampling)
{
  // A long, slow trip across many windows: at most 12 reports per window, unique keys, send time
  // 30–240 s after the window end, reports belong to their window.
  auto const pts = StraightTrip(52.40, 13.10, 52.40, 13.60, 20, kT0, RoadClass::Motorway);
  auto client = MakeClient(std::make_unique<SeededRandom>(42));
  auto const bundles = RunTrip(*client, pts);
  TEST_GREATER(bundles.size(), 3, ());
  std::set<int64_t> windows;
  for (auto const & b : bundles)
  {
    TEST(windows.insert(b.m_window).second, ("one bundle per window"));
    TEST_EQUAL(b.m_window % kWindowSeconds, 0, ());
    TEST_LESS_OR_EQUAL(b.m_reports.size(), kMaxReportsPerBundle, ());
    TEST_GREATER_OR_EQUAL(b.m_sendAt, b.m_window + kWindowSeconds + 30.0, ());
    TEST_LESS_OR_EQUAL(b.m_sendAt, b.m_window + kWindowSeconds + 240.0, ());
    TEST_EQUAL(b.m_deadline, b.m_window + kWindowSeconds + 300.0 - 10.0, ());
    std::set<SegmentKey> keys;
    for (auto const & r : b.m_reports)
    {
      TEST_EQUAL(r.m_window, b.m_window, ());
      TEST(keys.insert(r.Key()).second, ("each key once per bundle"));
      TEST(r.m_speedBucket >= 0 && r.m_speedBucket < kNumSpeedBuckets, ());
    }
  }
  auto const stats = client->GetStats();
  TEST_GREATER(stats.m_droppedSampling, 0, ());
  TEST_EQUAL(stats.m_reports, [&]
  {
    size_t n = 0;
    for (auto const & b : bundles)
      n += b.m_reports.size();
    return n;
  }(), ());
  TEST(!client->HasPendingWork(), ());
  TEST(!client->GetNextDueTime(), ());
}

UNIT_TEST(CrowdTraffic_NoisyBucketsFollowTheTruth)
{
  // 60 km/h → bucket 4 (55–80 km/h); with randomized response about 77 % keep it.
  int total = 0, kept = 0;
  for (int seed = 0; seed < 30; ++seed)
  {
    auto client = MakeClient(std::make_unique<SeededRandom>(seed));
    for (auto const & b : RunTrip(*client, LongTrip(60, RoadClass::Primary, kT0 + seed * 3600)))
    {
      for (auto const & r : b.m_reports)
      {
        ++total;
        kept += r.m_speedBucket == 4 ? 1 : 0;
      }
    }
  }
  TEST_GREATER(total, 500, ());
  TEST_ALMOST_EQUAL_ABS(static_cast<double>(kept) / total, 0.770, 0.05, (kept, total));
}

UNIT_TEST(CrowdTraffic_RoadClassesAndImplausibleSpeeds)
{
  // Unclassified roads are never reported.
  auto client = MakeClient(std::make_unique<FixedRandom>());
  TEST(RunTrip(*client, LongTrip(60, RoadClass::None)).empty(), ());
  TEST_GREATER(client->GetStats().m_droppedRoadClass, 0, ());

  // Faster than 250 km/h: measurement error.
  auto fast = MakeClient(std::make_unique<FixedRandom>());
  TEST(RunTrip(*fast, LongTrip(300, RoadClass::Motorway)).empty(), ());
  TEST_GREATER(fast->GetStats().m_droppedMeasurement, 0, ());
}

UNIT_TEST(CrowdTraffic_DailyLimitsAcrossTrips)
{
  ClientConfig cfg;
  cfg.m_dailyLimits = {20, 2};
  auto client = MakeClient(std::make_unique<SeededRandom>(9), cfg);
  std::map<SegmentKey, int> perKey;
  size_t total = 0;
  // The same commute three times on one day.
  for (int trip = 0; trip < 3; ++trip)
  {
    for (auto const & b : RunTrip(*client, LongTrip(60, RoadClass::Primary, kT0 + trip * 3600)))
    {
      for (auto const & r : b.m_reports)
      {
        ++perKey[r.Key()];
        ++total;
      }
    }
  }
  TEST_LESS_OR_EQUAL(total, 20, ());
  for (auto const & [k, n] : perKey)
    TEST_LESS_OR_EQUAL(n, 2, (DebugPrint(k)));
  TEST_GREATER(client->GetStats().m_droppedDailyLimit, 0, ());
}

UNIT_TEST(CrowdTraffic_ExpiredBundlesAreDropped)
{
  auto const pts = LongTrip();
  auto client = MakeClient(std::make_unique<FixedRandom>());
  client->StartTrip({pts.back().m_lat, pts.back().m_lon});
  std::vector<double> cum(pts.size(), 0);
  for (size_t i = 1; i < pts.size(); ++i)
    cum[i] = cum[i - 1] + DistanceM(pts[i - 1].m_lat, pts[i - 1].m_lon, pts[i].m_lat, pts[i].m_lon);
  for (size_t i = 0; i < pts.size(); ++i)
    client->AddSample({pts[i].m_lat, pts[i].m_lon, pts[i].m_time, pts[i].m_class, cum.back() - cum[i]});
  client->EndTrip();
  TEST(client->HasPendingWork(), ());
  // The app was asleep: an hour later nothing may be sent any more.
  TEST(client->TakeDue(pts.back().m_time + 3600).empty(), ());
  TEST_GREATER(client->GetStats().m_droppedExpired, 0, ());
  TEST(!client->HasPendingWork(), ());
}

UNIT_TEST(CrowdTraffic_OptOutDiscardsEverything)
{
  auto const pts = LongTrip();
  auto client = MakeClient(std::make_unique<FixedRandom>());
  client->StartTrip({pts.back().m_lat, pts.back().m_lon});
  for (auto const & p : pts)
    client->AddSample({p.m_lat, p.m_lon, p.m_time, p.m_class, 10000});
  TEST(client->GetNextDueTime().has_value(), ());
  client->Discard();
  TEST(!client->IsTripActive(), ());
  TEST(!client->HasPendingWork(), ());
  TEST(client->TakeDue(pts.back().m_time + 400).empty(), ());

  // Without a trip, samples are ignored.
  client->AddSample({52.5, 13.4, kT0, RoadClass::Motorway, 10000});
  client->MarkGap();
  TEST(!client->HasPendingWork(), ());
}

UNIT_TEST(CrowdTraffic_OutOfOrderSamplesAndGaps)
{
  auto const pts = LongTrip();
  std::vector<double> cum(pts.size(), 0);
  for (size_t i = 1; i < pts.size(); ++i)
    cum[i] = cum[i - 1] + DistanceM(pts[i - 1].m_lat, pts[i - 1].m_lon, pts[i].m_lat, pts[i].m_lon);
  auto reference = MakeClient(std::make_unique<FixedRandom>());
  auto const expected = RunTrip(*reference, pts);

  // Repeated and late fixes do not change anything.
  auto client = MakeClient(std::make_unique<FixedRandom>());
  client->StartTrip({pts.back().m_lat, pts.back().m_lon});
  std::vector<Bundle> got;
  for (size_t i = 0; i < pts.size(); ++i)
  {
    client->AddSample({pts[i].m_lat, pts[i].m_lon, pts[i].m_time, pts[i].m_class, cum.back() - cum[i]});
    client->AddSample({pts[i].m_lat, pts[i].m_lon, pts[i].m_time, pts[i].m_class, cum.back() - cum[i]});
    if (i > 0)
      client->AddSample({pts[i - 1].m_lat, pts[i - 1].m_lon, pts[i - 1].m_time, pts[i - 1].m_class, 0});
    for (auto & b : client->TakeDue(pts[i].m_time))
      got.push_back(std::move(b));
  }
  client->EndTrip();
  while (auto n = client->GetNextDueTime())
    for (auto & b : client->TakeDue(*n))
      got.push_back(std::move(b));
  TEST_EQUAL(KeysOf(got), KeysOf(expected), ());

  // A gap in the map matching removes the tiles it touches.
  auto gapped = MakeClient(std::make_unique<FixedRandom>());
  gapped->StartTrip({pts.back().m_lat, pts.back().m_lon});
  std::vector<Bundle> withGap;
  for (size_t i = 0; i < pts.size(); ++i)
  {
    gapped->AddSample({pts[i].m_lat, pts[i].m_lon, pts[i].m_time, pts[i].m_class, cum.back() - cum[i]});
    if (i == pts.size() / 2)
      gapped->MarkGap();
    for (auto & b : gapped->TakeDue(pts[i].m_time))
      withGap.push_back(std::move(b));
  }
  gapped->EndTrip();
  while (auto n = gapped->GetNextDueTime())
    for (auto & b : gapped->TakeDue(*n))
      withGap.push_back(std::move(b));
  TEST_GREATER_OR_EQUAL(gapped->GetStats().m_droppedRoadClass, 1, ());
}

UNIT_TEST(CrowdTraffic_NewTripEndsTheOldOne)
{
  auto const pts = LongTrip();
  auto client = MakeClient(std::make_unique<FixedRandom>());
  client->StartTrip({pts.back().m_lat, pts.back().m_lon});
  for (size_t i = 0; i < pts.size() / 2; ++i)
    client->AddSample({pts[i].m_lat, pts[i].m_lon, pts[i].m_time, pts[i].m_class, 15000});
  client->StartTrip({pts.back().m_lat, pts.back().m_lon});
  TEST_EQUAL(client->GetStats().m_trips, 2, ());
  TEST_GREATER(client->GetStats().m_droppedUnreleased, 0, ());
  TEST(client->IsTripActive(), ());
  TEST(!DebugPrint(client->GetStats()).empty(), ());
}
}  // namespace client_tests
