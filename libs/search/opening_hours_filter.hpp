#pragma once

#include "editor/yes_no_unknown.hpp"

#include <cstdint>
#include <ctime>
#include <string>

namespace search
{
// Filters POI search results by their opening hours.
// Non-POI results (streets, addresses, localities, coordinates, ...) are never filtered.
enum class OpeningHoursFilter : uint8_t
{
  // Do not filter. Values are used in Java code (JNI), don't change them.
  Off = 0,
  // Keep only POIs which are open now.
  OpenNow = 1,
  // Keep POIs which are open now or have unknown (absent, unparseable) opening hours.
  OpenNowOrUnknown = 2,
};

std::string DebugPrint(OpeningHoursFilter filter);

struct OpeningHoursStatus
{
  osm::YesNoUnknown m_isOpen = osm::Unknown;
  // Valid only if |m_isOpen| is not osm::Unknown.
  time_t m_minutesUntilOpen = 0;
  time_t m_minutesUntilClosed = 0;
};

// Evaluates OSM opening_hours value |openingHours| at the |time| moment (in device's local time).
OpeningHoursStatus GetOpeningHoursStatus(std::string const & openingHours, time_t time);

// Returns true if a POI with |isOpen| status should be kept in results with |filter|.
bool PassesOpeningHoursFilter(OpeningHoursFilter filter, osm::YesNoUnknown isOpen);
}  // namespace search
