#pragma once

#include "crowd_traffic/protocol.hpp"
#include "crowd_traffic/tiles.hpp"

#include <vector>

// Cuts a map-matched trace into tile traversals (docs/CROWD_TRAFFIC_PRIVACY.md, 13.2). Port of
// Segment() in services/crowd-traffic/internal/client/segment.go, but incremental: points are
// added one by one while driving and every traversal is returned as soon as the vehicle leaves
// the tile.
namespace crowd_traffic
{
// A map-matched position. m_class is the class of the road between this point and the next one
// (RoadClass::None for roads that are never reported and for unmatched stretches).
struct TracePoint
{
  double m_lat = 0.0;
  double m_lon = 0.0;
  // Unix time in seconds.
  double m_time = 0.0;
  RoadClass m_class = RoadClass::None;
};

// One pass through a tile from one edge to another.
struct Traversal
{
  Tile m_tile;
  Edge m_entry;
  Edge m_exit;
  // RoadClass::None if the road class changed inside the tile.
  RoadClass m_class = RoadClass::None;
  double m_tIn = 0.0;
  double m_tOut = 0.0;
  double m_lengthM = 0.0;
  // Distance driven since the start of the trace at entry and exit, and the entry and exit
  // points. Used on the device for trimming only, never sent.
  double m_distIn = 0.0;
  double m_distOut = 0.0;
  LatLon m_in;
  LatLon m_out;

  double SpeedKmh() const;
  SegmentKey Key() const { return {m_tile, m_entry.m_side, m_exit.m_side, m_class}; }
};

struct GridCrossing
{
  // Position on the piece A→B, in [0, 1].
  double m_t = 0.0;
  // Crossing of a line x = const.
  bool m_vertical = false;
  // Moving towards larger pixel coordinates.
  bool m_forward = false;
};

// Grid line crossings between the pixel positions a and b, ordered along the way (vertical lines
// first on ties). A point exactly on a grid line belongs to the tile east or south of it.
std::vector<GridCrossing> GridCrossings(PixelPoint const & a, PixelPoint const & b);

class Segmenter
{
public:
  // Adds the next point and appends the traversals completed on the way from the previous point.
  void Add(TracePoint const & point, std::vector<Traversal> & out);
  // The stretch from the last point to the next one is unknown (map matching lost): the tile in
  // which it starts and every tile it touches are not reported.
  void MarkGap();
  void Reset() { *this = Segmenter(); }

  bool HasPoints() const { return m_hasPrev; }
  TracePoint const & GetLastPoint() const { return m_prev; }
  // Distance driven along the trace so far, in metres.
  double GetDistanceM() const { return m_dist; }

private:
  void AddLength(LatLon const & p);

  bool m_hasPrev = false;
  TracePoint m_prev;
  Tile m_cur;
  bool m_entered = false;
  Edge m_entry;
  double m_tIn = 0.0;
  double m_length = 0.0;
  double m_dist = 0.0;
  double m_distIn = 0.0;
  RoadClass m_class = RoadClass::None;
  bool m_mixed = false;
  LatLon m_in;
  LatLon m_last;
};

// Segments a complete trace (convenience wrapper used by tests and tools).
std::vector<Traversal> Segment(std::vector<TracePoint> const & points);
}  // namespace crowd_traffic
