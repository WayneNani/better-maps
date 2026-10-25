#pragma once

#include "crowd_traffic/daily_limits.hpp"
#include "crowd_traffic/protocol.hpp"
#include "crowd_traffic/randomness.hpp"
#include "crowd_traffic/segmenter.hpp"
#include "crowd_traffic/tiles.hpp"

#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <set>
#include <string>
#include <vector>

// The reporting client of the privacy-preserving congestion detection (Feature 5, AP5b). It
// implements the rules of the client contract (docs/CROWD_TRAFFIC_PRIVACY.md, sections 7 and 13)
// incrementally while a navigation is running:
//
//   map-matched samples → tile traversals → eligibility (road class, length, speed, start and
//   destination trimming) → delayed release (500 m or 3 min later, trip still running) →
//   one bundle per 15-min window, sent 30–240 s after the window end, at most 12 randomly chosen
//   segments, daily limits → randomized response on the speed bucket.
//
// The decisions are the same as in the Go reference client (services/crowd-traffic/internal/
// client, PlanTrip), which processes complete trips offline; the tests check this with vectors
// generated from the Go code. Encryption and sending are done by the platform (Android: OHTTP in
// Java), see EncodeRequest() for the request bytes.
//
// The class is thread-safe: samples arrive on the GUI thread, bundles are taken by the sender.
namespace crowd_traffic
{
struct ClientConfig
{
  // Start trimming: segments are dropped until the vehicle has driven a random distance in
  // [m_startTrimMinM, m_startTrimMaxM] (drawn per trip) and while they are closer than
  // m_startRadiusM (straight line) to the start point.
  double m_startTrimMinM = 1000;
  double m_startTrimMaxM = 1500;
  double m_startRadiusM = 1000;
  // Destination trimming: segments ending less than m_destRouteM (route) or m_destRadiusM
  // (straight line) before the destination are dropped. The straight-line rule also applies to
  // intermediate stops.
  double m_destRouteM = 1500;
  double m_destRadiusM = 1000;
  // Release: m_releaseDistanceM driven or m_releaseTimeS passed after leaving the tile, while the
  // trip continues.
  double m_releaseDistanceM = 500;
  double m_releaseTimeS = 180;
  // Plausibility of a measurement.
  double m_minLengthM = 50;
  double m_maxSpeedKmh = 250;
  // Sending: one bundle per window, at window end + U(m_sendDelayMinS, m_sendDelayMaxS).
  double m_sendDelayMinS = 30;
  double m_sendDelayMaxS = 240;
  size_t m_maxPerWindow = kMaxReportsPerBundle;
  int64_t m_windowSeconds = kWindowSeconds;
  // The server accepts a window until m_graceS after its end; bundles are given up
  // m_deadlineMarginS before (no retries after the grace period).
  double m_graceS = 300;
  double m_deadlineMarginS = 10;
  DailyLimits::Limits m_dailyLimits;
};

// A map-matched position during navigation.
struct TripSample
{
  double m_lat = 0.0;
  double m_lon = 0.0;
  // Unix time in seconds (GPS time of the fix).
  double m_time = 0.0;
  // Class of the road from this sample to the next one.
  RoadClass m_class = RoadClass::None;
  // Remaining route distance to the destination in metres; negative if unknown.
  double m_remainingRouteM = -1.0;
};

// A bundle ready to be encrypted and sent.
struct Bundle
{
  int64_t m_window = 0;
  // Planned send time and the time after which the bundle must not be sent any more (Unix s).
  double m_sendAt = 0.0;
  double m_deadline = 0.0;
  std::vector<Report> m_reports;
};

// Counters for diagnostics (logged without any location data).
struct ClientStats
{
  uint64_t m_trips = 0;
  uint64_t m_traversals = 0;
  uint64_t m_droppedRoadClass = 0;
  uint64_t m_droppedMeasurement = 0;
  uint64_t m_droppedStart = 0;
  uint64_t m_droppedDestination = 0;
  uint64_t m_droppedUnreleased = 0;
  uint64_t m_droppedLate = 0;
  uint64_t m_droppedDuplicate = 0;
  uint64_t m_droppedSampling = 0;
  uint64_t m_droppedDailyLimit = 0;
  uint64_t m_droppedExpired = 0;
  uint64_t m_bundles = 0;
  uint64_t m_reports = 0;
};

std::string DebugPrint(ClientStats const & stats);

class Client
{
public:
  using DayFn = std::function<int64_t(double unixSeconds)>;

  // countersPath: file for the daily limits (empty: memory only).
  Client(ClientConfig const & config, std::unique_ptr<RandomSource> rnd, std::string const & countersPath,
         DayFn dayOf = LocalDay);

  // Starts a trip (navigation started). A running trip is ended first.
  void StartTrip(LatLon const & destination, std::vector<LatLon> const & intermediateStops = {});
  // The route was changed during the trip (e.g. a stop was removed).
  void UpdateDestination(LatLon const & destination, std::vector<LatLon> const & intermediateStops);
  void AddSample(TripSample const & sample);
  // The vehicle left the route or the map matching was lost: the stretch up to the next sample
  // is not reported.
  void MarkGap();
  // The navigation ended (arrival, cancellation): segments not yet released are dropped,
  // released ones are still sent with their window.
  void EndTrip();
  // The user opted out: everything not yet sent is dropped. Daily counters are kept.
  void Discard();

  bool IsTripActive() const;
  // Bundles whose send time has come. Expired bundles are dropped.
  std::vector<Bundle> TakeDue(double now);
  std::optional<double> GetNextDueTime() const;
  bool HasPendingWork() const;
  ClientStats GetStats() const;

private:
  struct Candidate
  {
    Traversal m_traversal;
    uint64_t m_seq = 0;
    double m_releasedAt = 0.0;
  };

  struct Window
  {
    double m_sendAt = 0.0;
    std::vector<Candidate> m_candidates;
  };

  struct Pending
  {
    Traversal m_traversal;
    uint64_t m_seq = 0;
  };

  struct Trip
  {
    Segmenter m_segmenter;
    double m_startTrimM = 0.0;
    std::optional<LatLon> m_start;
    LatLon m_destination;
    std::vector<LatLon> m_intermediate;
    double m_lastTime = 0.0;
    // Sum of the straight distances between samples (route distance driven).
    double m_driven = 0.0;
    std::vector<Pending> m_pending;
  };

  void EndTripLocked();
  bool IsEligible(Trip const & trip, Traversal const & t, double remainingAtExit);
  void Release(Pending const & p, double releasedAt);
  void ReleaseByTime(Trip & trip, double now);
  std::optional<Bundle> BuildBundle(int64_t windowStart, Window & window, double now);
  double Deadline(int64_t windowStart) const;

  ClientConfig const m_config;
  std::unique_ptr<RandomSource> m_rnd;
  DailyLimits m_limits;
  DayFn m_dayOf;
  RandomizedResponse m_rr;

  mutable std::mutex m_mutex;
  std::optional<Trip> m_trip;
  std::map<int64_t, Window> m_windows;
  // Windows whose bundle was already built; later releases for them are dropped.
  std::set<int64_t> m_closedWindows;
  uint64_t m_seq = 0;
  ClientStats m_stats;
};
}  // namespace crowd_traffic
