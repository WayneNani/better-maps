#pragma once

#include "map/search_product_info.hpp"

#include "search/opening_hours_filter.hpp"
#include "search/result.hpp"

#include <chrono>
#include <functional>
#include <optional>
#include <string>
#include <vector>

namespace search
{
struct EverywhereSearchParams
{
  using OnResults = std::function<void(Results results, std::vector<ProductInfo> productInfo)>;

  std::string m_query;
  std::string m_inputLocale;
  std::optional<std::chrono::steady_clock::duration> m_timeout;
  bool m_isCategory = false;

  OnResults m_onResults;

  // Filters POI results by their opening hours.
  // Placed after callbacks to keep aggregate initialization of existing fields valid.
  OpeningHoursFilter m_openingHoursFilter = OpeningHoursFilter::Off;
};
}  // namespace search
