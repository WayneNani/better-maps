#include "crowd_traffic/route_follower.hpp"

namespace crowd_traffic
{
std::vector<TripSample> RouteFollower::Next(bool sameRoute, size_t segmentIndex, LatLon const & point, double time,
                                            double remainingM, VertexFn const & vertexAt, ClassFn const & classAt)
{
  std::vector<TripSample> res;
  if (m_hasLast && sameRoute && time > m_lastTime && segmentIndex > m_lastIndex)
  {
    std::vector<LatLon> pts;
    for (size_t k = m_lastIndex + 1; k <= segmentIndex; ++k)
      pts.push_back(vertexAt(k));
    std::vector<double> dist(pts.size() + 1, 0.0);
    LatLon prev = m_lastPoint;
    for (size_t i = 0; i < pts.size(); ++i)
    {
      dist[i + 1] = dist[i] + DistanceM(prev.m_lat, prev.m_lon, pts[i].m_lat, pts[i].m_lon);
      prev = pts[i];
    }
    double const total = dist.back() + DistanceM(prev.m_lat, prev.m_lon, point.m_lat, point.m_lon);
    for (size_t i = 0; i < pts.size(); ++i)
    {
      double const f = total > 0 ? dist[i + 1] / total : 1.0;
      res.push_back({pts[i].m_lat, pts[i].m_lon, m_lastTime + f * (time - m_lastTime), classAt(m_lastIndex + 1 + i),
                     remainingM + (total - dist[i + 1])});
    }
  }
  res.push_back({point.m_lat, point.m_lon, time, classAt(segmentIndex), remainingM});
  m_hasLast = true;
  m_lastIndex = segmentIndex;
  m_lastPoint = point;
  m_lastTime = time;
  return res;
}
}  // namespace crowd_traffic
