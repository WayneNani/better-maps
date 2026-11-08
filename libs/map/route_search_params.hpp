#pragma once

#include "search/opening_hours_filter.hpp"
#include "search/result.hpp"
#include "search/route_corridor.hpp"
#include "search/search_params.hpp"

#include <chrono>
#include <functional>
#include <memory>
#include <optional>
#include <string>

namespace search
{
// Search along a route, see search::Mode::Route and RoutingManager::MakeSearchCorridor().
struct RouteSearchParams
{
  using OnResults = std::function<void(Results results)>;

  std::string m_query;
  std::string m_inputLocale;
  std::optional<std::chrono::steady_clock::duration> m_timeout;
  bool m_isCategory = false;

  std::shared_ptr<RouteCorridor const> m_corridor;

  // Evaluated at the estimated arrival at each place.
  OpeningHoursFilter m_openingHoursFilter = OpeningHoursFilter::Off;

  // See SearchParams::m_poiFilter, e.g. for charging stations with a certain socket.
  SearchParams::PoiFilter m_poiFilter;

  // Called on the UI thread with all results found so far.
  OnResults m_onResults;
};
}  // namespace search
