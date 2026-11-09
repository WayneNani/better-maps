#include "testing/testing.hpp"

#include "ev_planning/ev_planning_tests/test_helpers.hpp"

#include "ev_planning/plan_json.hpp"

#include <string>

namespace plan_json_tests
{
using namespace ev;
using namespace ev_planning_tests;

UNIT_TEST(PlanJson_Options)
{
  auto const options = PlanOptionsFromJson(
      R"({"startSoc":82,"minArrivalSoc":25,"minStopSoc":12,"priority":"fewest_stops","preferredProviders":["enbw"],)"
      R"("excludedProviders":["tesla"],"minPowerKw":150,"preferredAmenities":["toilets","playground","nonsense"],)"
      R"("excludedStations":["Germany:12"],"departureTime":1794200000,"newKey":true})");
  TEST(options, ());
  TEST_EQUAL(options->m_startSocPct, 82.0, ());
  TEST_EQUAL(options->m_minArrivalSocPct, 25.0, ());
  TEST_EQUAL(options->m_minStopSocPct, 12.0, ());
  TEST_EQUAL(options->m_maxChargeSocPct, 100.0, ());
  TEST_EQUAL(options->m_priority, Priority::FewestStops, ());
  TEST_EQUAL(options->m_preferredProviders, (std::vector<std::string>{"enbw"}), ());
  TEST_EQUAL(options->m_excludedProviders, (std::vector<std::string>{"tesla"}), ());
  TEST_EQUAL(options->m_minPowerKw, 150.0, ());
  TEST_EQUAL(options->m_preferredAmenities, (std::vector<AmenityType>{AmenityType::Toilets, AmenityType::Playground}),
             ());
  TEST_EQUAL(options->m_excludedStations, (std::vector<std::string>{"Germany:12"}), ());
  TEST(options->m_departureTime, ());
  TEST_EQUAL(*options->m_departureTime, 1794200000, ());

  auto const again = PlanOptionsFromJson(ToJson(*options));
  TEST(again, ());
  TEST_EQUAL(ToJson(*again), ToJson(*options), ());

  // Defaults.
  auto const empty = PlanOptionsFromJson("{}");
  TEST(empty, ());
  TEST_EQUAL(empty->m_priority, Priority::Fastest, ());
  TEST(!empty->m_departureTime, ());
  TEST(!PlanOptionsFromJson("[1,2"), ());
}

UNIT_TEST(PlanJson_Result)
{
  auto const & elroq = Elroq();
  auto const route = MakeRoute(elroq, 412.0, 100.0);
  std::vector<ChargingStation> stations;
  for (uint32_t i = 1; i * 30.0 < 412.0; ++i)
  {
    stations.push_back(MakeStation(i, i * 30.0, "type2_combo|4|300;type2_combo|4|150;type2|2|22", "EnBW"));
    Amenity playground;
    playground.m_type = AmenityType::Playground;
    playground.m_kind = "playground";
    playground.m_distanceM = 90.0;
    playground.m_openAtArrival = true;
    stations.back().m_amenities.push_back(playground);
  }
  PlanOptions options;
  options.m_startSocPct = 82.0;
  options.m_preferredAmenities = {AmenityType::Playground};
  auto const json = ToJson(PlanCharging(elroq, route, stations, options));

  for (auto const * part :
       {R"("status":"ok")", R"("stops":[{)", R"("provider":"EnBW")", R"("powerKw":300)",
        R"("sockets":[{"type":"type2_combo","count":4,"powerKw":300})", R"("matchedAmenities":["playground"])",
        R"("openAtArrival":true)", R"("longer":{)", R"("availability":"unknown")",
        R"("providers":[{"key":"enbw","name":"EnBW","stations":13}])", R"("priority":"fastest")",
        R"("alternatives":[{)"})
  {
    TEST(json.find(part) != std::string::npos, (part, json));
  }
}
}  // namespace plan_json_tests
