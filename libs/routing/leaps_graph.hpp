#pragma once

#include "routing/base/astar_graph.hpp"
#include "routing/mwm_hierarchy_handler.hpp"
#include "routing/route_weight.hpp"
#include "routing/segment.hpp"

#include "geometry/latlon.hpp"

#include <utility>
#include <vector>

#include "3party/ankerl/unordered_dense.h"

namespace routing
{
class IndexGraphStarter;

class LeapsGraph : public AStarGraph<Segment, SegmentEdge, RouteWeight>
{
public:
  LeapsGraph(IndexGraphStarter & starter, MwmHierarchyHandler && hierarchyHandler);

  // AStarGraph overrides:
  // @{
  void GetOutgoingEdgesList(astar::VertexData<Vertex, Weight> const & vertexData, EdgeListT & edges) override;
  void GetIngoingEdgesList(astar::VertexData<Vertex, Weight> const & vertexData, EdgeListT & edges) override;
  RouteWeight HeuristicCostEstimate(Segment const & from, Segment const & to) override;
  RouteWeight GetAStarWeightEpsilon() override;
  // @}

  Segment const & GetStartSegment() const { return m_startSegment; }
  Segment const & GetFinishSegment() const { return m_finishSegment; }
  ms::LatLon const & GetPoint(Segment const & segment, bool front) const;

  RouteWeight CalcMiddleCrossMwmWeight(std::vector<Segment> const & path);

private:
  void GetEdgesList(Segment const & segment, bool isOutgoing, EdgeListT & edges);

  void GetEdgesListFromStart(EdgeListT & edges) const;
  void GetEdgesListToFinish(EdgeListT & edges) const;

private:
  ms::LatLon m_startPoint;
  ms::LatLon m_finishPoint;

  Segment m_startSegment;
  Segment m_finishSegment;

  IndexGraphStarter & m_starter;

  MwmHierarchyHandler m_hierarchyHandler;

  // Heuristics of the vertices to the finish and to the start. A* asks for both for each relaxed edge and a
  // transition segment is reached by many edges; computing them takes the geometry of the segment and the
  // distance on the sphere, about half of the time of the search.
  struct SegmentHash
  {
    using is_avalanching = void;
    uint64_t operator()(Segment const & s) const noexcept
    {
      return ankerl::unordered_dense::detail::wyhash::mix(
          (uint64_t{s.GetFeatureId()} << 32) | s.GetSegmentIdx(),
          ((uint64_t{s.GetMwmId()} << 1) | static_cast<uint64_t>(s.IsForward())) ^ 0x9E3779B97F4A7C15ULL);
    }
  };
  // RouteWeight::GetWeight() to the finish and to the start (the heuristics have no other parts).
  ankerl::unordered_dense::map<Segment, std::pair<double, double>, SegmentHash> m_heuristics;
};
}  // namespace routing
