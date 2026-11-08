#include "testing/testing.hpp"

#include "search/result.hpp"
#include "search/route_corridor.hpp"
#include "search/search_params.hpp"
#include "search/search_tests_support/helpers.hpp"
#include "search/search_tests_support/test_results_matching.hpp"
#include "search/search_tests_support/test_search_request.hpp"

#include "generator/generator_tests_support/test_feature.hpp"
#include "generator/generator_tests_support/test_mwm_builder.hpp"

#include "indexer/feature.hpp"
#include "indexer/feature_meta.hpp"

#include "geometry/mercator.hpp"
#include "geometry/point2d.hpp"
#include "geometry/rect2d.hpp"

#include <ctime>
#include <memory>
#include <string>
#include <vector>

// better-maps: search along a route (Mode::Route).
namespace route_search_test
{
using namespace generator::tests_support;
using namespace search::tests_support;
using namespace search;
using namespace std;

// Wednesday, 2026-10-21 at |hour|:|minute| in local time, as opening hours are evaluated in local time.
time_t MakeLocalTime(int hour, int minute)
{
  tm t = {};
  t.tm_year = 2026 - 1900;
  t.tm_mon = 10 - 1;
  t.tm_mday = 21;
  t.tm_hour = hour;
  t.tm_min = minute;
  t.tm_isdst = -1;
  return mktime(&t);
}

// A route eastwards along the equator, about 22 km long: through the town Alpha and the village Beta in
// Wonderland, then the town Gamma in Neverland. 0.01 in mercator is about 1.1 km here. It takes 40 minutes (about 33
// km/h).
//
//            far (3.3 km)
//
//  behind    left       Starbucks                      end            | Neverland cafe
//  ===o=========Alpha=============o==========Beta===================o=|===========Gamma=========> 0.2
//  start  current position  right (445 m)   Starbucks (2.2 km)        |
class RouteSearchTest : public SearchTest
{
public:
  RouteSearchTest()
    : m_alpha({0.02, 0.0005}, "Alpha", "en", 70)
    , m_beta({0.07, 0.0005}, "Beta", "en", 70)
    , m_gamma({0.17, 0.0005}, "Gamma", "en", 70)
    , m_behind({0.005, 0.001}, "Behind cafe", "en")
    , m_left({0.03, 0.003}, "Left cafe", "en")
    , m_right({0.05, -0.004}, "Right cafe", "en")
    , m_far({0.06, 0.03}, "Far cafe", "en")
    , m_starbucksNear({0.04, 0.001}, "Starbucks", "en")
    , m_starbucksFar({0.08, -0.02}, "Starbucks", "en")
    , m_end({0.095, 0.001}, "End cafe", "en")
    , m_neverland({0.15, 0.002}, "Neverland cafe", "en")
  {
    // Opens at 13:00, see OpeningHoursAtArrival.
    m_left.GetMetadata().Set(feature::Metadata::FMD_OPEN_HOURS, "Mo-Su 13:00-20:00");
    m_end.GetMetadata().Set(feature::Metadata::FMD_OPEN_HOURS, "Mo-Su 13:00-20:00");

    m_wonderlandId = BuildCountry("Wonderland", [&](TestMwmBuilder & builder)
    {
      builder.Add(m_alpha);
      builder.Add(m_beta);
      builder.Add(m_behind);
      builder.Add(m_left);
      builder.Add(m_right);
      builder.Add(m_far);
      builder.Add(m_starbucksNear);
      builder.Add(m_starbucksFar);
      builder.Add(m_end);
    });
    m_neverlandId = BuildCountry("Neverland", [&](TestMwmBuilder & builder)
    {
      builder.Add(m_gamma);
      builder.Add(m_neverland);
    });

    // The viewport is irrelevant for the search along the route.
    SetViewport(m2::RectD(-0.01, -0.01, 0.01, 0.01));
  }

  // The vehicle has passed |passedM| of the route.
  static shared_ptr<RouteCorridor const> MakeCorridor(double halfWidthM, double passedM)
  {
    RouteCorridor::Params params;
    for (int i = 0; i <= 4; ++i)
    {
      params.m_points.emplace_back(i * 0.05, 0.0);
      params.m_timesSec.push_back(i * 600.0);
    }
    params.m_halfWidthM = halfWidthM;
    params.m_passedDistanceM = passedM;
    return make_shared<RouteCorridor const>(params);
  }

  SearchParams MakeParams(string const & query, bool categorial, shared_ptr<RouteCorridor const> corridor) const
  {
    SearchParams params = GetDefaultSearchParams(query);
    params.m_mode = Mode::Route;
    params.m_categorialRequest = categorial;
    params.m_routeCorridor = std::move(corridor);
    params.m_maxNumResults = SearchParams::kDefaultNumResultsAlongRoute;
    params.m_openingHoursTime = MakeLocalTime(12, 50);
    return params;
  }

  vector<Result> Search(SearchParams const & params) { return MakeRequest(params)->Results(); }

  vector<Result> SearchCafes(double halfWidthM = 1000, double passedM = 1500)
  {
    return Search(MakeParams("cafe ", true /* categorial */, MakeCorridor(halfWidthM, passedM)));
  }

  Rule Match(TestFeature const & feature, MwmSet::MwmId const & mwmId) { return ExactMatch(mwmId, feature); }
  Rule Match(TestFeature const & feature) { return Match(feature, m_wonderlandId); }

  // The result of |feature| in |results|, nullptr if there is none.
  Result const * Find(vector<Result> const & results, TestFeature const & feature, MwmSet::MwmId const & mwmId)
  {
    for (auto const & r : results)
      if (ResultsMatch({r}, {Match(feature, mwmId)}))
        return &r;
    return nullptr;
  }
  Result const * Find(vector<Result> const & results, TestFeature const & feature)
  {
    return Find(results, feature, m_wonderlandId);
  }

protected:
  TestCity m_alpha;
  TestVillage m_beta;
  TestCity m_gamma;
  TestCafe m_behind;
  TestCafe m_left;
  TestCafe m_right;
  TestCafe m_far;
  TestCafe m_starbucksNear;
  TestCafe m_starbucksFar;
  TestCafe m_end;
  TestCafe m_neverland;

  MwmSet::MwmId m_wonderlandId;
  MwmSet::MwmId m_neverlandId;
};

UNIT_CLASS_TEST(RouteSearchTest, CategoryInCorridorAheadOrderedAlongRoute)
{
  auto const results = SearchCafes();

  // Not the cafe behind the vehicle, not the one far from the route.
  Rules const rules = {Match(m_left), Match(m_right), Match(m_starbucksNear), Match(m_end),
                       Match(m_neverland, m_neverlandId)};
  TEST(ResultsMatch(results, rules), (results));

  // Ordered along the route, the next one first, in both maps.
  vector<Result const *> const expectedOrder = {Find(results, m_left), Find(results, m_starbucksNear),
                                                Find(results, m_right), Find(results, m_end),
                                                Find(results, m_neverland, m_neverlandId)};
  TEST_EQUAL(results.size(), expectedOrder.size(), ());
  for (size_t i = 0; i < results.size(); ++i)
  {
    TEST_EQUAL(&results[i], expectedOrder[i], (i, results));
    TEST(results[i].GetRoutePosition(), (results[i]));
  }

  // Distances are counted from the current position, 1.5 km after the route start.
  auto const & left = *Find(results, m_left)->GetRoutePosition();
  double const kmPerMercator = mercator::DistanceOnEarth({0, 0}, {1, 0}) / 1000.0;
  TEST_ALMOST_EQUAL_ABS(left.m_distanceAlongRouteM, 0.03 * kmPerMercator * 1000 - 1500, 20.0, (left));
  TEST_ALMOST_EQUAL_ABS(left.m_distanceFromRouteM, 0.003 * kmPerMercator * 1000, 5.0, (left));
  TEST_GREATER(left.m_detourSec, 0.0, (left));
  TEST_GREATER(left.m_timeAlongRouteSec, 0.0, (left));
}

UNIT_CLASS_TEST(RouteSearchTest, CorridorWidth)
{
  // The left cafe is 334 m from the route, the right one 445 m.
  auto const results = SearchCafes(400 /* halfWidthM */);
  TEST(Find(results, m_left), (results));
  TEST(!Find(results, m_right), (results));

  // Wide enough for the far cafe, 3.3 km from the route.
  auto const wide = SearchCafes(4000 /* halfWidthM */);
  TEST(Find(wide, m_far), (wide));
  TEST(Find(wide, m_starbucksFar), (wide));
}

UNIT_CLASS_TEST(RouteSearchTest, OnlyAhead)
{
  // At the route start, the "behind" cafe is ahead.
  TEST(Find(SearchCafes(1000, 0 /* passedM */), m_behind), ());

  // After Neverland's border nothing in Wonderland is ahead.
  auto const results = SearchCafes(1000, 12000 /* passedM */);
  Rules const rules = {Match(m_neverland, m_neverlandId)};
  TEST(ResultsMatch(results, rules), (results));

  // At the destination: nothing ahead.
  TEST(SearchCafes(1000, 30000 /* passedM */).empty(), ());
}

UNIT_CLASS_TEST(RouteSearchTest, TextQuery)
{
  // The Starbucks near the route, not the one 2.2 km away.
  auto results = Search(MakeParams("Starbucks", false /* categorial */, MakeCorridor(1000, 1500)));
  TEST(ResultsMatch(results, {Match(m_starbucksNear)}), (results));

  // A village on the route.
  results = Search(MakeParams("Beta", false /* categorial */, MakeCorridor(1000, 1500)));
  TEST(ResultsMatch(results, {Match(m_beta)}), (results));
}

UNIT_CLASS_TEST(RouteSearchTest, OpeningHoursAtArrival)
{
  // Both open at 13:00. The search starts at 12:50: the left cafe is reached at about 12:54, the end cafe after 13:05.
  auto params = MakeParams("cafe ", true /* categorial */, MakeCorridor(1000, 1500));
  {
    auto const results = Search(params);
    TEST_EQUAL(Find(results, m_left)->IsOpenNow(), osm::No, (results));
    TEST_EQUAL(Find(results, m_end)->IsOpenNow(), osm::Yes, (results));
  }
  {
    // The other cafes have no opening hours.
    params.m_openingHoursFilter = OpeningHoursFilter::OpenNow;
    auto const results = Search(params);
    TEST(ResultsMatch(results, {Match(m_end)}), (results));
  }
}

UNIT_CLASS_TEST(RouteSearchTest, PoiFilter)
{
  // E.g. charging stations with a certain socket: here cafes without "Starbucks" in the name.
  auto params = MakeParams("cafe ", true /* categorial */, MakeCorridor(1000, 1500));
  params.m_poiFilter = [](FeatureType & ft) { return ft.GetName(localisation::kEnglishLanguageIndex) != "Starbucks"; };
  auto const results = Search(params);
  TEST(!Find(results, m_starbucksNear), (results));
  TEST(Find(results, m_left), (results));
}
}  // namespace route_search_test
