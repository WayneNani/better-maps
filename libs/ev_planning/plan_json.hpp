#pragma once

#include "ev_planning/planner.hpp"

#include <optional>
#include <string>
#include <string_view>

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

// See PlanResultDto in plan_json.cpp for the format.
std::string ToJson(PlanResult const & result);
std::string ToJson(Plan const & plan);
}  // namespace ev
