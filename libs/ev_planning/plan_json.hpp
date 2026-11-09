#pragma once

#include "ev_planning/planner.hpp"

#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace ev
{
// JSON for the apps: the options come from the user interface, the result goes back to it.
//
// Options: {"startSoc":82,"minArrivalSoc":20,"minStopSoc":10,"maxChargeSoc":100,"priority":"fastest",
//   "preferredProviders":["enbw"],"excludedProviders":[],"minPowerKw":150,"preferredAmenities":["toilets"],
//   "excludedStations":["Germany_Bavaria:123"],"departureTime":1794200000}
// All keys are optional. departureTime 0 means now.
std::optional<PlanOptions> PlanOptionsFromJson(std::string_view json);
std::string ToJson(PlanOptions const & options);

// A charging stop of a plan that the app keeps, e.g. to evaluate it again with the real route.
// [{"key":"Germany_X:12","title":"Ionity Rasthof","lat":50.1,"lon":8.2,"powerKw":150,"dc":true,"departureSoc":60}]
struct StopInput
{
  ChargingStation m_station;
  double m_departureSocPct = 0.0;
};
std::optional<std::vector<StopInput>> StopInputsFromJson(std::string_view json);

// See PlanResultDto in plan_json.cpp for the format.
std::string ToJson(PlanResult const & result);
std::string ToJson(Plan const & plan);
}  // namespace ev
