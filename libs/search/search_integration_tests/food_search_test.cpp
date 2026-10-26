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

// Searching places to eat and drink by cuisine ("indisch", "Italiener offen"), nightlife words ("Club") and the
// quick categories of the search ("Bars & Clubs") with the real categories.txt.
namespace food_search_test
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

class FoodSearchTest : public SearchTest
{
public:
  FoodSearchTest()
    : m_indian({0.001, 0.0}, "Taj Mahal", "de")
    , m_italianWeekdays({0.002, 0.0}, "Da Mario", "de")
    , m_italianLunch({0.003, 0.0}, "Luigi", "de")
    , m_indianStreet({{0.0, 0.01}, {0.01, 0.01}}, "Indische Straße", "de")
    , m_stolperstein({0.004, 0.0}, "Karl Italiener", "de")
    , m_nightclub({0.0, 0.001}, "Berghain", "de")
    , m_gym({0.0, 0.002}, "Kraftwerk", "de")
    , m_bar({0.0, 0.003}, "Kumpelnest", "de")
    , m_pub({0.0, 0.004}, "Zum Hecht", "de")
    , m_biergarten({0.0, 0.005}, "Prater", "de")
    , m_dayCafe({0.0, 0.006}, "Tagescafé", "de")
    , m_nightCafe({0.0, 0.007}, "Nachtcafé", "de")
  {
    m_indian.SetTypes({{"amenity", "restaurant"}, {"cuisine", "indian"}});
    m_indian.GetMetadata().Set(feature::Metadata::FMD_OPEN_HOURS, "Mo-Su 12:00-22:00");
    m_italianWeekdays.SetTypes({{"amenity", "restaurant"}, {"cuisine", "italian"}});
    m_italianWeekdays.GetMetadata().Set(feature::Metadata::FMD_OPEN_HOURS, "Mo-Sa 12:00-22:00");
    m_italianLunch.SetTypes({{"amenity", "restaurant"}, {"cuisine", "italian"}});
    m_italianLunch.GetMetadata().Set(feature::Metadata::FMD_OPEN_HOURS, "Mo-Su 12:00-15:00");
    m_stolperstein.SetTypes({{"historic", "memorial", "stolperstein"}});
    m_nightclub.SetTypes({{"amenity", "nightclub"}});
    m_gym.SetTypes({{"leisure", "fitness_centre"}});
    m_bar.SetTypes({{"amenity", "bar"}});
    m_pub.SetTypes({{"amenity", "pub"}});
    m_biergarten.SetTypes({{"amenity", "biergarten"}});
    m_dayCafe.GetMetadata().Set(feature::Metadata::FMD_OPEN_HOURS, "Mo-Su 08:00-18:00");
    m_nightCafe.GetMetadata().Set(feature::Metadata::FMD_OPEN_HOURS, "Mo-Su 18:00-23:00");

    m_mwmId = BuildCountry("Wonderland", [&](TestMwmBuilder & builder)
    {
      builder.Add(m_indian);
      builder.Add(m_italianWeekdays);
      builder.Add(m_italianLunch);
      builder.Add(m_indianStreet);
      builder.Add(m_stolperstein);
      builder.Add(m_nightclub);
      builder.Add(m_gym);
      builder.Add(m_bar);
      builder.Add(m_pub);
      builder.Add(m_biergarten);
      builder.Add(m_dayCafe);
      builder.Add(m_nightCafe);
    });

    SetViewport(m2::RectD(-0.1, -0.1, 0.1, 0.1));
  }

  // Searches like the app: the query as typed (no trailing space), filter words detected.
  vector<Result> Search(string const & query, int hour = 13, string const & locale = "de")
  {
    SearchParams params = GetDefaultSearchParams(query, locale);
    params.m_detectQueryFilters = true;
    params.m_openingHoursTime = MakeLocalTime(hour);
    return MakeRequest(params)->Results();
  }

  // Searches like a tap on a quick category: the category text with a space, as a categorial request.
  vector<Result> SearchCategory(string const & text, OpeningHoursFilter filter, int hour = 13,
                                string const & locale = "de")
  {
    SearchParams params = GetDefaultSearchParams(text + " ", locale);
    params.m_categorialRequest = true;
    params.m_openingHoursFilter = filter;
    params.m_openingHoursTime = MakeLocalTime(hour);
    return MakeRequest(params)->Results();
  }

  Rule Match(TestFeature const & feature) const { return ExactMatch(m_mwmId, feature); }

protected:
  TestPOI m_indian;
  TestPOI m_italianWeekdays;
  TestPOI m_italianLunch;
  TestStreet m_indianStreet;
  TestPOI m_stolperstein;
  TestPOI m_nightclub;
  TestPOI m_gym;
  TestPOI m_bar;
  TestPOI m_pub;
  TestPOI m_biergarten;
  TestCafe m_dayCafe;
  TestCafe m_nightCafe;

  MwmSet::MwmId m_mwmId;
};

UNIT_CLASS_TEST(FoodSearchTest, Cuisines)
{
  // A cuisine name is a categorial request: only restaurants of this cuisine, no names with this word.
  for (auto const * query : {"indisch", "Indische", "indisches", "Inder", "indisches Restaurant", "Indische Küche"})
    TEST(ResultsMatch(Search(query), {Match(m_indian)}), (query));
  TEST(ResultsMatch(Search("Indian", 13, "en"), {Match(m_indian)}), ());

  for (auto const * query : {"italienisch", "Italiener", "italienisches Restaurant"})
    TEST(ResultsMatch(Search(query), {Match(m_italianWeekdays), Match(m_italianLunch)}), (query));
}

UNIT_CLASS_TEST(FoodSearchTest, NamesWithCuisineWords)
{
  TEST(ResultsMatch(Search("Indische Straße"), {Match(m_indianStreet)}), ());
  TEST(ResultsMatch(Search("Karl Italiener"), {Match(m_stolperstein)}), ());
}

UNIT_CLASS_TEST(FoodSearchTest, CuisinesWithOpeningHoursFilter)
{
  // The search runs on Wednesday.
  TEST(ResultsMatch(Search("Italiener offen", 17), {Match(m_italianWeekdays)}), ());
  TEST(ResultsMatch(Search("Italiener jetzt geöffnet", 13), {Match(m_italianWeekdays), Match(m_italianLunch)}), ());
  TEST(ResultsMatch(Search("Italiener Sonntag", 13), {Match(m_italianLunch)}), ());
  TEST(ResultsMatch(Search("Inder Sonntag", 13), {Match(m_indian)}), ());
  TEST(ResultsMatch(Search("indisch offen", 23), Rules{}), ());
}
UNIT_CLASS_TEST(FoodSearchTest, Nightlife)
{
  // "Club" is a nightclub, not a fitness centre.
  for (auto const * query : {"Club", "Disco", "Diskothek"})
    TEST(ResultsMatch(Search(query), {Match(m_nightclub)}), (query));
  TEST(ResultsMatch(Search("Kneipe"), {Match(m_bar), Match(m_pub)}), ());
}

UNIT_CLASS_TEST(FoodSearchTest, QuickCategories)
{
  for (auto const * locale : {"de", "en", "fr"})
  {
    TEST(ResultsMatch(SearchCategory("Bars & Clubs", OpeningHoursFilter::Off, 13, locale),
                      {Match(m_nightclub), Match(m_bar), Match(m_pub)}),
         (locale));
    TEST(ResultsMatch(SearchCategory("Restaurant", OpeningHoursFilter::Off, 13, locale),
                      {Match(m_indian), Match(m_italianWeekdays), Match(m_italianLunch)}),
         (locale));
    TEST(ResultsMatch(SearchCategory("Café", OpeningHoursFilter::Off, 13, locale),
                      {Match(m_dayCafe), Match(m_nightCafe)}),
         (locale));
  }

  // With "Open now" chosen in the search.
  TEST(ResultsMatch(SearchCategory("Café", OpeningHoursFilter::OpenNow, 13), {Match(m_dayCafe)}), ());
  TEST(ResultsMatch(SearchCategory("Café", OpeningHoursFilter::OpenNow, 20), {Match(m_nightCafe)}), ());
  TEST(ResultsMatch(SearchCategory("Restaurant", OpeningHoursFilter::OpenNow, 17),
                    {Match(m_indian), Match(m_italianWeekdays)}),
       ());
}
}  // namespace food_search_test
