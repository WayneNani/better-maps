#pragma once

#include "ev_planning/energy_model.hpp"

#include "routing/route.hpp"
#include "routing/segment.hpp"

#include "indexer/mwm_set.hpp"

#include <functional>
#include <map>
#include <memory>
#include <vector>

class DataSource;

namespace feature
{
class AltitudeLoaderCached;
}

namespace ev
{
// Altitudes of route segments from the map data, for routes built without altitudes (the car router does not load
// them). Loads each feature once.
class SegmentAltitudes
{
public:
  using MwmIdGetter = std::function<MwmSet::MwmId(routing::NumMwmId)>;

  SegmentAltitudes(DataSource const & dataSource, MwmIdGetter mwmIdGetter);
  ~SegmentAltitudes();

  // Altitude of the end of |segment| in meters, NaN if unknown.
  double GetEndAltitudeM(routing::Segment const & segment);

private:
  struct Mwm;
  Mwm * GetMwm(routing::NumMwmId numMwmId);

  DataSource const & m_dataSource;
  MwmIdGetter m_mwmIdGetter;
  std::map<routing::NumMwmId, std::unique_ptr<Mwm>> m_mwms;
};

// Altitude of the end of a segment, NaN if unknown.
using AltitudeGetter = std::function<double(routing::RouteSegment const & segment)>;

// Altitude of the junction of the segment, e.g. for routes built with altitudes.
double GetJunctionAltitudeM(routing::RouteSegment const & segment);

// Route points for the energy model from the segments of a route. |startPoint| is the start of the first segment.
// Distances and times are relative to the first segment's start.
std::vector<RoutePoint> MakeRoutePoints(std::vector<routing::RouteSegment> const & segments,
                                        AltitudeGetter const & getAltitude);

RoadKind GetRoadKind(ftypes::HighwayClass highwayClass);
}  // namespace ev
