#pragma once

#include "routing/segment.hpp"

#include "routing_common/num_mwm_id.hpp"

#include "geometry/point2d.hpp"
#include "geometry/rect2d.hpp"

#include <functional>
#include <string>
#include <vector>

namespace routing
{
// Maps the geometry of a server route to the cross-mwm transition segments where it crosses the mwm borders, so that
// the router can build the leaps path (start, exit, enter, exit, enter, ..., finish) of the server's way and only
// refine it (IndexRouter::ProcessLeapsJoints) instead of searching the leaps and choosing among candidates.
//
// The borders are found with the country polygons along the polyline (sampled, then vertex by vertex where the mwm
// changes). The packed polygons of the app are simplified, so the transition is searched in a window around each
// crossing: exits of the mwm left behind whose both ends lie on the polyline (within kMaxDistanceM) in the direction
// of travel and whose twin is in the mwm entered.
class ServerRouteMatcher
{
public:
  // What the matcher needs from the routing graph (a fake one in tests).
  class Graph
  {
  public:
    virtual ~Graph() = default;

    // Mwm of a point by the borders of the countries, kFakeNumMwmId outside of the maps that are available,
    // kUnknownMwmId in a gap between the (simplified) borders.
    virtual NumMwmId GetMwmId(m2::PointD const & point) = 0;
    // Calls |fn| once for each exit transition segment of |mwmId| whose feature intersects one of |rects|.
    virtual void ForEachExit(NumMwmId mwmId, std::vector<m2::RectD> const & rects,
                             std::function<void(Segment const &)> const & fn) = 0;
    // Mercator point of the segment's end (|front|) or start.
    virtual m2::PointD GetPoint(Segment const & segment, bool front) = 0;
    // Enter transition segments of the neighbouring mwms that are the same road segment as |exit|.
    virtual void GetTwins(Segment const & exit, std::vector<Segment> & twins) = 0;
  };

  struct Crossing
  {
    Segment m_exit;   // In the mwm left behind.
    Segment m_enter;  // Twin of m_exit in the mwm entered.
    double m_distanceM = 0.0;  // Along the polyline.
  };

  enum class Result
  {
    Ok,
    TooShort,            // Less than two points.
    OutsideMaps,         // A point of the polyline is outside of the available maps.
    TransitionNotFound,  // No transition segment matches a crossing.
  };

  // Graph::GetMwmId() for a point between the borders of the country polygons (they are simplified and don't quite
  // touch): such points count to the mwm of the polyline before them.
  static NumMwmId constexpr kUnknownMwmId = kFakeNumMwmId - 1;

  // A transition matches if both its ends are this close to the polyline.
  static double constexpr kMaxDistanceM = 10.0;
  // The polyline is checked for the mwm every this many meters, then vertex by vertex where it changed.
  static double constexpr kSampleM = 1000.0;
  // Transitions are searched this far along the polyline before and after the crossing found with the polygons.
  static double constexpr kSearchWindowM = 600.0;
  // A crossing into a mwm and back within this distance whose transitions are not found is left out: the simplified
  // polygons may cut a corner that the road doesn't (the refinement then stays in one mwm there).
  static double constexpr kPhantomExcursionM = 3000.0;

  // |polyline|: mercator points of the server route. Fills |crossings| in the order of the route.
  static Result Match(std::vector<m2::PointD> const & polyline, Graph & graph, std::vector<Crossing> & crossings);
};

std::string DebugPrint(ServerRouteMatcher::Result result);
}  // namespace routing
