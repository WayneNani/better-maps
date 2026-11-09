#include "testing/testing.hpp"

#include "ev_planning/ev_planning_tests/test_helpers.hpp"

#include "ev_planning/planner.hpp"
#include "ev_planning/station.hpp"

namespace station_tests
{
using namespace ev;
using namespace ev_planning_tests;

UNIT_TEST(Station_NormalizeProvider)
{
  TEST_EQUAL(NormalizeProvider("EnBW mobility+").m_key, "enbw", ());
  TEST_EQUAL(NormalizeProvider("EnBW Energie Baden-Württemberg AG").m_key, "enbw", ());
  TEST_EQUAL(NormalizeProvider("EnBW").m_name, "EnBW", ());
  TEST_EQUAL(NormalizeProvider("IONITY GmbH").m_key, "ionity", ());
  TEST_EQUAL(NormalizeProvider("Ionity").m_name, "Ionity", ());
  TEST_EQUAL(NormalizeProvider("Tesla, Inc.").m_key, "tesla", ());
  TEST_EQUAL(NormalizeProvider("Tesla Supercharger").m_key, "tesla", ());
  TEST_EQUAL(NormalizeProvider("Aral pulse").m_key, "aral", ());
  TEST_EQUAL(NormalizeProvider("EWE Go GmbH").m_key, "ewe_go", ());
  TEST_EQUAL(NormalizeProvider("E.ON Drive").m_key, "eon", ());
  // Unknown providers: without legal forms.
  auto const stadtwerke = NormalizeProvider("Stadtwerke Bonn GmbH");
  TEST_EQUAL(stadtwerke.m_key, "stadtwerke bonn", ());
  TEST_EQUAL(stadtwerke.m_name, "Stadtwerke Bonn", ());
  TEST_EQUAL(NormalizeProvider("stadtwerke bonn").m_key, stadtwerke.m_key, ());
  TEST_EQUAL(NormalizeProvider("  ").m_key, "", ());
}

UNIT_TEST(Station_Usable)
{
  auto const & elroq = Elroq();
  PlanOptions options;
  options.m_minPowerKw = 50.0;

  TEST(IsUsable(elroq, MakeStation(1, 10.0, "type2_combo|2|150"), options), ());
  // Socket of another vehicle.
  TEST(!IsUsable(elroq, MakeStation(2, 10.0, "chademo|2|50"), options), ());
  // Too slow.
  TEST(!IsUsable(elroq, MakeStation(3, 10.0, "type2|4|22"), options), ());
  options.m_minPowerKw = 150.0;
  TEST(!IsUsable(elroq, MakeStation(4, 10.0, "type2_combo|2|50"), options), ());
  TEST(IsUsable(elroq, MakeStation(5, 10.0, "type2_combo|2|300"), options), ());

  // Excluded provider and station.
  options.m_excludedProviders = {"enbw"};
  TEST(!IsUsable(elroq, MakeStation(6, 10.0, "type2_combo|2|300", "EnBW mobility+"), options), ());
  TEST(IsUsable(elroq, MakeStation(7, 10.0, "type2_combo|2|300", "Ionity"), options), ());
  auto const excluded = MakeStation(8, 10.0, "type2_combo|2|300", "Ionity");
  options.m_excludedStations = {excluded.GetKey()};
  TEST(!IsUsable(elroq, excluded, options), ());

  // Out of order.
  auto broken = MakeStation(9, 10.0, "type2_combo|2|300");
  broken.m_status.m_availability = Availability::OutOfOrder;
  TEST(!IsUsable(elroq, broken, options), ());

  // Closed at the arrival.
  PlanOptions night;
  night.m_departureTime = 1'794'200'000;  // Some moment; the station is closed all the time.
  auto closed = MakeStation(10, 10.0, "type2_combo|2|300");
  closed.m_openingHours = "off";
  TEST(!IsUsable(elroq, closed, night), ());
  closed.m_openingHours = "24/7";
  TEST(IsUsable(elroq, closed, night), ());
}

UNIT_TEST(Station_CountProviders)
{
  std::vector<ChargingStation> stations = {
      MakeStation(1, 1, "type2_combo|1|50", "EnBW"), MakeStation(2, 2, "type2_combo|1|50", "Ionity"),
      MakeStation(3, 3, "type2_combo|1|50", "EnBW mobility+"), MakeStation(4, 4, "type2_combo|1|50")};
  auto const counts = CountProviders(stations);
  TEST_EQUAL(counts.size(), 2, ());
  TEST_EQUAL(counts[0].m_provider.m_key, "enbw", ());
  TEST_EQUAL(counts[0].m_stations, 2, ());
  TEST_EQUAL(counts[1].m_provider.m_name, "Ionity", ());
}
}  // namespace station_tests
