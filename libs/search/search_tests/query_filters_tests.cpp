#include "testing/testing.hpp"

#include "search/common.hpp"
#include "search/query_filters.hpp"

#include "indexer/categories_holder.hpp"
#include "indexer/classificator_loader.hpp"
#include "indexer/search_string_utils.hpp"

#include "base/stl_helpers.hpp"

#include <ctime>
#include <optional>
#include <string>
#include <vector>

namespace query_filters_tests
{
using namespace search;
using namespace std;

// Local time, as opening hours are evaluated in local time. A week without a DST switch.
time_t MakeLocalTime(int day, int hour, int minute = 0)
{
  tm t = {};
  t.tm_year = 2026 - 1900;
  t.tm_mon = 6 - 1;
  t.tm_mday = day;
  t.tm_hour = hour;
  t.tm_min = minute;
  t.tm_isdst = -1;
  return mktime(&t);
}

// Wednesday, 2026-06-17, 20:30.
time_t const kNow = MakeLocalTime(17, 20, 30);
int constexpr kWednesday = 17;
int constexpr kThursday = 18;
int constexpr kFriday = 19;
int constexpr kSunday = 21;
int constexpr kMonday = 22;

// Categories for most tests, the real categories are checked in QueryFilters_RealCategories.
bool IsCategorial(string const & query)
{
  vector<strings::UniString> kCategories;
  for (char const * category : {"Café", "Apotheke", "pharmacy", "Restaurant", "Supermarkt", "Bäckerei"})
    kCategories.push_back(NormalizeAndSimplifyString(category));

  for (auto const & token : NormalizeAndTokenizeString(query))
    if (base::IsExist(kCategories, token))
      return true;
  return false;
}

optional<QueryFilters> Detect(string const & query)
{
  return DetectQueryFilters(query, kNow, IsCategorial);
}

void TestOpenNow(string const & query, string const & expectedQuery)
{
  auto const filters = Detect(query);
  TEST(filters, (query));
  TEST_EQUAL(filters->m_query, expectedQuery, (query));
  TEST_EQUAL(filters->m_openingHoursFilter, OpeningHoursFilter::OpenNow, (query));
  TEST(!filters->m_openingHoursTime, (query, *filters));
}

void TestOpenAt(string const & query, string const & expectedQuery, time_t expectedTime)
{
  auto const filters = Detect(query);
  TEST(filters, (query));
  TEST_EQUAL(filters->m_query, expectedQuery, (query));
  TEST_EQUAL(filters->m_openingHoursFilter, OpeningHoursFilter::OpenNow, (query));
  TEST_EQUAL(filters->m_openingHoursTime, optional<time_t>(expectedTime), (query, *filters));
}

void TestNoFilter(string const & query)
{
  auto const filters = Detect(query);
  TEST(!filters, (query, *filters));
}

UNIT_TEST(QueryFilters_OpenNow)
{
  // The rest of the query becomes a complete token, it was followed by a filter word.
  TestOpenNow("Café offen", "Café ");
  TestOpenNow("Café geöffnet", "Café ");
  TestOpenNow("Café geoeffnet", "Café ");
  TestOpenNow("Café jetzt offen", "Café ");
  TestOpenNow("Café jetzt geöffnet", "Café ");
  TestOpenNow("Café noch offen", "Café ");
  TestOpenNow("Café geöffnet jetzt", "Café ");
  TestOpenNow("cafe open now", "cafe ");
  TestOpenNow("cafe now open", "cafe ");
  TestOpenNow("cafe open", "cafe ");

  // Filter words before the rest keep the last token a prefix.
  TestOpenNow("offen Café", "Café");
  TestOpenNow("jetzt geöffnet Café", "Café");
  TestOpenNow("open now cafe", "cafe");
  TestOpenNow("offene Apotheke", "Apotheke");
  TestOpenNow("geöffnete Apotheke", "Apotheke");
  TestOpenNow("Café offen ", "Café ");

  // Several words are kept as they are.
  TestOpenNow("Italienisches  Restaurant offen", "Italienisches Restaurant ");
  TestOpenNow("Restaurant offen Unter den Linden", "Restaurant Unter den Linden");
}

UNIT_TEST(QueryFilters_CaseAndUmlauts)
{
  TestOpenNow("CAFÉ OFFEN", "CAFÉ ");
  TestOpenNow("Café GeÖffnet", "Café ");
  TestOpenNow("Café GEOEFFNET", "Café ");
  TestOpenNow("cafe Open Now", "cafe ");
  TestOpenNow("Bäckerei offen", "Bäckerei ");
  TestOpenAt("APOTHEKE SONNTAG", "APOTHEKE ", MakeLocalTime(kSunday, 12));
  TestOpenAt("Apotheke übermorgen", "Apotheke ", MakeLocalTime(kFriday, 12));
  TestOpenAt("Apotheke uebermorgen", "Apotheke ", MakeLocalTime(kFriday, 12));
}

UNIT_TEST(QueryFilters_Punctuation)
{
  TestOpenNow("Café, offen", "Café, ");
  TestOpenNow("Café offen!", "Café ");
  TestOpenAt("Apotheke, Sonntag.", "Apotheke, ", MakeLocalTime(kSunday, 12));
}

UNIT_TEST(QueryFilters_Days)
{
  TestOpenAt("Apotheke Sonntag", "Apotheke ", MakeLocalTime(kSunday, 12));
  TestOpenAt("Apotheke am Sonntag", "Apotheke ", MakeLocalTime(kSunday, 12));
  TestOpenAt("Apotheke sonntags", "Apotheke ", MakeLocalTime(kSunday, 12));
  TestOpenAt("Apotheke Sonnabend", "Apotheke ", MakeLocalTime(20, 12));
  TestOpenAt("Sonntag Apotheke", "Apotheke", MakeLocalTime(kSunday, 12));
  TestOpenAt("pharmacy on sunday", "pharmacy ", MakeLocalTime(kSunday, 12));
  TestOpenAt("pharmacy sundays", "pharmacy ", MakeLocalTime(kSunday, 12));
  TestOpenAt("Apotheke Montag", "Apotheke ", MakeLocalTime(kMonday, 12));
  TestOpenAt("Apotheke morgen", "Apotheke ", MakeLocalTime(kThursday, 12));
  TestOpenAt("pharmacy tomorrow", "pharmacy ", MakeLocalTime(kThursday, 12));
  TestOpenAt("Apotheke offen am Sonntag", "Apotheke ", MakeLocalTime(kSunday, 12));

  // Today is now.
  TestOpenNow("Apotheke heute", "Apotheke ");
  TestOpenNow("Apotheke Mittwoch", "Apotheke ");
  TestOpenNow("pharmacy today", "pharmacy ");
}

UNIT_TEST(QueryFilters_Times)
{
  TestOpenAt("Restaurant heute 22 Uhr", "Restaurant ", MakeLocalTime(kWednesday, 22));
  TestOpenAt("Restaurant um 22 Uhr", "Restaurant ", MakeLocalTime(kWednesday, 22));
  TestOpenAt("Restaurant um 22", "Restaurant ", MakeLocalTime(kWednesday, 22));
  TestOpenAt("Restaurant 22:15", "Restaurant ", MakeLocalTime(kWednesday, 22, 15));
  TestOpenAt("Restaurant 22.15 Uhr", "Restaurant ", MakeLocalTime(kWednesday, 22, 15));
  TestOpenAt("Restaurant 22uhr", "Restaurant ", MakeLocalTime(kWednesday, 22));
  TestOpenAt("Restaurant 22h", "Restaurant ", MakeLocalTime(kWednesday, 22));
  TestOpenAt("Restaurant bis 22 Uhr", "Restaurant ", MakeLocalTime(kWednesday, 22));
  TestOpenAt("Restaurant offen bis 24 Uhr", "Restaurant ", MakeLocalTime(kWednesday, 23, 59));
  TestOpenAt("restaurant at 9pm", "restaurant ", MakeLocalTime(kWednesday, 21));
  TestOpenAt("restaurant at 9 pm", "restaurant ", MakeLocalTime(kWednesday, 21));
  TestOpenAt("restaurant 9:30pm", "restaurant ", MakeLocalTime(kWednesday, 21, 30));
  TestOpenAt("restaurant 12am", "restaurant ", MakeLocalTime(kThursday, 0));

  // The time has passed today.
  TestOpenAt("Bäckerei 7 Uhr", "Bäckerei ", MakeLocalTime(kThursday, 7));
  TestOpenAt("cafe at 8 am", "cafe ", MakeLocalTime(kThursday, 8));
  // But not for an explicit day.
  TestOpenAt("Bäckerei heute 7 Uhr", "Bäckerei ", MakeLocalTime(kWednesday, 7));

  // Day and time.
  TestOpenAt("Apotheke Sonntag 10 Uhr", "Apotheke ", MakeLocalTime(kSunday, 10));
  TestOpenAt("Apotheke am Sonntag um 9:30", "Apotheke ", MakeLocalTime(kSunday, 9, 30));
  TestOpenAt("Apotheke morgen früh 8 Uhr", "Apotheke früh ", MakeLocalTime(kThursday, 8));
  TestOpenAt("Bäckerei 8 am Sonntag", "Bäckerei 8 ", MakeLocalTime(kSunday, 12));
}

UNIT_TEST(QueryFilters_NoFilter)
{
  // Nothing is left.
  TestNoFilter("offen");
  TestNoFilter("geöffnet");
  TestNoFilter("jetzt offen");
  TestNoFilter("open now");
  TestNoFilter("Sonntag");
  TestNoFilter("am Sonntag");
  TestNoFilter("Sonntag 10 Uhr");
  TestNoFilter(" offen ");

  // No filter words.
  TestNoFilter("");
  TestNoFilter("Café");
  TestNoFilter("Offenbach");
  TestNoFilter("Offenbacher Straße");
  TestNoFilter("Café Offenbach");
  TestNoFilter("Tag der offenen Tür");
  TestNoFilter("Sonntagstraße");
  TestNoFilter("Sonntagstraße 1");
  TestNoFilter("Apotheke Sonntagstraße");
  TestNoFilter("Unter den Linden 1");
  TestNoFilter("Torstraße 20");
  TestNoFilter("Restaurant 22");
  TestNoFilter("Restaurant 25 Uhr");
  TestNoFilter("Restaurant 22:75");
  TestNoFilter("Restaurant 13pm");
  TestNoFilter("Café um");
  TestNoFilter("Café am Markt");
  TestNoFilter("Café jetzt");
  TestNoFilter("Supermarkt now");

  // Days, times and inflected forms are ambiguous, they filter categorial queries only.
  TestNoFilter("Rosa Sonntag");
  TestNoFilter("Hotel Montag");
  TestNoFilter("Kaiser Wilhelm Gedächtniskirche morgen");
  TestNoFilter("Offener Kanal");
  TestNoFilter("Open Air Kino");
  TestNoFilter("Kino open");
  TestNoFilter("Dussmann 20 Uhr");

  // "offen" and "geöffnet" filter any query, e.g. a brand.
  TestOpenNow("Rossmann offen", "Rossmann ");
  TestOpenNow("Kino jetzt geöffnet", "Kino ");
  TestOpenNow("Kino open now", "Kino ");
}

UNIT_TEST(QueryFilters_RealCategories)
{
  classificator::Load();
  auto const & categories = GetDefaultCategories();

  Locales locales;
  locales.insert(static_cast<uint64_t>(CategoriesHolder::kEnglishCode));
  locales.insert(static_cast<uint64_t>(CategoriesHolder::MapLocaleToInteger("de")));

  auto const detect = [&](string const & query) { return DetectQueryFilters(query, kNow, categories, locales); };

  for (string const query :
       {"Café offen", "Apotheke Sonntag", "Supermarkt sonntags", "Restaurant heute 22 Uhr", "Tankstelle geöffnet",
        "cafe open now", "Bäckerei am Sonntag", "Frühstück Sonntag", "Apoteke Sonntag", "offene Apotheke"})
  {
    auto const filters = detect(query);
    TEST(filters, (query));
    TEST_EQUAL(filters->m_openingHoursFilter, OpeningHoursFilter::OpenNow, (query));
  }

  for (string const query : {"Offenbach", "Sonntagstraße 1", "Brandenburger Tor Sonntag", "Alexanderplatz morgen",
                             "Unter den Linden 1", "offen"})
  {
    auto const filters = detect(query);
    TEST(!filters, (query, *filters));
  }
}
}  // namespace query_filters_tests
