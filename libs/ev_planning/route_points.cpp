#include "ev_planning/route_points.hpp"

#include "indexer/altitude_loader.hpp"
#include "indexer/data_source.hpp"
#include "indexer/feature.hpp"

#include "geometry/point_with_altitude.hpp"

#include <cmath>

namespace ev
{
struct SegmentAltitudes::Mwm
{
  explicit Mwm(DataSource const & dataSource, MwmSet::MwmId const & id) : m_guard(dataSource, id)
  {
    if (auto const * value = m_guard.GetHandle().GetValue())
      m_loader = std::make_unique<feature::AltitudeLoaderCached>(*value);
  }

  FeaturesLoaderGuard m_guard;
  std::unique_ptr<feature::AltitudeLoaderCached> m_loader;
  std::map<uint32_t, geometry::Altitudes> m_altitudes;
};

SegmentAltitudes::SegmentAltitudes(DataSource const & dataSource, MwmIdGetter mwmIdGetter)
  : m_dataSource(dataSource)
  , m_mwmIdGetter(std::move(mwmIdGetter))
{}

SegmentAltitudes::~SegmentAltitudes() = default;

SegmentAltitudes::Mwm * SegmentAltitudes::GetMwm(routing::NumMwmId numMwmId)
{
  auto it = m_mwms.find(numMwmId);
  if (it == m_mwms.end())
  {
    auto const id = m_mwmIdGetter(numMwmId);
    std::unique_ptr<Mwm> mwm;
    if (id.IsAlive())
      mwm = std::make_unique<Mwm>(m_dataSource, id);
    it = m_mwms.emplace(numMwmId, std::move(mwm)).first;
  }
  return it->second.get();
}

double SegmentAltitudes::GetEndAltitudeM(routing::Segment const & segment)
{
  // Fake segments from the start or to the finish point.
  if (!segment.IsRealSegment())
    return kUnknownAltitude;
  auto * mwm = GetMwm(segment.GetMwmId());
  if (!mwm || !mwm->m_loader || !mwm->m_loader->HasAltitudes())
    return kUnknownAltitude;

  auto it = mwm->m_altitudes.find(segment.GetFeatureId());
  if (it == mwm->m_altitudes.end())
  {
    geometry::Altitudes altitudes;
    if (auto ft = mwm->m_guard.GetFeatureByIndex(segment.GetFeatureId()))
    {
      ft->ParseGeometry(FeatureType::BEST_GEOMETRY);
      altitudes = mwm->m_loader->GetAltitudes(segment.GetFeatureId(), ft->GetPointsCount());
    }
    it = mwm->m_altitudes.emplace(segment.GetFeatureId(), std::move(altitudes)).first;
  }

  uint32_t const pointId = segment.GetPointId(true /* front */);
  if (pointId >= it->second.size())
    return kUnknownAltitude;
  return it->second[pointId];
}

double GetJunctionAltitudeM(routing::RouteSegment const & segment)
{
  auto const altitude = segment.GetJunction().GetAltitude();
  return altitude == geometry::kInvalidAltitude ? kUnknownAltitude : static_cast<double>(altitude);
}

RoadKind GetRoadKind(ftypes::HighwayClass highwayClass)
{
  using ftypes::HighwayClass;
  switch (highwayClass)
  {
  case HighwayClass::Motorway:
  case HighwayClass::Trunk: return RoadKind::Fast;
  case HighwayClass::Primary:
  case HighwayClass::Secondary:
  case HighwayClass::Tertiary: return RoadKind::Main;
  case HighwayClass::Undefined: return RoadKind::Unknown;
  default: return RoadKind::Minor;
  }
}

std::vector<RoutePoint> MakeRoutePoints(std::vector<routing::RouteSegment> const & segments,
                                        AltitudeGetter const & getAltitude)
{
  std::vector<RoutePoint> points;
  if (segments.empty())
    return points;
  points.reserve(segments.size() + 1);

  // The start of the first segment: same altitude as its end is good enough.
  RoutePoint start;
  start.m_altitudeM = getAltitude ? getAltitude(segments.front()) : kUnknownAltitude;
  points.push_back(start);

  RoadKind kind = RoadKind::Unknown;
  for (auto const & s : segments)
  {
    // The road class is set on the first segment of each road only.
    auto const segmentKind = GetRoadKind(s.GetRoadNameInfo().m_highwayClass);
    if (segmentKind != RoadKind::Unknown)
      kind = segmentKind;

    RoutePoint p;
    p.m_distanceM = s.GetDistFromBeginningMeters();
    p.m_timeSec = s.GetTimeFromBeginningSec();
    p.m_altitudeM = getAltitude ? getAltitude(s) : kUnknownAltitude;
    p.m_roadKind = kind;
    points.push_back(p);
  }
  return points;
}
}  // namespace ev
