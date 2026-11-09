#include "ev_planning/detours.hpp"

#include "ev_planning/route_points.hpp"

#include "routing/checkpoints.hpp"
#include "routing/router.hpp"
#include "routing/router_delegate.hpp"
#include "routing/routing_callbacks.hpp"

#include "indexer/feature_meta.hpp"
#include "indexer/mwm_set.hpp"

#include "geometry/mercator.hpp"

#include "base/assert.hpp"
#include "base/logging.hpp"
#include "base/timer.hpp"

#include <algorithm>
#include <utility>

namespace ev
{
RouteLine::RouteLine(std::vector<m2::PointD> points, std::vector<double> distancesM, std::vector<double> timesSec,
                     std::vector<ftypes::HighwayClass> highwayClasses)
  : m_points(std::move(points))
  , m_distancesM(std::move(distancesM))
  , m_timesSec(std::move(timesSec))
  , m_highwayClasses(std::move(highwayClasses))
{
  CHECK_EQUAL(m_points.size(), m_distancesM.size(), ());
  CHECK_EQUAL(m_points.size(), m_timesSec.size(), ());
  CHECK(m_highwayClasses.empty() || m_highwayClasses.size() == m_points.size(), ());
}

// static
RouteLine RouteLine::FromSegments(std::vector<routing::RouteSegment> const & segments, double passedM,
                                  m2::PointD const & routeStart)
{
  // The junctions with the route start before them (distance and time 0).
  struct Junction
  {
    m2::PointD m_point;
    double m_distanceM = 0.0;
    double m_timeSec = 0.0;
    ftypes::HighwayClass m_class = ftypes::HighwayClass::Undefined;
  };
  std::vector<Junction> junctions;
  junctions.reserve(segments.size() + 1);
  junctions.push_back({routeStart, 0.0, 0.0, ftypes::HighwayClass::Undefined});
  // The road class is set on the first segment of each road only.
  auto highwayClass = ftypes::HighwayClass::Undefined;
  for (auto const & s : segments)
  {
    if (s.GetRoadNameInfo().m_highwayClass != ftypes::HighwayClass::Undefined)
      highwayClass = s.GetRoadNameInfo().m_highwayClass;
    junctions.push_back(
        {s.GetJunction().GetPoint(), s.GetDistFromBeginningMeters(), s.GetTimeFromBeginningSec(), highwayClass});
  }
  if (!segments.empty())
    junctions.front().m_class = junctions[1].m_class;

  std::vector<m2::PointD> points;
  std::vector<double> distances;
  std::vector<double> times;
  std::vector<ftypes::HighwayClass> classes;
  double startTime = 0.0;
  for (size_t i = 0; i < junctions.size(); ++i)
  {
    auto const & j = junctions[i];
    if (j.m_distanceM < passedM)
      continue;
    if (points.empty() && i > 0)
    {
      // The current position between two junctions.
      auto const & prev = junctions[i - 1];
      double const length = j.m_distanceM - prev.m_distanceM;
      double const t = length > 0.0 ? (passedM - prev.m_distanceM) / length : 0.0;
      startTime = prev.m_timeSec + (j.m_timeSec - prev.m_timeSec) * t;
      points.push_back(prev.m_point + (j.m_point - prev.m_point) * t);
      distances.push_back(0.0);
      times.push_back(0.0);
      classes.push_back(j.m_class);
    }
    else if (points.empty())
    {
      startTime = j.m_timeSec;
    }
    points.push_back(j.m_point);
    distances.push_back(j.m_distanceM - passedM);
    times.push_back(j.m_timeSec - startTime);
    classes.push_back(j.m_class);
  }
  return RouteLine(std::move(points), std::move(distances), std::move(times), std::move(classes));
}

size_t RouteLine::FindSegment(double distanceM) const
{
  // The segment [i - 1, i] that contains |distanceM|.
  CHECK(!IsEmpty(), ());
  auto const it = std::lower_bound(m_distancesM.begin(), m_distancesM.end(), distanceM);
  size_t const i = static_cast<size_t>(it - m_distancesM.begin());
  return std::clamp<size_t>(i, 1, m_points.size() - 1);
}

m2::PointD RouteLine::GetPointAt(double distanceM) const
{
  if (m_points.empty())
    return {};
  if (IsEmpty())
    return m_points.front();
  distanceM = std::clamp(distanceM, 0.0, GetLengthM());
  size_t const i = FindSegment(distanceM);
  double const length = m_distancesM[i] - m_distancesM[i - 1];
  double const t = length > 0.0 ? (distanceM - m_distancesM[i - 1]) / length : 0.0;
  return m_points[i - 1] + (m_points[i] - m_points[i - 1]) * t;
}

double RouteLine::GetTimeSecAt(double distanceM) const
{
  if (IsEmpty())
    return 0.0;
  distanceM = std::clamp(distanceM, 0.0, GetLengthM());
  size_t const i = FindSegment(distanceM);
  double const length = m_distancesM[i] - m_distancesM[i - 1];
  double const t = length > 0.0 ? (distanceM - m_distancesM[i - 1]) / length : 0.0;
  return m_timesSec[i - 1] + (m_timesSec[i] - m_timesSec[i - 1]) * t;
}

m2::PointD RouteLine::GetDirectionAt(double distanceM) const
{
  if (IsEmpty())
    return {};
  distanceM = std::clamp(distanceM, 0.0, GetLengthM());
  // The first segment with a length from there on (junctions may coincide).
  for (size_t i = FindSegment(distanceM); i < m_points.size(); ++i)
    if (!m_points[i].EqualDxDy(m_points[i - 1], 1e-9))
      return m_points[i] - m_points[i - 1];
  return {};
}

ftypes::HighwayClass RouteLine::GetHighwayClassAt(double distanceM) const
{
  if (IsEmpty() || m_highwayClasses.empty())
    return ftypes::HighwayClass::Undefined;
  return m_highwayClasses[FindSegment(std::clamp(distanceM, 0.0, GetLengthM()))];
}

RouterDetours::RouterDetours(routing::IRouter & router, routing::RouterDelegate const & delegate, RouteLine line)
  : m_router(router)
  , m_delegate(delegate)
  , m_line(std::move(line))
{}

std::optional<Detour> RouterDetours::GetDetour(ChargingStation const & station)
{
  if (m_line.IsEmpty() || m_delegate.IsCancelled())
    return {};

  double const length = m_line.GetLengthM();
  double const position = std::clamp(station.m_distanceAlongRouteM, 0.0, length);
  double const fromM = std::max(0.0, position - kApproachM);
  double const toM = std::min(length, position + kReturnM);

  base::Timer timer;
  routing::Checkpoints checkpoints({m_line.GetPointAt(fromM), station.m_point, m_line.GetPointAt(toM)});
  routing::Route route("" /* router */, 0 /* routeId */);
  auto const code = m_router.CalculateRoute(checkpoints, m_line.GetDirectionAt(fromM), false /* adjustToPrevRoute */,
                                            m_delegate, route);
  ++m_routesCount;
  m_routingSec += timer.ElapsedSeconds();
  if (code != routing::RouterResultCode::NoError || !route.IsValid())
  {
    LOG(LINFO, ("No route to the station", station.GetTitle(), code));
    return {};
  }

  Detour detour;
  detour.m_timeSec = std::max(0.0, route.GetTotalTimeSec() - (m_line.GetTimeSecAt(toM) - m_line.GetTimeSecAt(fromM)));
  detour.m_distanceM = std::max(0.0, route.GetTotalDistanceMeters() - (toM - fromM));
  return detour;
}
}  // namespace ev

namespace ev
{
std::optional<double> RouterDetours::EstimateDetourSec(ChargingStation const & station)
{
  if (m_line.IsEmpty())
    return {};
  double const position = std::clamp(station.m_distanceAlongRouteM, 0.0, m_line.GetLengthM());
  auto const highwayClass = m_line.GetHighwayClassAt(position);
  if (GetRoadKind(highwayClass) != RoadKind::Fast)
    return {};

  double estimate = station.m_detourSec + kLeaveFastRoadSec;
  if (highwayClass == ftypes::HighwayClass::Motorway)
  {
    // The side of the station: the cross product of the direction of travel and the way to the station is positive
    // on the left.
    auto const direction = m_line.GetDirectionAt(position);
    auto const toStation = station.m_point - m_line.GetPointAt(position);
    bool const left = m2::CrossProduct(direction, toStation) > 0.0;
    bool leftHandTraffic = false;
    if (auto const & info = station.m_featureId.m_mwmId.GetInfo())
      leftHandTraffic = info->GetRegionData().Get(feature::RegionData::RD_DRIVING) == "l";
    // Stations right next to the motorway, e.g. at services, are on one side only.
    if (left != leftHandTraffic && station.m_distanceFromRouteM > kOnRouteM)
      estimate += kOtherSideOfMotorwaySec;
  }
  return estimate;
}
}  // namespace ev
