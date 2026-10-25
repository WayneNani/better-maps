#include "map/crowd_traffic_recorder.hpp"

#include "routing/base/followed_polyline.hpp"

#include "platform/settings.hpp"

#include "geometry/mercator.hpp"

#include "base/logging.hpp"

#include <algorithm>
#include <utility>

using crowd_traffic::RoadClass;

CrowdTrafficRecorder::CrowdTrafficRecorder(std::string const & countersPath)
  : CrowdTrafficRecorder(
        std::make_unique<crowd_traffic::Client>(crowd_traffic::ClientConfig{},
                                                std::make_unique<crowd_traffic::SecureRandom>(), countersPath),
        IsActiveInSettings())
{}

CrowdTrafficRecorder::CrowdTrafficRecorder(std::unique_ptr<crowd_traffic::Client> client, bool active)
  : m_client(std::move(client))
  , m_active(active)
{}

bool CrowdTrafficRecorder::IsActiveInSettings()
{
  bool enabled = false;
  std::string url;
  return settings::Get(kEnabledSetting, enabled) && enabled && settings::Get(kRelayUrlSetting, url) && !url.empty();
}

void CrowdTrafficRecorder::SetActive(bool active)
{
  bool const was = m_active.exchange(active);
  if (was && !active)
  {
    m_client->Discard();
    m_follower.Reset();
    LOG(LINFO, ("Crowd traffic: deactivated, unsent data dropped"));
  }
}

RoadClass CrowdTrafficRecorder::ToRoadClass(ftypes::HighwayClass cls, bool isLink)
{
  if (isLink)
    return RoadClass::None;
  switch (cls)
  {
  case ftypes::HighwayClass::Motorway: return RoadClass::Motorway;
  case ftypes::HighwayClass::Trunk: return RoadClass::Trunk;
  case ftypes::HighwayClass::Primary: return RoadClass::Primary;
  case ftypes::HighwayClass::Secondary: return RoadClass::Secondary;
  case ftypes::HighwayClass::Tertiary: return RoadClass::Tertiary;
  default: return RoadClass::None;
  }
}

void CrowdTrafficRecorder::OnFollowRoute(m2::PointD const & destination,
                                         std::vector<m2::PointD> const & intermediateStops)
{
  if (!m_active)
    return;
  std::vector<crowd_traffic::LatLon> stops;
  for (auto const & p : intermediateStops)
    stops.push_back({mercator::YToLat(p.y), mercator::XToLon(p.x)});
  m_client->StartTrip({mercator::YToLat(destination.y), mercator::XToLon(destination.x)}, stops);
  m_follower.Reset();
  m_lastRealFixTime = -1.0;
  LOG(LINFO, ("Crowd traffic: trip started"));
}

void CrowdTrafficRecorder::EndTrip()
{
  if (!m_client->IsTripActive())
    return;
  m_client->EndTrip();
  m_follower.Reset();
  LOG(LINFO, ("Crowd traffic: trip ended;", DebugPrint(m_client->GetStats())));
}

void CrowdTrafficRecorder::OnNavigationEnded()
{
  EndTrip();
}

void CrowdTrafficRecorder::OnLocation(location::GpsInfo const & raw, routing::RoutingSession const & session,
                                      std::shared_ptr<routing::Route> const & route)
{
  if (!m_active || !m_client->IsTripActive())
    return;
  // Extrapolated positions are for the display only.
  if (raw.m_timestamp != m_lastRealFixTime)
    return;
  if (session.IsFinished() || !session.IsFollowing())
  {
    EndTrip();
    return;
  }
  if (!session.IsOnRoute() || !route || !route->IsValid())
  {
    m_client->MarkGap();
    m_follower.Reset();
    return;
  }

  auto const & poly = route->GetFollowedPolyline();
  auto const iter = poly.GetCurrentIter();
  m2::PointD const rawPoint = mercator::FromLatLon(raw.m_latitude, raw.m_longitude);
  if (!iter.IsValid() || poly.IsFakeSegment(iter.m_ind) ||
      mercator::DistanceOnEarth(iter.m_pt, rawPoint) >= route->GetCurrentRoutingSettings().m_matchingThresholdM)
  {
    // Not matched to the route: the vehicle drives somewhere we do not know.
    m_client->MarkGap();
    m_follower.Reset();
    return;
  }

  auto const & segments = route->GetRouteSegments();
  auto const & line = poly.GetPolyline();
  auto const toLatLon = [](m2::PointD const & p)
  { return crowd_traffic::LatLon{mercator::YToLat(p.y), mercator::XToLon(p.x)}; };
  bool const sameRoute = m_lastRoute.lock() == route;
  auto const samples = m_follower.Next(sameRoute, iter.m_ind, toLatLon(iter.m_pt), raw.m_timestamp,
                                       route->GetCurrentDistanceToEndMeters(), [&](size_t k)
  { return toLatLon(line.GetPoint(std::min(k, line.GetSize() - 1))); }, [&](size_t k)
  {
    if (k >= segments.size())
      return RoadClass::None;
    auto const & rni = segments[k].GetRoadNameInfo();
    return ToRoadClass(rni.m_highwayClass, rni.m_isLink);
  });
  for (auto const & sample : samples)
    m_client->AddSample(sample);
  m_lastRoute = route;
}
