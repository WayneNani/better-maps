#include "testing/testing.hpp"

#include "search/search_params.hpp"
#include "search/search_tests_support/helpers.hpp"
#include "search/search_tests_support/test_results_matching.hpp"
#include "search/search_tests_support/test_search_request.hpp"

#include "generator/generator_tests_support/test_feature.hpp"
#include "generator/generator_tests_support/test_mwm_builder.hpp"

#include "geometry/mercator.hpp"
#include "geometry/point2d.hpp"

#include <string>
#include <vector>

// Searching for railway stations in another city than the user's one: "Nürnberg Hbf" from Berlin.
namespace station_ranking_test
{
using namespace generator::tests_support;
using namespace search::tests_support;
using namespace search;
using namespace std;

// The user is at (0, 0), the cities are about 550 km away.
m2::PointD const kNuremberg = mercator::FromLatLon(3.5, 3.5);
m2::PointD const kFuerth = mercator::FromLatLon(3.5, 3.44);

// A point |east| and |north| meters away from |p|.
m2::PointD Offset(m2::PointD const & p, double east, double north)
{
  return p + m2::PointD(mercator::MetersToMercator(east), mercator::MetersToMercator(north));
}

TestPOI MakePoi(m2::PointD const & p, string const & name, base::StringIL const & type)
{
  TestPOI poi(p, name, "de");
  poi.SetTypes({type});
  return poi;
}

class StationRankingTest : public SearchTest
{
public:
  StationRankingTest()
    : m_nuremberg(kNuremberg, "Nürnberg", "de", 100 /* rank */)
    , m_fuerth(kFuerth, "Fürth", "de", 80 /* rank */)
    // Nürnberg Hauptbahnhof: the station, its subway station and building with the same name, subway entrances
    // named "Hauptbahnhof" and a tram stop.
    , m_station(MakePoi(Offset(kNuremberg, 300, -800), "Nürnberg Hauptbahnhof", {"railway", "station"}))
    , m_subwayStation(
          MakePoi(Offset(kNuremberg, 250, -750), "Nürnberg Hauptbahnhof", {"railway", "station", "subway"}))
    , m_stationBuilding(MakePoi(Offset(kNuremberg, 320, -760), "Nürnberg Hauptbahnhof", {"building", "train_station"}))
    , m_entrance1(MakePoi(Offset(kNuremberg, 200, -650), "Hauptbahnhof", {"railway", "subway_entrance"}))
    , m_entrance2(MakePoi(Offset(kNuremberg, 380, -620), "Hauptbahnhof", {"railway", "subway_entrance"}))
    , m_tramStop(MakePoi(Offset(kNuremberg, 150, -700), "Hauptbahnhof", {"railway", "tram_stop"}))
    // Another station of Nürnberg.
    , m_otherStation(MakePoi(Offset(kNuremberg, -1500, -1200), "Nürnberg-Steinbühl", {"railway", "station"}))
    // Fürth: the station, its subway station and a bus stop named "Hauptbahnhof".
    , m_fuerthStation(MakePoi(Offset(kFuerth, 400, -300), "Fürth (Bayern) Hauptbahnhof", {"railway", "station"}))
    , m_fuerthSubway(MakePoi(Offset(kFuerth, 350, -280), "Fürth Hauptbahnhof", {"railway", "station", "subway"}))
    , m_fuerthBusStop(MakePoi(Offset(kFuerth, 300, -200), "Hauptbahnhof", {"highway", "bus_stop"}))
    // Things that are not stations.
    , m_street({Offset(kNuremberg, -300, 300), Offset(kNuremberg, 300, 300)}, "Bahnhofstraße", "de")
    , m_hotel(MakePoi(Offset(kNuremberg, 500, -900), "Hotel am Hauptbahnhof", {"tourism", "hotel"}))
    , m_suburb(Offset(kNuremberg, -2000, 1000), "Gostenhof", "de")
  {
    m_worldId = BuildWorld([&](TestMwmBuilder & builder)
    {
      builder.Add(m_nuremberg);
      builder.Add(m_fuerth);
    });
    m_countryId = BuildCountry("Franconia", [&](TestMwmBuilder & builder)
    {
      builder.Add(m_nuremberg);
      builder.Add(m_fuerth);
      for (auto const * poi : {&m_station, &m_subwayStation, &m_stationBuilding, &m_entrance1, &m_entrance2,
                               &m_tramStop, &m_otherStation, &m_fuerthStation, &m_fuerthSubway, &m_fuerthBusStop,
                               &m_hotel})
      {
        builder.Add(*poi);
      }
      builder.Add(m_street);
      builder.Add(m_suburb);
    });
    // A user at home, far away from Nürnberg.
    BuildCountry("Home", [&](TestMwmBuilder & builder)
    { builder.Add(MakePoi(m2::PointD(0, 0), "Bäckerei", {"shop", "bakery"})); });

    double const size = mercator::MetersToMercator(5000);
    SetViewport(m2::RectD(-size, -size, size, size));
  }

  vector<Result> Search(string const & query)
  {
    auto params = GetDefaultSearchParams(query, "de");
    params.m_position = m2::PointD(0, 0);
    auto request = MakeRequest(params);
    return request->Results();
  }

  bool IsFirst(vector<Result> const & results, TestFeature const & feature)
  {
    return !results.empty() && ResultsMatch({results[0]}, {ExactMatch(m_countryId, feature)});
  }

  bool Contains(vector<Result> const & results, TestFeature const & feature)
  {
    auto const rule = ExactMatch(m_countryId, feature);
    for (auto const & r : results)
      if (IsResultMatches(r, rule))
        return true;
    return false;
  }

  TestCity m_nuremberg;
  TestCity m_fuerth;
  TestPOI m_station;
  TestPOI m_subwayStation;
  TestPOI m_stationBuilding;
  TestPOI m_entrance1;
  TestPOI m_entrance2;
  TestPOI m_tramStop;
  TestPOI m_otherStation;
  TestPOI m_fuerthStation;
  TestPOI m_fuerthSubway;
  TestPOI m_fuerthBusStop;
  TestStreet m_street;
  TestPOI m_hotel;
  TestSuburb m_suburb;

  MwmSet::MwmId m_worldId;
  MwmSet::MwmId m_countryId;
};

UNIT_CLASS_TEST(StationRankingTest, CityAndMainStation)
{
  for (string const query : {"Nürnberg Hbf", "Nürnberg Hauptbahnhof", "Hbf Nürnberg", "Nürnberg Hbf ",
                             "Hauptbahnhof Nürnberg", "Nürnberg Bahnhof"})
  {
    auto const results = Search(query);
    TEST(IsFirst(results, m_station), (query, results));

    // The subway station, the station building and the subway entrances are merged into the station.
    TEST(!Contains(results, m_subwayStation), (query, results));
    TEST(!Contains(results, m_stationBuilding), (query, results));
    TEST(!Contains(results, m_entrance1), (query, results));
    TEST(!Contains(results, m_entrance2), (query, results));
  }

  // The tram stop is not a part of the station, it is still found after it.
  auto const results = Search("Nürnberg Hbf");
  TEST(Contains(results, m_tramStop), (results));
}

UNIT_CLASS_TEST(StationRankingTest, StationWithAnotherName)
{
  // "Fürth Hauptbahnhof" (subway) is merged into "Fürth (Bayern) Hauptbahnhof", the bus stop is not.
  for (string const query : {"Fürth Hbf", "Fürth Hauptbahnhof", "Hbf Fürth"})
  {
    auto const results = Search(query);
    TEST(IsFirst(results, m_fuerthStation), (query, results));
    TEST(!Contains(results, m_fuerthSubway), (query, results));
    TEST(Contains(results, m_fuerthBusStop), (query, results));
  }
}

UNIT_CLASS_TEST(StationRankingTest, OtherStation)
{
  auto const results = Search("Nürnberg Steinbühl");
  TEST(IsFirst(results, m_otherStation), (results));
}

UNIT_CLASS_TEST(StationRankingTest, NotStations)
{
  {
    auto const results = Search("Nürnberg Bahnhofstraße");
    TEST(IsFirst(results, m_street), (results));
  }
  {
    auto const results = Search("Hotel am Hauptbahnhof");
    TEST(IsFirst(results, m_hotel), (results));
  }
  {
    auto const results = Search("Nürnberg Hotel am Hauptbahnhof");
    TEST(IsFirst(results, m_hotel), (results));
  }
  {
    auto const results = Search("Nürnberg Gostenhof");
    TEST(IsFirst(results, m_suburb), (results));
  }
}
}  // namespace station_ranking_test
