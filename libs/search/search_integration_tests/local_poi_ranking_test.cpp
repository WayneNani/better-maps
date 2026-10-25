#include "testing/testing.hpp"

#include "search/search_params.hpp"
#include "search/search_tests_support/helpers.hpp"
#include "search/search_tests_support/test_results_matching.hpp"
#include "search/search_tests_support/test_search_request.hpp"

#include "generator/generator_tests_support/test_feature.hpp"
#include "generator/generator_tests_support/test_mwm_builder.hpp"

#include "indexer/feature_meta.hpp"

#include "geometry/mercator.hpp"
#include "geometry/point2d.hpp"
#include "geometry/rect2d.hpp"

#include <ctime>
#include <string>
#include <vector>

// Ranking of POIs for category queries ("Café", "Apotheke"): distance, opening state, importance.
namespace local_poi_ranking_test
{
using namespace generator::tests_support;
using namespace search::tests_support;
using namespace search;
using namespace std;

using feature::Metadata;

// Wednesday, 2026-06-17 at 12:00 in local time, as opening hours are evaluated in local time.
time_t MakeLocalNoon()
{
  tm t = {};
  t.tm_year = 2026 - 1900;
  t.tm_mon = 6 - 1;
  t.tm_mday = 17;
  t.tm_hour = 12;
  t.tm_isdst = -1;
  return mktime(&t);
}

// A point |meters| to the east of the origin (the user position).
m2::PointD East(double meters)
{
  return mercator::FromLatLon(0.0, 0.0) + m2::PointD(mercator::MetersToMercator(meters), 0.0);
}

char const kOpen[] = "Mo-Su 08:00-20:00";
char const kClosed[] = "Mo-Su 18:00-23:00";

class LocalPoiRankingTest : public SearchTest
{
public:
  TestPOI MakeCafe(double meters, string const & name, char const * openingHours = nullptr)
  {
    TestPOI cafe(East(meters), name, "en");
    cafe.SetTypes({{"amenity", "cafe"}});
    if (openingHours)
      cafe.GetMetadata().Set(Metadata::FMD_OPEN_HOURS, openingHours);
    return cafe;
  }

  TestPOI MakeCafeAt(m2::PointD const & point, string const & name)
  {
    TestPOI cafe(point, name, "en");
    cafe.SetTypes({{"amenity", "cafe"}});
    return cafe;
  }

  void Build(vector<TestPOI> const & pois)
  {
    m_mwmId = BuildCountry("Wonderland", [&](TestMwmBuilder & builder)
    {
      for (auto const & poi : pois)
        builder.Add(poi);
    });
    // The viewport contains the user position.
    double const size = mercator::MetersToMercator(20000);
    SetViewport(m2::RectD(-size, -size, size, size));
  }

  vector<Result> Search(string const & query, bool categorial = false, Mode mode = Mode::Everywhere,
                        m2::PointD const & position = mercator::FromLatLon(0.0, 0.0))
  {
    SearchParams params = GetDefaultSearchParams(query);
    params.m_position = position;
    params.m_categorialRequest = categorial;
    params.m_openingHoursTime = MakeLocalNoon();
    params.m_mode = mode;
    if (mode == Mode::Viewport)
      params.m_maxNumResults = SearchParams::kDefaultNumResultsInViewport;
    return MakeRequest(params)->Results();
  }

  Rules MakeRules(vector<TestPOI const *> const & pois) const
  {
    Rules rules;
    for (auto const * poi : pois)
      rules.push_back(ExactMatch(m_mwmId, *poi));
    return rules;
  }

  MwmSet::MwmId m_mwmId;
};

UNIT_CLASS_TEST(LocalPoiRankingTest, CloserIsBetter)
{
  auto const near = MakeCafe(300, "Near");
  auto const middle = MakeCafe(1500, "Middle");
  auto const far = MakeCafe(6000, "Far");
  Build({far, middle, near});

  for (bool const categorial : {false, true})
  {
    TEST(OrderedResultsMatch(Search("cafe", categorial), MakeRules({&near, &middle, &far})), (categorial));
    TEST(OrderedResultsMatch(Search("cafe ", categorial), MakeRules({&near, &middle, &far})), (categorial));
  }
}

UNIT_CLASS_TEST(LocalPoiRankingTest, OpenBeforeClosed)
{
  // At noon: a closed cafe next door is worse than an open one or one with unknown hours a bit farther away.
  auto const closed = MakeCafe(100, "Closed", kClosed);
  auto const open = MakeCafe(800, "Open", kOpen);
  auto const unknown = MakeCafe(400, "Unknown");
  auto const farOpen = MakeCafe(15000, "Far open", kOpen);
  Build({closed, open, unknown, farOpen});

  for (bool const categorial : {false, true})
  {
    auto const results = Search("cafe", categorial);
    TEST(OrderedResultsMatch(results, MakeRules({&unknown, &open, &closed, &farOpen})), (categorial, results));
  }

  // Viewport results are not ordered, but closed POIs are not dropped.
  TEST(ResultsMatch(Search("cafe", true /* categorial */, Mode::Viewport),
                    MakeRules({&open, &farOpen, &unknown, &closed})),
       ());
}

UNIT_CLASS_TEST(LocalPoiRankingTest, Importance)
{
  // A cafe with a Wikipedia article, a brand and informative tags is better than a plain one a bit closer,
  // but not than a plain one much closer.
  auto important = MakeCafe(1000, "Important");
  important.GetMetadata().Set(Metadata::FMD_WIKIPEDIA, "en:Important Cafe");
  important.GetMetadata().Set(Metadata::FMD_BRAND, "Important Chain");
  important.GetMetadata().Set(Metadata::FMD_WEBSITE, "https://important.example");
  important.GetMetadata().Set(Metadata::FMD_PHONE_NUMBER, "+49 30 123456");
  auto const plain = MakeCafe(700, "Plain");
  auto const nextDoor = MakeCafe(50, "Next door");
  Build({important, plain, nextDoor});

  TEST(OrderedResultsMatch(Search("cafe"), MakeRules({&nextDoor, &important, &plain})), ());
}

UNIT_CLASS_TEST(LocalPoiRankingTest, SecondaryType)
{
  // For "cafe" a cafe is better than a museum with a cafe, even with a Wikipedia article.
  TestPOI museum(East(100), "Museum", "en");
  museum.SetTypes({{"tourism", "museum"}, {"amenity", "cafe"}});
  museum.GetMetadata().Set(Metadata::FMD_WIKIPEDIA, "en:Museum");
  auto const cafe = MakeCafe(600, "Cafe");
  Build({museum, cafe});

  TEST(OrderedResultsMatch(Search("cafe"), MakeRules({&cafe, &museum})), ());
  // But it's the first for "museum".
  TEST(OrderedResultsMatch(Search("museum"), MakeRules({&museum})), ());
}

UNIT_CLASS_TEST(LocalPoiRankingTest, ViewportCenterIfPositionIsOutside)
{
  // The user looks at the map far away from the position: the distance to the viewport center counts.
  auto const nearViewportCenter = MakeCafeAt(m2::PointD(0.0, 0.0), "Center");
  auto const nearPosition = MakeCafeAt(East(25000), "Position");
  Build({nearViewportCenter, nearPosition});

  for (bool const categorial : {false, true})
  {
    TEST(OrderedResultsMatch(Search("cafe", categorial, Mode::Everywhere, East(25100)),
                             MakeRules({&nearViewportCenter, &nearPosition})),
         (categorial));
  }
}

UNIT_CLASS_TEST(LocalPoiRankingTest, NameQueryIsNotChanged)
{
  // "Nordcafe" is matched by name, the query is not a category: the closed POI with the better name match wins.
  auto const byName = MakeCafe(3000, "Nordcafe Linden", kClosed);
  TestPOI other(East(100), "Linden Nordcafe Bistro", "en");
  other.SetTypes({{"amenity", "restaurant"}});
  other.GetMetadata().Set(Metadata::FMD_OPEN_HOURS, kOpen);
  Build({byName, other});

  TEST(OrderedResultsMatch(Search("Nordcafe Linden"), MakeRules({&byName, &other})), ());
}
}  // namespace local_poi_ranking_test
