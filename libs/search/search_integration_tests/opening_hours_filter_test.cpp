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

namespace opening_hours_filter_test
{
using namespace generator::tests_support;
using namespace search::tests_support;
using namespace search;
using namespace std;

// Wednesday, 2026-10-21 at |hour|:00 in local time, as opening hours are evaluated in local time.
time_t MakeLocalTime(int hour)
{
  tm t = {};
  t.tm_year = 2026 - 1900;
  t.tm_mon = 10 - 1;
  t.tm_mday = 21;
  t.tm_hour = hour;
  t.tm_isdst = -1;
  return mktime(&t);
}

class OpeningHoursFilterTest : public SearchTest
{
public:
  OpeningHoursFilterTest()
    : m_openCafe({0.001, 0.0}, "Day Cafe", "en")
    , m_closedCafe({0.002, 0.0}, "Night Cafe", "en")
    , m_unknownCafe({0.003, 0.0}, "Mystery Cafe", "en")
    , m_invalidCafe({0.004, 0.0}, "Typo Cafe", "en")
    , m_street({{0.0, 0.01}, {0.01, 0.01}}, "Cafe street", "en")
    , m_building({0.005, 0.005}, "Cafe", "5", "en")
  {
    m_openCafe.GetMetadata().Set(feature::Metadata::FMD_OPEN_HOURS, "Mo-Su 08:00-20:00");
    m_closedCafe.GetMetadata().Set(feature::Metadata::FMD_OPEN_HOURS, "Mo-Su 18:00-23:00");
    m_invalidCafe.GetMetadata().Set(feature::Metadata::FMD_OPEN_HOURS, "sometimes, ask the owner");
    // Buildings (addresses) are not POIs and are never filtered, even with closed opening hours.
    m_building.GetMetadata().Set(feature::Metadata::FMD_OPEN_HOURS, "Mo-Su 18:00-23:00");

    m_mwmId = BuildCountry("Wonderland", [&](TestMwmBuilder & builder)
    {
      builder.Add(m_openCafe);
      builder.Add(m_closedCafe);
      builder.Add(m_unknownCafe);
      builder.Add(m_invalidCafe);
      builder.Add(m_street);
      builder.Add(m_building);
    });

    SetViewport(m2::RectD(-0.1, -0.1, 0.1, 0.1));
  }

  vector<Result> Search(string const & query, OpeningHoursFilter filter, Mode mode = Mode::Everywhere,
                        bool categorial = true, int hour = 12)
  {
    SearchParams params = GetDefaultSearchParams(query);
    params.m_mode = mode;
    params.m_categorialRequest = categorial;
    params.m_openingHoursFilter = filter;
    params.m_openingHoursTime = MakeLocalTime(hour);
    if (mode == Mode::Viewport)
      params.m_maxNumResults = SearchParams::kDefaultNumResultsInViewport;
    return MakeRequest(params)->Results();
  }

  Rule Match(TestFeature const & feature) const { return ExactMatch(m_mwmId, feature); }

protected:
  TestCafe m_openCafe;
  TestCafe m_closedCafe;
  TestCafe m_unknownCafe;
  TestCafe m_invalidCafe;
  TestStreet m_street;
  TestBuilding m_building;

  MwmSet::MwmId m_mwmId;
};

UNIT_CLASS_TEST(OpeningHoursFilterTest, CategorialEverywhere)
{
  {
    Rules const rules = {Match(m_openCafe), Match(m_closedCafe), Match(m_unknownCafe), Match(m_invalidCafe)};
    TEST(ResultsMatch(Search("cafe ", OpeningHoursFilter::Off), rules), ());
  }
  {
    Rules const rules = {Match(m_openCafe)};
    TEST(ResultsMatch(Search("cafe ", OpeningHoursFilter::OpenNow), rules), ());
  }
  {
    Rules const rules = {Match(m_openCafe), Match(m_unknownCafe), Match(m_invalidCafe)};
    TEST(ResultsMatch(Search("cafe ", OpeningHoursFilter::OpenNowOrUnknown), rules), ());
  }
}

UNIT_CLASS_TEST(OpeningHoursFilterTest, CategorialViewport)
{
  {
    Rules const rules = {Match(m_openCafe), Match(m_closedCafe), Match(m_unknownCafe), Match(m_invalidCafe)};
    TEST(ResultsMatch(Search("cafe ", OpeningHoursFilter::Off, Mode::Viewport), rules), ());
  }
  {
    Rules const rules = {Match(m_openCafe)};
    TEST(ResultsMatch(Search("cafe ", OpeningHoursFilter::OpenNow, Mode::Viewport), rules), ());
  }
  {
    Rules const rules = {Match(m_openCafe), Match(m_unknownCafe), Match(m_invalidCafe)};
    TEST(ResultsMatch(Search("cafe ", OpeningHoursFilter::OpenNowOrUnknown, Mode::Viewport), rules), ());
  }
}

UNIT_CLASS_TEST(OpeningHoursFilterTest, NonPoiResultsAreKept)
{
  // Free text query matches cafes by category and the street and the building by name.
  for (auto const mode : {Mode::Everywhere, Mode::Viewport})
  {
    {
      Rules const rules = {Match(m_openCafe),    Match(m_closedCafe), Match(m_unknownCafe),
                           Match(m_invalidCafe), Match(m_street),     Match(m_building)};
      TEST(ResultsMatch(Search("cafe", OpeningHoursFilter::Off, mode, false /* categorial */), rules), (mode));
    }
    {
      Rules const rules = {Match(m_openCafe), Match(m_street), Match(m_building)};
      TEST(ResultsMatch(Search("cafe", OpeningHoursFilter::OpenNow, mode, false /* categorial */), rules), (mode));
    }
    {
      Rules const rules = {Match(m_openCafe), Match(m_unknownCafe), Match(m_invalidCafe), Match(m_street),
                           Match(m_building)};
      TEST(ResultsMatch(Search("cafe", OpeningHoursFilter::OpenNowOrUnknown, mode, false /* categorial */), rules),
           (mode));
    }
  }
}

UNIT_CLASS_TEST(OpeningHoursFilterTest, DependsOnTime)
{
  // At 21:00 |m_openCafe| is closed and |m_closedCafe| is open.
  {
    Rules const rules = {Match(m_closedCafe)};
    TEST(ResultsMatch(Search("cafe ", OpeningHoursFilter::OpenNow, Mode::Everywhere, true /* categorial */, 21), rules),
         ());
  }
  // At 06:00 both are closed.
  {
    Rules const rules = {Match(m_unknownCafe), Match(m_invalidCafe)};
    TEST(ResultsMatch(Search("cafe ", OpeningHoursFilter::OpenNowOrUnknown, Mode::Everywhere, true /* categorial */, 6),
                      rules),
         ());
  }
}

UNIT_CLASS_TEST(OpeningHoursFilterTest, ResultsOpenStatusUsesSearchTime)
{
  for (int const hour : {12, 21})
  {
    auto const results = Search("cafe ", OpeningHoursFilter::Off, Mode::Everywhere, true /* categorial */, hour);
    TEST_EQUAL(results.size(), 4, (hour));
    for (auto const & r : results)
    {
      osm::YesNoUnknown expected = osm::Unknown;
      if (ResultsMatch({r}, {Match(m_openCafe)}))
        expected = hour == 12 ? osm::Yes : osm::No;
      else if (ResultsMatch({r}, {Match(m_closedCafe)}))
        expected = hour == 12 ? osm::No : osm::Yes;
      TEST_EQUAL(r.IsOpenNow(), expected, (hour, r));
    }
  }
}

// More POIs than PreRanker's everywhere batch size (1000), so the batch selection is involved.
UNIT_CLASS_TEST(SearchTest, OpeningHoursFilter_ManyPois)
{
  size_t constexpr kSide = 40;
  vector<TestCafe> cafes;
  cafes.reserve(kSide * kSide);
  for (size_t i = 0; i < kSide; ++i)
  {
    for (size_t j = 0; j < kSide; ++j)
    {
      cafes.emplace_back(m2::PointD(i * 0.0001, j * 0.0001), "Cafe", "en");
      cafes.back().GetMetadata().Set(feature::Metadata::FMD_OPEN_HOURS,
                                     (i + j) % 2 == 0 ? "Mo-Su 08:00-20:00" : "Mo-Su 18:00-23:00");
    }
  }

  BuildCountry("Wonderland", [&](TestMwmBuilder & builder)
  {
    for (auto const & cafe : cafes)
      builder.Add(cafe);
  });
  SetViewport(m2::RectD(-0.1, -0.1, 0.1, 0.1));

  SearchParams params = GetDefaultSearchParams("cafe ");
  params.m_categorialRequest = true;
  params.m_openingHoursTime = MakeLocalTime(12);

  params.m_openingHoursFilter = OpeningHoursFilter::Off;
  auto results = MakeRequest(params)->Results();
  TEST_EQUAL(results.size(), params.m_maxNumResults, ());
  size_t closedCount = 0;
  for (auto const & r : results)
    if (r.IsOpenNow() == osm::No)
      ++closedCount;
  TEST_GREATER(closedCount, 0, ());

  params.m_openingHoursFilter = OpeningHoursFilter::OpenNow;
  results = MakeRequest(params)->Results();
  TEST_EQUAL(results.size(), params.m_maxNumResults, ());
  for (auto const & r : results)
    TEST_EQUAL(r.IsOpenNow(), osm::Yes, (r));
}

UNIT_CLASS_TEST(OpeningHoursFilterTest, ViewportFilterBeforeSweeping)
{
  // All cafes are close to each other, so with a big min distance only one of them survives the
  // viewport displacement. The filter must be applied before it, so the open cafe is shown.
  SearchParams params = GetDefaultSearchParams("cafe ");
  params.m_mode = Mode::Viewport;
  params.m_categorialRequest = true;
  params.m_maxNumResults = SearchParams::kDefaultNumResultsInViewport;
  params.m_minDistanceOnMapBetweenResults = {0.1, 0.1};
  params.m_openingHoursTime = MakeLocalTime(12);

  params.m_openingHoursFilter = OpeningHoursFilter::Off;
  TEST_EQUAL(MakeRequest(params)->Results().size(), 1, ());

  params.m_openingHoursFilter = OpeningHoursFilter::OpenNow;
  Rules const rules = {Match(m_openCafe)};
  TEST(ResultsMatch(MakeRequest(params)->Results(), rules), ());
}
}  // namespace opening_hours_filter_test
