#pragma once

#include "crowd_traffic/client.hpp"
#include "crowd_traffic/protocol.hpp"
#include "crowd_traffic/route_follower.hpp"

#include "routing/route.hpp"
#include "routing/routing_session.hpp"

#include "platform/location.hpp"

#include "indexer/ftypes_matcher.hpp"

#include "geometry/point2d.hpp"

#include <atomic>
#include <memory>
#include <string>
#include <vector>

// Connects the navigation to the crowd-traffic reporting client (Feature 5, AP5b,
// docs/CROWD_TRAFFIC_PRIVACY.md): turns the positions matched to the route into map-matched
// samples (with the road class of the route segment) for crowd_traffic::Client.
//
// Only used during active car navigation and only if the user opted in. All methods except
// GetClient(), SetActive() and IsActive() must be called on the GUI thread (like RoutingManager).
class CrowdTrafficRecorder
{
public:
  // Settings (settings.ini, written by the platform UI).
  static char constexpr kEnabledSetting[] = "CrowdTrafficEnabled";
  static char constexpr kRelayUrlSetting[] = "CrowdTrafficRelayUrl";

  // countersPath: file for the daily limits (empty: in memory only).
  explicit CrowdTrafficRecorder(std::string const & countersPath);
  CrowdTrafficRecorder(std::unique_ptr<crowd_traffic::Client> client, bool active);

  // Opt-in and relay configured. Deactivating drops everything that was not sent yet.
  void SetActive(bool active);
  bool IsActive() const { return m_active; }
  // Reads the opt-in and the relay URL from the settings.
  static bool IsActiveInSettings();

  // A car navigation started (RoutingManager::FollowRoute).
  void OnFollowRoute(m2::PointD const & destination, std::vector<m2::PointD> const & intermediateStops);
  // A real (not extrapolated) location fix arrived.
  void OnRealLocation(double timestamp) { m_lastRealFixTime = timestamp; }
  // Called after the route matching of a location (raw: position before matching).
  void OnLocation(location::GpsInfo const & raw, routing::RoutingSession const & session,
                  std::shared_ptr<routing::Route> const & route);
  // The navigation ended (arrival, cancellation, route removed).
  void OnNavigationEnded();

  crowd_traffic::Client & GetClient() { return *m_client; }

  static crowd_traffic::RoadClass ToRoadClass(ftypes::HighwayClass cls, bool isLink);
  // Road class of every route segment. The router stores the highway class only in the first
  // segment of each feature (the following ones have HighwayClass::Undefined), so it is carried
  // forward; links and fake segments (connections to the road graph) are not reportable.
  static std::vector<crowd_traffic::RoadClass> RouteClasses(std::vector<routing::RouteSegment> const & segments);

private:
  void EndTrip();

  std::unique_ptr<crowd_traffic::Client> m_client;
  std::atomic<bool> m_active{false};
  double m_lastRealFixTime = -1.0;

  crowd_traffic::RouteFollower m_follower;
  std::weak_ptr<routing::Route> m_lastRoute;
  std::vector<crowd_traffic::RoadClass> m_routeClasses;
};
