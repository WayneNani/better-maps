#include "crowd_traffic/client.hpp"

#include <algorithm>
#include <sstream>
#include <utility>

namespace crowd_traffic
{
std::string DebugPrint(ClientStats const & s)
{
  std::ostringstream out;
  out << "trips " << s.m_trips << ", traversals " << s.m_traversals << ", dropped: road class " << s.m_droppedRoadClass
      << ", measurement " << s.m_droppedMeasurement << ", start " << s.m_droppedStart << ", destination "
      << s.m_droppedDestination << ", unreleased " << s.m_droppedUnreleased << ", late " << s.m_droppedLate
      << ", duplicate " << s.m_droppedDuplicate << ", sampling " << s.m_droppedSampling << ", daily limit "
      << s.m_droppedDailyLimit << ", expired " << s.m_droppedExpired << "; bundles " << s.m_bundles << ", reports "
      << s.m_reports;
  return out.str();
}

Client::Client(ClientConfig const & config, std::unique_ptr<RandomSource> rnd, std::string const & countersPath,
               DayFn dayOf)
  : m_config(config)
  , m_rnd(std::move(rnd))
  , m_limits(countersPath, config.m_dailyLimits, *m_rnd)
  , m_dayOf(std::move(dayOf))
  , m_rr(kNumSpeedBuckets, kEpsilon)
{}

void Client::StartTrip(LatLon const & destination, std::vector<LatLon> const & intermediateStops)
{
  std::lock_guard lock(m_mutex);
  EndTripLocked();
  m_trip.emplace();
  m_trip->m_startTrimM = m_rnd->Uniform(m_config.m_startTrimMinM, m_config.m_startTrimMaxM);
  m_trip->m_destination = destination;
  m_trip->m_intermediate = intermediateStops;
  ++m_stats.m_trips;
}

void Client::UpdateDestination(LatLon const & destination, std::vector<LatLon> const & intermediateStops)
{
  std::lock_guard lock(m_mutex);
  if (!m_trip)
    return;
  m_trip->m_destination = destination;
  m_trip->m_intermediate = intermediateStops;
}

bool Client::IsTripActive() const
{
  std::lock_guard lock(m_mutex);
  return m_trip.has_value();
}

void Client::MarkGap()
{
  std::lock_guard lock(m_mutex);
  if (m_trip)
    m_trip->m_segmenter.MarkGap();
}

void Client::EndTrip()
{
  std::lock_guard lock(m_mutex);
  EndTripLocked();
}

void Client::EndTripLocked()
{
  if (!m_trip)
    return;
  m_stats.m_droppedUnreleased += m_trip->m_pending.size();
  m_trip.reset();
}

void Client::Discard()
{
  std::lock_guard lock(m_mutex);
  EndTripLocked();
  m_windows.clear();
}

bool Client::IsEligible(Trip const & trip, Traversal const & t, double remainingAtExit)
{
  if (!IsReportable(t.m_class))
  {
    ++m_stats.m_droppedRoadClass;
    return false;
  }
  if (t.m_lengthM < m_config.m_minLengthM || t.SpeedKmh() > m_config.m_maxSpeedKmh || t.m_entry == t.m_exit)
  {
    ++m_stats.m_droppedMeasurement;
    return false;
  }
  auto const near = [&t](LatLon const & p, double radius)
  {
    return DistanceM(p.m_lat, p.m_lon, t.m_in.m_lat, t.m_in.m_lon) < radius ||
           DistanceM(p.m_lat, p.m_lon, t.m_out.m_lat, t.m_out.m_lon) < radius;
  };
  if (!trip.m_start || t.m_distIn < trip.m_startTrimM || near(*trip.m_start, m_config.m_startRadiusM))
  {
    ++m_stats.m_droppedStart;
    return false;
  }
  bool nearStop = near(trip.m_destination, m_config.m_destRadiusM);
  for (auto const & p : trip.m_intermediate)
    nearStop = nearStop || near(p, m_config.m_destRadiusM);
  // An unknown remaining distance is treated as "close to the destination".
  if (remainingAtExit < m_config.m_destRouteM || nearStop)
  {
    ++m_stats.m_droppedDestination;
    return false;
  }
  return true;
}

void Client::Release(Pending const & p, double releasedAt)
{
  int64_t const ws = WindowStart(p.m_traversal.m_tOut, m_config.m_windowSeconds);
  if (m_closedWindows.count(ws) != 0)
  {
    ++m_stats.m_droppedLate;
    return;
  }
  auto it = m_windows.find(ws);
  if (it == m_windows.end())
  {
    Window w;
    w.m_sendAt = static_cast<double>(ws + m_config.m_windowSeconds) +
                 m_rnd->Uniform(m_config.m_sendDelayMinS, m_config.m_sendDelayMaxS);
    it = m_windows.emplace(ws, std::move(w)).first;
  }
  it->second.m_candidates.push_back({p.m_traversal, p.m_seq, releasedAt});
}

void Client::ReleaseByTime(Trip & trip, double now)
{
  auto & pending = trip.m_pending;
  auto it = std::stable_partition(pending.begin(), pending.end(), [&](Pending const & p)
  { return p.m_traversal.m_tOut + m_config.m_releaseTimeS > now; });
  for (auto r = it; r != pending.end(); ++r)
    Release(*r, r->m_traversal.m_tOut + m_config.m_releaseTimeS);
  pending.erase(it, pending.end());
}

void Client::AddSample(TripSample const & s)
{
  std::lock_guard lock(m_mutex);
  if (!m_trip)
    return;
  Trip & trip = *m_trip;
  auto & seg = trip.m_segmenter;
  if (seg.HasPoints())
  {
    if (s.m_time <= trip.m_lastTime)
      return;  // Out of order or repeated fix.
    auto const & last = seg.GetLastPoint();
    trip.m_driven += DistanceM(last.m_lat, last.m_lon, s.m_lat, s.m_lon);
  }
  else
  {
    trip.m_start = LatLon{s.m_lat, s.m_lon};
  }
  trip.m_lastTime = s.m_time;

  std::vector<Traversal> done;
  seg.Add({s.m_lat, s.m_lon, s.m_time, s.m_class}, done);
  for (auto const & t : done)
  {
    ++m_stats.m_traversals;
    // Remaining route distance at the tile exit, from the remaining distance at this sample.
    double const remaining = s.m_remainingRouteM < 0 ? -1.0 : s.m_remainingRouteM + (seg.GetDistanceM() - t.m_distOut);
    if (IsEligible(trip, t, remaining))
      trip.m_pending.push_back({t, m_seq++});
  }

  // Release: distance rule at this sample, time rule up to this sample. A segment released by
  // both rules counts as released at the earlier time.
  auto & pending = trip.m_pending;
  auto it = std::stable_partition(pending.begin(), pending.end(), [&](Pending const & p)
  {
    return trip.m_driven < p.m_traversal.m_distOut + m_config.m_releaseDistanceM &&
           p.m_traversal.m_tOut + m_config.m_releaseTimeS > s.m_time;
  });
  for (auto r = it; r != pending.end(); ++r)
    Release(*r, std::min(s.m_time, r->m_traversal.m_tOut + m_config.m_releaseTimeS));
  pending.erase(it, pending.end());
}

double Client::Deadline(int64_t windowStart) const
{
  return static_cast<double>(windowStart + m_config.m_windowSeconds) + m_config.m_graceS - m_config.m_deadlineMarginS;
}

std::optional<Bundle> Client::BuildBundle(int64_t windowStart, Window & window, double now)
{
  auto & cands = window.m_candidates;
  if (now >= Deadline(windowStart))
  {
    m_stats.m_droppedExpired += cands.size();
    return {};
  }
  // Candidates in the order of the trip, released before the send time, each key once.
  std::sort(cands.begin(), cands.end(), [](Candidate const & a, Candidate const & b) { return a.m_seq < b.m_seq; });
  std::vector<Traversal> chosen;
  std::set<SegmentKey> seen;
  for (auto const & c : cands)
  {
    if (c.m_releasedAt > window.m_sendAt)
    {
      ++m_stats.m_droppedLate;
      continue;
    }
    if (!seen.insert(c.m_traversal.Key()).second)
    {
      ++m_stats.m_droppedDuplicate;
      continue;
    }
    chosen.push_back(c.m_traversal);
  }
  // Uniform random subset in random order: Fisher–Yates shuffle, then take the first segments
  // that are within the daily limits.
  for (size_t i = chosen.size(); i-- > 1;)
  {
    size_t const j = m_rnd->IntN(static_cast<uint32_t>(i + 1));
    std::swap(chosen[i], chosen[j]);
  }
  int64_t const day = m_dayOf(now);
  Bundle b;
  b.m_window = windowStart;
  b.m_sendAt = window.m_sendAt;
  b.m_deadline = Deadline(windowStart);
  for (auto const & t : chosen)
  {
    if (b.m_reports.size() >= m_config.m_maxPerWindow)
    {
      ++m_stats.m_droppedSampling;
      continue;
    }
    if (!m_limits.TryCount(day, t.Key()))
    {
      ++m_stats.m_droppedDailyLimit;
      continue;
    }
    Report r;
    r.m_window = windowStart;
    r.m_tile = t.m_tile;
    r.m_entry = t.m_entry;
    r.m_exit = t.m_exit;
    r.m_class = t.m_class;
    r.m_speedBucket = m_rr.Perturb(BucketOf(t.SpeedKmh()), *m_rnd);
    b.m_reports.push_back(r);
  }
  if (b.m_reports.empty())
    return {};
  ++m_stats.m_bundles;
  m_stats.m_reports += b.m_reports.size();
  return b;
}

std::vector<Bundle> Client::TakeDue(double now)
{
  std::lock_guard lock(m_mutex);
  // While the navigation is running, the time rule releases segments without new samples.
  if (m_trip)
    ReleaseByTime(*m_trip, now);

  std::vector<Bundle> res;
  for (auto it = m_windows.begin(); it != m_windows.end();)
  {
    if (it->second.m_sendAt > now)
    {
      ++it;
      continue;
    }
    if (auto b = BuildBundle(it->first, it->second, now))
      res.push_back(std::move(*b));
    m_closedWindows.insert(it->first);
    it = m_windows.erase(it);
  }
  // Closed windows are only needed while segments of them can still be released.
  int64_t const oldest = WindowStart(now, m_config.m_windowSeconds) - 2 * m_config.m_windowSeconds;
  while (!m_closedWindows.empty() && *m_closedWindows.begin() < oldest)
    m_closedWindows.erase(m_closedWindows.begin());
  return res;
}

std::optional<double> Client::GetNextDueTime() const
{
  std::lock_guard lock(m_mutex);
  std::optional<double> res;
  for (auto const & [ws, w] : m_windows)
    if (!res || w.m_sendAt < *res)
      res = w.m_sendAt;
  return res;
}

bool Client::HasPendingWork() const
{
  std::lock_guard lock(m_mutex);
  return m_trip.has_value() || !m_windows.empty();
}

ClientStats Client::GetStats() const
{
  std::lock_guard lock(m_mutex);
  return m_stats;
}
}  // namespace crowd_traffic
