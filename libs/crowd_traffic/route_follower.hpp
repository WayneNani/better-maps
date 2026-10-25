#pragma once

#include "crowd_traffic/client.hpp"
#include "crowd_traffic/protocol.hpp"
#include "crowd_traffic/tiles.hpp"

#include <cstddef>
#include <functional>
#include <vector>

namespace crowd_traffic
{
// Turns the positions of the vehicle on the navigation route (one per location fix) into
// map-matched samples that follow the road: the route vertices passed since the previous fix
// are inserted with times interpolated by distance. Each sample carries the road class of the
// route segment that starts at it.
class RouteFollower
{
public:
  using VertexFn = std::function<LatLon(size_t index)>;
  using ClassFn = std::function<RoadClass(size_t segmentIndex)>;

  // sameRoute: the route did not change since the previous call (no rebuild).
  // segmentIndex: index of the route segment the vehicle is on (from vertex segmentIndex to
  // segmentIndex + 1). point: the position on that segment. remainingM: route distance to the
  // destination from point.
  std::vector<TripSample> Next(bool sameRoute, size_t segmentIndex, LatLon const & point, double time,
                               double remainingM, VertexFn const & vertexAt, ClassFn const & classAt);
  // Forget the previous position (gap in the map matching, new trip).
  void Reset() { m_hasLast = false; }

private:
  bool m_hasLast = false;
  size_t m_lastIndex = 0;
  LatLon m_lastPoint;
  double m_lastTime = 0.0;
};
}  // namespace crowd_traffic
