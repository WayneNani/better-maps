#pragma once

#include "ev_planning/station.hpp"

#include "routing/route.hpp"

#include "indexer/ftypes_matcher.hpp"

#include "geometry/point2d.hpp"

#include <cstddef>
#include <optional>
#include <vector>

namespace routing
{
class IRouter;
class RouterDelegate;
}  // namespace routing

namespace ev
{
// The real extra travel of a visit to a charging station from the route.
struct Detour
{
  double m_timeSec = 0.0;
  double m_distanceM = 0.0;
};

// Computes the real detours to charging stations, see PlanCharging().
//
// The search along the route estimates a detour from the distance of the station to the route as the crow flies.
// That is good for most stations, but not for motorway services that serve one direction only (e.g. "Rasthof X
// Nord"): the services on the other carriageway are as close to the route, but reachable only by turning at the next
// junction and again after the stop, often 20 km more.
class DetourProvider
{
public:
  virtual ~DetourProvider() = default;

  // nullopt if the detour can't be computed (no route, cancelled): the estimate stays.
  virtual std::optional<Detour> GetDetour(ChargingStation const & station) = 0;

  // A better estimate of the detour than the one of the search, cheap enough for all stations, so that the planner
  // rarely chooses a station whose real detour is much longer. nullopt: the estimate of the search stays.
  virtual std::optional<double> EstimateDetourSec(ChargingStation const & /* station */) { return {}; }
};

// The route as a line, with distances and travel times from the start of the planning (where the search corridor
// starts, see ChargingStation::m_distanceAlongRouteM).
class RouteLine
{
public:
  RouteLine() = default;
  // |highwayClasses|: of the road from the previous point to each point (the first one is not used), optional.
  RouteLine(std::vector<m2::PointD> points, std::vector<double> distancesM, std::vector<double> timesSec,
            std::vector<ftypes::HighwayClass> highwayClasses = {});

  // The junctions of the segments (with |routeStart| before them) ahead of |passedM| (distance from the route start),
  // distances and times from there.
  static RouteLine FromSegments(std::vector<routing::RouteSegment> const & segments, double passedM,
                                m2::PointD const & routeStart);

  bool IsEmpty() const { return m_points.size() < 2; }
  double GetLengthM() const { return m_distancesM.empty() ? 0.0 : m_distancesM.back(); }

  // Linear interpolation, clamped to the line.
  m2::PointD GetPointAt(double distanceM) const;
  double GetTimeSecAt(double distanceM) const;
  // Direction of travel at |distanceM| (not normalized), zero for an empty line.
  m2::PointD GetDirectionAt(double distanceM) const;
  ftypes::HighwayClass GetHighwayClassAt(double distanceM) const;

private:
  size_t FindSegment(double distanceM) const;

  std::vector<m2::PointD> m_points;
  std::vector<double> m_distancesM;
  std::vector<double> m_timesSec;
  std::vector<ftypes::HighwayClass> m_highwayClasses;
};

// Detours computed with a router: a route from a point of the route kApproachM before the station (with the direction
// of travel, so that it starts on the carriageway of the route) via the station to a point kReturnM after it, minus
// the time and length of the route between both points. Services on the other carriageway get their real detour:
// on to the next junction, back, and the same after the stop.
//
// Not thread-safe (as the router).
class RouterDetours : public DetourProvider
{
public:
  // Long enough to leave the route at an exit before the station (e.g. an "Autohof" 1-2 km from the motorway).
  static double constexpr kApproachM = 3000.0;
  static double constexpr kReturnM = 3000.0;

  // EstimateDetourSec(): leaving a motorway or trunk road for a station and coming back takes this much more than the
  // way to the station suggests (slow link roads, deceleration). The router gives 2.5-3 min for motorway services
  // next to the route.
  static double constexpr kLeaveFastRoadSec = 150.0;
  // A station on the other side of a motorway (left in right-hand traffic) is either at services of the other
  // carriageway (20-25 min on the next junctions) or at an exit, across the motorway (5-10 min). A guess between,
  // until the router knows.
  static double constexpr kOtherSideOfMotorwaySec = 10.0 * 60.0;
  // Closer stations are on the route itself (no side).
  static double constexpr kOnRouteM = 30.0;

  RouterDetours(routing::IRouter & router, routing::RouterDelegate const & delegate, RouteLine line);

  std::optional<Detour> GetDetour(ChargingStation const & station) override;
  std::optional<double> EstimateDetourSec(ChargingStation const & station) override;

  size_t GetRoutesCount() const { return m_routesCount; }
  double GetRoutingSec() const { return m_routingSec; }

private:
  routing::IRouter & m_router;
  routing::RouterDelegate const & m_delegate;
  RouteLine m_line;
  size_t m_routesCount = 0;
  double m_routingSec = 0.0;
};
}  // namespace ev
