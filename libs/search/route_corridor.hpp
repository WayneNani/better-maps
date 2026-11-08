#pragma once

#include "geometry/point2d.hpp"
#include "geometry/rect2d.hpp"

#include <cstddef>
#include <optional>
#include <string>
#include <vector>

namespace search
{
// Where a place lies relative to a route, see RouteCorridor::Locate().
struct RoutePosition
{
  // Estimated travel time from the corridor start to the place itself: along the route and half of the detour.
  double GetTimeToPlaceSec() const { return m_timeAlongRouteSec + m_detourSec / 2.0; }

  // Distance from the place to the nearest point of the route, as the crow flies.
  double m_distanceFromRouteM = 0.0;
  // Distance along the route from the corridor start (e.g. the current position) to that nearest point,
  // i.e. to where one leaves the route for the place.
  double m_distanceAlongRouteM = 0.0;
  // Travel time along the route from the corridor start to that nearest point. Zero if the route has no times.
  double m_timeAlongRouteSec = 0.0;
  // Rough estimate of the extra travel time to leave the route there, visit the place and come back.
  double m_detourSec = 0.0;
};

std::string DebugPrint(RoutePosition const & position);

// The area within a given distance of a route, ahead of a position on the route.
//
// Search along a route (Mode::Route) finds only places inside the corridor and orders them by
// RoutePosition::m_distanceAlongRouteM. Places behind the corridor start (already passed) are not in it.
class RouteCorridor
{
public:
  struct Params
  {
    // Route polyline in mercator.
    std::vector<m2::PointD> m_points;
    // Travel time from the route start to each point of |m_points|, in seconds.
    // Optional: if empty, RoutePosition::m_timeAlongRouteSec is zero.
    std::vector<double> m_timesSec;
    // Places farther from the route are not in the corridor.
    double m_halfWidthM = 1000.0;
    // Distance along the route already travelled. The corridor starts at that point of the route.
    double m_passedDistanceM = 0.0;
    // To estimate RoutePosition::m_detourSec: speed on the way from the route to a place and back,
    // and how much longer that way is than the straight line.
    double m_detourSpeedMps = 40.0 / 3.6;
    double m_detourCircuity = 1.4;
  };

  // A place behind the corridor start by less than this is still in the corridor, e.g. a fuel station the
  // vehicle is just passing by.
  static double constexpr kBehindStartToleranceM = 30.0;
  // A place near several parts of the route (e.g. a road used twice) is located at the first of them, if it is
  // at most this much farther from it than from the nearest part.
  static double constexpr kFirstPassToleranceM = 50.0;

  explicit RouteCorridor(Params const & params);

  // True if the corridor has no route ahead, e.g. the route is already completed.
  bool IsEmpty() const { return m_points.size() < 2; }

  double GetHalfWidthM() const { return m_halfWidthM; }
  // Length of the route inside the corridor, from the corridor start to the route end.
  double GetLengthM() const { return m_distancesM.empty() ? 0.0 : m_distancesM.back(); }
  // Points of the route inside the corridor, from the corridor start to the route end.
  std::vector<m2::PointD> const & GetPoints() const { return m_points; }

  m2::RectD const & GetLimitRect() const { return m_limitRect; }
  // Rectangles which cover the whole corridor, e.g. to retrieve its features from the map index.
  std::vector<m2::RectD> GetCoveringRects() const;

  // Returns nullopt if |point| is outside the corridor: too far from the route or behind the corridor start.
  std::optional<RoutePosition> Locate(m2::PointD const & point) const;

private:
  // Consecutive route segments [m_begin, m_end) and their bounding rect, inflated by the corridor half width.
  struct Chunk
  {
    m2::RectD m_rect;
    size_t m_begin = 0;
    size_t m_end = 0;
  };

  void BuildChunks();

  std::vector<m2::PointD> m_points;
  // Distance and travel time from the corridor start to each point of |m_points|.
  std::vector<double> m_distancesM;
  std::vector<double> m_timesSec;
  std::vector<Chunk> m_chunks;
  m2::RectD m_limitRect;

  double m_halfWidthM = 0.0;
  double m_detourSpeedMps = 0.0;
  double m_detourCircuity = 0.0;
};

// Selects at most |limit| places spread along a route of length |lengthM|, so that the many places of a town do not
// displace all places in the rest of the route: the route is split into equal parts, which give one place each in
// turn. Within a part, places are taken in increasing order of |priorities| (smaller is better).
//
// |distancesAlongRouteM| and |priorities| describe the places and have the same size.
// Returns indices of the selected places, ordered by distance along the route.
std::vector<size_t> SpreadAlongRoute(std::vector<double> const & distancesAlongRouteM,
                                     std::vector<double> const & priorities, double lengthM, size_t limit);
}  // namespace search
