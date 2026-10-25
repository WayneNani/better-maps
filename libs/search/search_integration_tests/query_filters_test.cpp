#include "testing/testing.hpp"

#include "search/opening_hours_filter.hpp"
#include "search/search_params.hpp"
#include "search/search_tests_support/helpers.hpp"
#include "search/search_tests_support/test_results_matching.hpp"
#include "search/search_tests_support/test_search_request.hpp"

#include "generator/generator_tests_support/test_feature.hpp"
#include "generator/generator_tests_support/test_mwm_builder.hpp"

#include "indexer/feature_meta.hpp"

#include "geometry/point2d.hpp"
#include "geometry/rect2d.hpp"

#include <ctime>
#include <string>
#include <vector>

// Opening hours filters from the query text: "Café offen", "Apotheke Sonntag".
namespace query_filters_test
{
using namespace generator::tests_support;
using namespace search::tests_support;
using namespace search;
using namespace std;

// Wednesday, 2026-06-17 at |hour|:00 in local time (a week without a DST switch).
time_t MakeLocalTime(int hour)
{
  tm t = {};
  t.tm_year = 2026 - 1900;
  t.tm_mon = 6 - 1;
  t.tm_mday = 17;
  t.tm_hour = hour;
  t.tm_isdst = -1;
  return mktime(&t);
}

class QueryFiltersTest : public SearchTest
{
public:
  QueryFiltersTest()
    : m_dayCafe({0.001, 0.0}, "Tagescafé", "de")
    , m_nightCafe({0.002, 0.0}, "Nachtcafé", "de")
    , m_unknownCafe({0.003, 0.0}, "Café Ungewiss", "de")
    , m_weekdayPharmacy({0.0, 0.001}, "Werktagsapotheke", "de")
    , m_sundayPharmacy({0.0, 0.002}, "Sonntagsapotheke", "de")
    , m_offenbachStreet({{0.01, 0.01}, {0.02, 0.01}}, "Offenbacher Straße", "de")
    , m_sonntagStreet({{0.01, 0.02}, {0.02, 0.02}}, "Sonntagstraße", "de")
  {
    m_dayCafe.GetMetadata().Set(feature::Metadata::FMD_OPEN_HOURS, "Mo-Su 08:00-20:00");
    m_nightCafe.GetMetadata().Set(feature::Metadata::FMD_OPEN_HOURS, "Mo-Su 18:00-23:00");
    m_weekdayPharmacy.SetTypes({{"amenity", "pharmacy"}});
    m_weekdayPharmacy.GetMetadata().Set(feature::Metadata::FMD_OPEN_HOURS, "Mo-Fr 08:00-20:00");
    m_sundayPharmacy.SetTypes({{"amenity", "pharmacy"}});
    m_sundayPharmacy.GetMetadata().Set(feature::Metadata::FMD_OPEN_HOURS, "Mo-Fr 08:00-18:00; Su 10:00-14:00");

    m_mwmId = BuildCountry("Wonderland", [&](TestMwmBuilder & builder)
    {
      builder.Add(m_dayCafe);
      builder.Add(m_nightCafe);
      builder.Add(m_unknownCafe);
      builder.Add(m_weekdayPharmacy);
      builder.Add(m_sundayPharmacy);
      builder.Add(m_offenbachStreet);
      builder.Add(m_sonntagStreet);
    });

    SetViewport(m2::RectD(-0.1, -0.1, 0.1, 0.1));
  }

  SearchParams MakeParams(string const & query, int hour, string const & locale = "de") const
  {
    SearchParams params = GetDefaultSearchParams(query, locale);
    params.m_detectQueryFilters = true;
    params.m_openingHoursTime = MakeLocalTime(hour);
    return params;
  }

  vector<Result> Search(string const & query, int hour = 12, Mode mode = Mode::Everywhere)
  {
    auto params = MakeParams(query, hour);
    params.m_mode = mode;
    if (mode == Mode::Viewport)
      params.m_maxNumResults = SearchParams::kDefaultNumResultsInViewport;
    return MakeRequest(params)->Results();
  }

  Rule Match(TestFeature const & feature) const { return ExactMatch(m_mwmId, feature); }

protected:
  TestCafe m_dayCafe;
  TestCafe m_nightCafe;
  TestCafe m_unknownCafe;
  TestPOI m_weekdayPharmacy;
  TestPOI m_sundayPharmacy;
  TestStreet m_offenbachStreet;
  TestStreet m_sonntagStreet;

  MwmSet::MwmId m_mwmId;
};

UNIT_CLASS_TEST(QueryFiltersTest, OpenNow)
{
  for (auto const mode : {Mode::Everywhere, Mode::Viewport})
  {
    TEST(ResultsMatch(Search("Café offen", 12, mode), {Match(m_dayCafe)}), (mode));
    TEST(ResultsMatch(Search("Café jetzt geöffnet", 21, mode), {Match(m_nightCafe)}), (mode));
    TEST(ResultsMatch(Search("offenes Café", 12, mode), {Match(m_dayCafe)}), (mode));
  }

  auto params = MakeParams("cafe open now", 12, "en");
  TEST(ResultsMatch(MakeRequest(params)->Results(), {Match(m_dayCafe)}), ());
}

UNIT_CLASS_TEST(QueryFiltersTest, Disabled)
{
  // Without detection "offen" is a search token.
  auto params = MakeParams("Café offen", 12);
  params.m_detectQueryFilters = false;
  TEST(!ResultsMatch(MakeRequest(params)->Results(), {Match(m_dayCafe)}), ());

  // Categorial requests (category chosen in UI) are never changed.
  params = MakeParams("Café ", 12);
  params.m_categorialRequest = true;
  TEST(ResultsMatch(MakeRequest(params)->Results(), {Match(m_dayCafe), Match(m_nightCafe), Match(m_unknownCafe)}), ());
}

UNIT_CLASS_TEST(QueryFiltersTest, ExplicitFilterHasPriority)
{
  // "Also without opening hours" chosen in UI.
  auto params = MakeParams("Café offen", 12);
  params.m_openingHoursFilter = OpeningHoursFilter::OpenNowOrUnknown;
  TEST(ResultsMatch(MakeRequest(params)->Results(), {Match(m_dayCafe), Match(m_unknownCafe)}), ());
}

UNIT_CLASS_TEST(QueryFiltersTest, Days)
{
  // The search runs on Wednesday.
  TEST(ResultsMatch(Search("Apotheke Sonntag"), {Match(m_sundayPharmacy)}), ());
  TEST(ResultsMatch(Search("Apotheke am Sonntag"), {Match(m_sundayPharmacy)}), ());
  TEST(ResultsMatch(Search("Apotheke sonntags"), {Match(m_sundayPharmacy)}), ());
  TEST(ResultsMatch(Search("Apotheke Samstag"), Rules{}), ());
  TEST(ResultsMatch(Search("Apotheke heute"), {Match(m_weekdayPharmacy), Match(m_sundayPharmacy)}), ());
  TEST(ResultsMatch(Search("Apotheke morgen 19 Uhr"), {Match(m_weekdayPharmacy)}), ());
  TEST(ResultsMatch(Search("Apotheke um 19 Uhr", 21), {Match(m_weekdayPharmacy)}), ());

  // Results show the opening state at the moment from the query.
  auto const results = Search("Apotheke Sonntag");
  TEST_EQUAL(results.size(), 1, ());
  TEST_EQUAL(results[0].IsOpenNow(), osm::Yes, ());
}

UNIT_CLASS_TEST(QueryFiltersTest, Collisions)
{
  // Filter words are recognized as whole words only.
  TEST(ResultsMatch(Search("Offenbacher Straße"), {Match(m_offenbachStreet)}), ());
  TEST(ResultsMatch(Search("Sonntagstraße"), {Match(m_sonntagStreet)}), ());
  TEST(ResultsMatch(Search("Sonntagsapotheke"), {Match(m_sundayPharmacy)}), ());

  // Nothing is left without the filter word, so it's a usual (prefix) search.
  TEST(ResultsMatch(Search("Sonntag"), {Match(m_sonntagStreet), Match(m_sundayPharmacy)}), ());
}
}  // namespace query_filters_test
