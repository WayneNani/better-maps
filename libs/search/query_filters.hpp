#pragma once

#include "search/common.hpp"
#include "search/opening_hours_filter.hpp"

#include <ctime>
#include <functional>
#include <optional>
#include <string>

class CategoriesHolder;

namespace search
{
// Filters recognized in a search query, e.g. "Café offen" -> query "Café ", filter OpenNow.
struct QueryFilters
{
  // The query without the filter words.
  std::string m_query;

  OpeningHoursFilter m_openingHoursFilter = OpeningHoursFilter::Off;

  // Moment to evaluate opening hours at, if the query names a day or a time ("Apotheke Sonntag",
  // "Restaurant heute um 20 Uhr"). Not set if opening hours should be checked for the current moment.
  std::optional<time_t> m_openingHoursTime;
};

std::string DebugPrint(QueryFilters const & filters);

// Returns true if the query (without the filter words) asks for a category, e.g. "Café" or "Apotheke".
using IsCategorialQueryFn = std::function<bool(std::string const & query)>;

// Recognizes opening hours filter words (German and English) in |query|:
// - "offen", "geöffnet", "jetzt offen", "noch geöffnet", "open now", ... -> open now;
// - "heute", "morgen", "Sonntag", "am Sonntag", "sonntags", "on sunday", ... -> open on that day
//   (now for today, otherwise at noon);
// - "um 20 Uhr", "20:30", "bis 22 Uhr", "at 8pm", ... -> open at that time (today or the next day, if the time
//   has passed already), also combined with a day: "Apotheke Sonntag 10 Uhr".
//
// Only whole words are recognized ("Offenbach" stays as it is). Filter words are kept in the query,
// i.e. nullopt is returned, if:
// - nothing else is left in the query ("offen", "Sonntag");
// - the rest of the query is not a category but the filter words are ambiguous: days, times and inflected
//   forms like "offene" may be parts of names (the "Sonntag" place, "Offener Kanal"). Only "offen" and
//   "geöffnet" (also with "jetzt", "noch") and "open now" filter any query, e.g. "Rossmann offen".
//
// |now| is the current moment, days and times are resolved in the device's local time zone like opening hours.
std::optional<QueryFilters> DetectQueryFilters(std::string const & query, time_t now,
                                               IsCategorialQueryFn const & isCategorial);

// Same as above, a query is categorial if any of its words is a category synonym (misprints are allowed)
// in one of |locales| (see CategoriesHolder::MapLocaleToInteger).
std::optional<QueryFilters> DetectQueryFilters(std::string const & query, time_t now,
                                               CategoriesHolder const & categories, Locales const & locales);
}  // namespace search
