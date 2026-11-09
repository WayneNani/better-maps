#include "map/ev_planning_api.hpp"

#include "map/routing_manager.hpp"

#include "ev_planning/candidates.hpp"
#include "ev_planning/energy_model.hpp"
#include "ev_planning/plan_json.hpp"
#include "ev_planning/planner.hpp"
#include "ev_planning/route_points.hpp"
#include "ev_planning/vehicle_profile.hpp"

#include "search/engine.hpp"
#include "search/result.hpp"
#include "search/route_corridor.hpp"

#include "routing_common/num_mwm_id.hpp"

#include "indexer/data_source.hpp"

#include "platform/platform.hpp"

#include "geometry/mercator.hpp"

#include "base/logging.hpp"
#include "base/timer.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <future>
#include <utility>

namespace
{
std::string const kNoRouteJson = R"({"status":"no_route"})";
std::string const kInvalidJson = R"({"status":"invalid_profile"})";
// The search along a long route takes a while on slow devices.
auto constexpr kSearchTimeout = std::chrono::seconds(30);
// A charging stop matches an intermediate point of the route within this distance.
double constexpr kStopMatchM = 1000.0;
}  // namespace

struct EvPlanningApi::Route
{
  std::vector<routing::RouteSegment> m_segments;
  // From the route start.
  std::vector<double> m_subrouteEndsM;
  std::vector<m2::PointD> m_subrouteEndPoints;
  double m_passedM = 0.0;
  std::shared_ptr<search::RouteCorridor const> m_corridor;
  std::shared_ptr<routing::NumMwmIds> m_numMwmIds;

  // Points ahead of the current position, distances and times from it.
  std::vector<ev::RoutePoint> MakePoints(DataSource const & dataSource) const
  {
    auto const numMwmIds = m_numMwmIds;
    ev::SegmentAltitudes altitudes(dataSource, [&dataSource, numMwmIds](routing::NumMwmId id)
    {
      if (!numMwmIds->ContainsFileForMwm(id))
        return MwmSet::MwmId();
      return dataSource.GetMwmIdByCountryFile(numMwmIds->GetFile(id));
    });
    auto const all = ev::MakeRoutePoints(m_segments, [&altitudes](routing::RouteSegment const & s)
    { return altitudes.GetEndAltitudeM(s.GetSegment()); });

    std::vector<ev::RoutePoint> ahead;
    for (size_t i = 0; i < all.size(); ++i)
    {
      if (all[i].m_distanceM < m_passedM)
        continue;
      if (ahead.empty() && i > 0)
      {
        // The current position between two points.
        auto const & a = all[i - 1];
        auto const & b = all[i];
        double const t =
            b.m_distanceM > a.m_distanceM ? (m_passedM - a.m_distanceM) / (b.m_distanceM - a.m_distanceM) : 0.0;
        ev::RoutePoint start = a;
        start.m_distanceM = m_passedM;
        start.m_timeSec = a.m_timeSec + (b.m_timeSec - a.m_timeSec) * t;
        ahead.push_back(start);
      }
      ahead.push_back(all[i]);
    }
    if (ahead.empty())
      return ahead;
    double const startDistance = ahead.front().m_distanceM;
    double const startTime = ahead.front().m_timeSec;
    for (auto & p : ahead)
    {
      p.m_distanceM -= startDistance;
      p.m_timeSec -= startTime;
    }
    return ahead;
  }
};

EvPlanningApi::EvPlanningApi(DataSource const & dataSource, search::Engine & searchEngine,
                             RoutingManager & routingManager, RegisterCountryFilesFn registerCountryFiles)
  : m_dataSource(dataSource)
  , m_searchEngine(searchEngine)
  , m_routingManager(routingManager)
  , m_registerCountryFiles(std::move(registerCountryFiles))
  , m_currentRequest(std::make_shared<std::atomic<uint64_t>>(0))
{}

EvPlanningApi::~EvPlanningApi()
{
  Cancel();
}

std::shared_ptr<EvPlanningApi::Route> EvPlanningApi::GetRoute() const
{
  if (m_routingManager.GetRouter() != routing::RouterType::Vehicle)
    return nullptr;

  auto route = std::make_shared<Route>();
  auto const & session = m_routingManager.RoutingSession();
  if (!session.IsRouteValid() || !session.GetRouteSegments(route->m_segments, route->m_subrouteEndsM, route->m_passedM))
    return nullptr;
  if (route->m_segments.empty())
    return nullptr;

  for (double const end : route->m_subrouteEndsM)
  {
    auto const it =
        std::find_if(route->m_segments.begin(), route->m_segments.end(),
                     [end](routing::RouteSegment const & s) { return s.GetDistFromBeginningMeters() >= end; });
    route->m_subrouteEndPoints.push_back(it == route->m_segments.end()
                                             ? route->m_segments.back().GetJunction().GetPoint()
                                             : it->GetJunction().GetPoint());
  }

  route->m_corridor = m_routingManager.MakeSearchCorridor(ev::kStationCorridorHalfWidthM);
  if (!route->m_corridor || route->m_corridor->IsEmpty())
    return nullptr;

  route->m_numMwmIds = std::make_shared<routing::NumMwmIds>();
  m_registerCountryFiles(route->m_numMwmIds);
  return route;
}

void EvPlanningApi::Cancel()
{
  ++*m_currentRequest;
  if (auto handle = m_searchHandle.lock())
    handle->Cancel();
}

void EvPlanningApi::Plan(std::string const & profileJson, std::string const & optionsJson, OnResult onResult)
{
  Cancel();
  uint64_t const requestId = *m_currentRequest;

  auto const profile = ev::VehicleProfileFromJson(profileJson);
  auto const options = ev::PlanOptionsFromJson(optionsJson);
  if (!profile || !options || !profile->IsValid())
  {
    onResult(kInvalidJson);
    return;
  }

  auto const route = GetRoute();
  if (!route)
  {
    onResult(kNoRouteJson);
    return;
  }

  auto params =
      ev::MakeStationSearchParams(route->m_corridor, ev::MakeChargingStationFilter(*profile, options->m_minPowerKw));
  params.m_timeout = kSearchTimeout;
  auto const timer = std::make_shared<base::Timer>();
  auto current = m_currentRequest;
  auto statusProvider = m_statusProvider;
  auto & dataSource = m_dataSource;
  params.m_onResults = [=, &dataSource, profile = *profile, options = *options](search::Results const & results)
  {
    if (!results.IsEndMarker())
      return;
    if (results.IsEndedCancelled() || *current != requestId)
      return;

    std::vector<search::Result> found(results.begin(), results.end());
    double const searchSec = timer->ElapsedSeconds();
    GetPlatform().RunTask(Platform::Thread::Background, [=, &dataSource, found = std::move(found)]()
    {
      if (*current != requestId)
        return;
      auto stations = ev::MakeCandidates(dataSource, found);
      if (statusProvider)
      {
        // Real-time availability, see ev::ChargerStatusProvider. Waits for the answer: the provider is responsible
        // for a short timeout.
        std::vector<ev::ChargerStatusRequest> requests;
        for (auto const & s : stations)
          requests.push_back({s.m_featureId, s.m_point, s.m_operator});
        std::promise<std::vector<ev::ChargerStatus>> promise;
        auto future = promise.get_future();
        statusProvider->RequestStatuses(
            requests, [&promise](std::vector<ev::ChargerStatus> const & statuses) { promise.set_value(statuses); });
        auto const statuses = future.get();
        for (size_t i = 0; i < stations.size() && i < statuses.size(); ++i)
          stations[i].m_status = statuses[i];
      }
      auto const energy = ev::ComputeRouteEnergy(ev::EnergyModel(profile), route->MakePoints(dataSource));
      ev::AmenityFinder finder(dataSource);
      auto const result = ev::PlanChargingWithAmenities(profile, energy, stations, options, finder);
      LOG(LINFO, ("EV planning:", DebugPrint(result.m_status), result.m_plan.m_stops.size(), "stops,", stations.size(),
                  "stations, search", searchSec, "s, total", timer->ElapsedSeconds(), "s"));
      auto json = ev::ToJson(result);
      GetPlatform().RunTask(Platform::Thread::Gui, [=, json = std::move(json)]()
      {
        if (*current == requestId)
          onResult(json);
      });
    });
  };
  m_searchHandle = m_searchEngine.Search(std::move(params));
}

std::string EvPlanningApi::Evaluate(std::string const & profileJson, std::string const & optionsJson,
                                    std::string const & stopsJson, bool fromCurrentPosition)
{
  auto const profile = ev::VehicleProfileFromJson(profileJson);
  auto const options = ev::PlanOptionsFromJson(optionsJson);
  auto const inputs = ev::StopInputsFromJson(stopsJson);
  if (!profile || !options || !inputs || !profile->IsValid())
    return kInvalidJson;

  auto const route = GetRoute();
  if (!route)
    return kNoRouteJson;
  if (!fromCurrentPosition)
    route->m_passedM = 0.0;

  // The stops are intermediate points of the route: at the end of a subroute (the last one is the finish).
  std::vector<ev::StopTarget> stops;
  size_t nextSubroute = 0;
  for (auto const & input : *inputs)
  {
    size_t best = route->m_subrouteEndPoints.size();
    double bestDistance = kStopMatchM;
    for (size_t i = nextSubroute; i + 1 < route->m_subrouteEndPoints.size(); ++i)
    {
      double const d = mercator::DistanceOnEarth(route->m_subrouteEndPoints[i], input.m_station.m_point);
      if (d < bestDistance)
      {
        bestDistance = d;
        best = i;
      }
    }
    if (best == route->m_subrouteEndPoints.size())
      continue;
    nextSubroute = best + 1;
    double const positionM = route->m_subrouteEndsM[best] - route->m_passedM;
    if (positionM < 0.0)
      continue;  // Already passed.
    ev::StopTarget target{input.m_station, input.m_departureSocPct};
    target.m_station.m_distanceAlongRouteM = positionM;
    target.m_station.m_detourSec = 0.0;
    stops.push_back(std::move(target));
  }

  auto const energy = ev::ComputeRouteEnergy(ev::EnergyModel(*profile), route->MakePoints(m_dataSource));
  return ev::ToJson(ev::EvaluateStopsAdjusting(*profile, energy, std::move(stops), *options));
}

// static
std::string EvPlanningApi::GetVehicleTemplatesJson()
{
  std::string json = "[";
  for (auto const & t : ev::GetVehicleTemplates())
  {
    if (json.size() > 1)
      json += ',';
    json += ev::ToJson(t);
  }
  return json + "]";
}

void EvPlanningApi::SetChargerStatusProvider(std::shared_ptr<ev::ChargerStatusProvider> provider)
{
  m_statusProvider = std::move(provider);
}
