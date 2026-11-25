#pragma once

// better-maps: indoor maps. Triangulation of polygons with holes by ear clipping (the algorithm of mapbox/earcut,
// written for m2::PointD): holes are bridged into the outer ring, then ears are cut; self-touching and slightly
// broken rings (as they occur in OSM) are handled by filtering, curing local self-intersections and splitting.

#include "geometry/point2d.hpp"

#include <vector>

namespace df::indoor
{
// |polygon|: outer ring first, then holes; open rings in any orientation. Returns the triangles as a list of
// points (three per triangle). Degenerate input gives an empty or partial result, never an endless loop.
std::vector<m2::PointD> Triangulate(std::vector<std::vector<m2::PointD>> const & polygon);
}  // namespace df::indoor
