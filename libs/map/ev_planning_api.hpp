#pragma once

#include "ev_planning/charger_status.hpp"

#include "routing/route.hpp"

#include "geometry/point2d.hpp"

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

class DataSource;
class RoutingManager;

namespace routing
{
class NumMwmIds;
}

namespace search
{
class Engine;
class ProcessorHandle;
}  // namespace search

// better-maps: charging planner for electric vehicles along the current route, for the apps.
//
// Profiles, options and results are JSON, see ev_planning/vehicle_profile.hpp and ev_planning/plan_json.hpp.
class EvPlanningApi
{
public:
  // Called on the GUI thread with ev::ToJson(ev::PlanResult) or {"status":"no_route"}.
  using OnResult = std::function<void(std::string const & json)>;
  using RegisterCountryFilesFn = std::function<void(std::shared_ptr<routing::NumMwmIds>)>;

  EvPlanningApi(DataSource const & dataSource, search::Engine & searchEngine, RoutingManager & routingManager,
                RegisterCountryFilesFn registerCountryFiles);
  ~EvPlanningApi();

  // Plans charging stops along the current route, ahead of the current position: search for charging stations,
  // their amenities and the planner run in the background. A new call cancels the previous one. GUI thread.
  void Plan(std::string const & profileJson, std::string const & optionsJson, OnResult onResult);
  void Cancel();

  // Evaluates charging stops with the current route, in which they are intermediate points, e.g. after a plan was
  // added to the route or the user corrected the state of charge during the navigation (|fromCurrentPosition|: from
  // the current position with PlanOptions::m_startSocPct, stops behind it are left out).
  //
  // |stopsJson|: [{"key":"...","title":"...","lat":50.1,"lon":8.2,"powerKw":150,"dc":true,"departureSoc":60}, ...]
  // in the order along the route. Charges more at a stop if the real route needs it. Returns ev::ToJson(ev::Plan)
  // with the stops found on the route, or {"status":"no_route"}. GUI thread.
  std::string Evaluate(std::string const & profileJson, std::string const & optionsJson, std::string const & stopsJson,
                       bool fromCurrentPosition);

  // [ev::VehicleProfile, ...] of the templates.
  static std::string GetVehicleTemplatesJson();

  // Real-time status of the stations, asked before each plan (none by default: no provider exists yet).
  void SetChargerStatusProvider(std::shared_ptr<ev::ChargerStatusProvider> provider);

private:
  struct Route;
  // The route ahead of the current position. GUI thread.
  std::shared_ptr<Route> GetRoute() const;

  DataSource const & m_dataSource;
  search::Engine & m_searchEngine;
  RoutingManager & m_routingManager;
  RegisterCountryFilesFn m_registerCountryFiles;
  std::shared_ptr<ev::ChargerStatusProvider> m_statusProvider;

  // Identifies the current Plan() call, tasks of earlier calls stop.
  std::shared_ptr<std::atomic<uint64_t>> m_currentRequest;
  std::weak_ptr<search::ProcessorHandle> m_searchHandle;
};
