#include "testing/testing.hpp"

#include "ev_planning/ev_planning_tests/test_helpers.hpp"

#include "ev_planning/vehicle_profile.hpp"

namespace vehicle_profile_tests
{
using namespace ev;
using namespace ev_planning_tests;

UNIT_TEST(VehicleProfile_Templates)
{
  auto const & elroq = Elroq();
  TEST_EQUAL(elroq.m_name, "Škoda Elroq 85", ());
  TEST_EQUAL(elroq.m_usableCapacityKwh, 77.0, ());
  TEST_EQUAL(elroq.m_maxDcPowerKw, 135.0, ());
  TEST_EQUAL(elroq.m_maxAcPowerKw, 11.0, ());
  TEST(elroq.HasSocket("type2_combo"), ());
  TEST(elroq.HasSocket("type2"), ());
  TEST(!elroq.HasSocket("chademo"), ());
  TEST(!elroq.m_source.empty(), ());
  TEST(FindVehicleTemplate("unknown") == nullptr, ());
  for (auto const & t : GetVehicleTemplates())
    TEST(t.IsValid(), (t));
}

UNIT_TEST(VehicleProfile_Json)
{
  VehicleProfile custom = Elroq();
  custom.m_id = "custom";
  custom.m_name = "Mein Elroq";
  custom.m_consumptionFactor = 1.15;
  custom.m_sockets.push_back("chademo");

  auto const json = ToJson(custom);
  auto const read = VehicleProfileFromJson(json);
  TEST(read, (json));
  TEST_EQUAL(read->m_name, "Mein Elroq", ());
  TEST_EQUAL(read->m_consumptionFactor, 1.15, ());
  TEST_EQUAL(read->m_sockets, custom.m_sockets, ());
  TEST_EQUAL(read->m_chargeCurve.size(), custom.m_chargeCurve.size(), ());
  TEST_EQUAL(read->m_chargeCurve[5].m_powerKw, custom.m_chargeCurve[5].m_powerKw, ());
  TEST_EQUAL(ToJson(*read), json, ());

  // Missing values keep their defaults, unknown keys are ignored.
  auto const partial = VehicleProfileFromJson(
      R"({"id":"custom","name":"Small","usableCapacityKwh":40,"consumptionCountry":12,"consumptionMotorway":20,)"
      R"("massKg":1500,"sockets":["type2"],"maxAcPowerKw":11,"futureKey":1})");
  TEST(partial, ());
  TEST_EQUAL(partial->m_drivetrainEfficiency, 0.9, ());
  TEST(partial->IsValid(), ());

  TEST(!VehicleProfileFromJson("{not json"), ());
}

UNIT_TEST(VehicleProfile_Invalid)
{
  VehicleProfile p = Elroq();
  TEST(p.IsValid(), ());
  p.m_usableCapacityKwh = 0;
  TEST(!p.IsValid(), ());

  p = Elroq();
  p.m_consumptionMotorwayKwhPer100Km = p.m_consumptionCountryKwhPer100Km;
  TEST(!p.IsValid(), ());

  p = Elroq();
  p.m_sockets.clear();
  TEST(!p.IsValid(), ());

  p = Elroq();
  std::swap(p.m_chargeCurve[1], p.m_chargeCurve[2]);
  TEST(!p.IsValid(), ());
}
}  // namespace vehicle_profile_tests
