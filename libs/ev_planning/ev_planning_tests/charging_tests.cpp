#include "testing/testing.hpp"

#include "ev_planning/ev_planning_tests/test_helpers.hpp"

#include "ev_planning/charging.hpp"
#include "ev_planning/station.hpp"

#include "base/math.hpp"

#include <map>
#include <string>

namespace charging_tests
{
using namespace ev;
using namespace ev_planning_tests;

ChargingPower Dc(double kw)
{
  return {kw, true, true};
}

UNIT_TEST(Charging_TemplatesChargeTime)
{
  // 10 -> 80 % at a station that does not limit the vehicle, as stated by the sources (see vehicle_profile.cpp).
  std::map<std::string, double> const statedMinutes = {{"skoda_elroq_85", 28.0},    {"skoda_elroq_85_my27", 30.0},
                                                       {"vw_id3_pro", 25.0},        {"tesla_model_y_lr", 27.0},
                                                       {"hyundai_ioniq5_84", 18.0}, {"renault_megane_ev60", 33.0}};
  TEST_EQUAL(GetVehicleTemplates().size(), statedMinutes.size(), ());
  for (auto const & profile : GetVehicleTemplates())
  {
    TEST(profile.IsValid(), (profile));
    auto const it = statedMinutes.find(profile.m_id);
    TEST(it != statedMinutes.end(), (profile.m_id));
    double const minutes = GetChargingTimeSec(profile, Dc(400.0), 10.0, 80.0) / 60.0;
    TEST(AlmostEqualAbs(minutes, it->second, it->second * 0.1), (profile.m_id, minutes));
  }
}

UNIT_TEST(Charging_ChargeCurve)
{
  auto const & elroq = Elroq();
  TEST_EQUAL(elroq.GetDcPowerKw(0.0), 113.0, ());
  TEST_EQUAL(elroq.GetDcPowerKw(25.0), 135.0, ());
  TEST(AlmostEqualAbs(elroq.GetDcPowerKw(55.0), 119.0, 1e-9), ());
  TEST_EQUAL(elroq.GetDcPowerKw(100.0), 12.0, ());
  // Charging slows down: 70 -> 80 % takes longer than 20 -> 30 %.
  TEST_GREATER(GetChargingTimeSec(elroq, Dc(400.0), 70.0, 80.0), GetChargingTimeSec(elroq, Dc(400.0), 20.0, 30.0), ());
  // 20 -> 30 % at the 135 kW plateau: 7.7 kWh in 205 s.
  TEST(AlmostEqualAbs(GetChargingTimeSec(elroq, Dc(400.0), 20.0, 30.0), 7.7 / 135.0 * 3600.0, 1e-6), ());
}

UNIT_TEST(Charging_LimitedByStation)
{
  auto const & elroq = Elroq();
  // At 50 kW the curve (above 73 kW up to 80 %) does not matter: 53.9 kWh / 50 kW.
  TEST(AlmostEqualAbs(GetChargingTimeSec(elroq, Dc(50.0), 10.0, 80.0), 53.9 / 50.0 * 3600.0, 1.0), ());
  // A 300 kW station does not charge faster than the car can.
  TEST(AlmostEqualAbs(GetChargingTimeSec(elroq, Dc(300.0), 10.0, 80.0), GetChargingTimeSec(elroq, Dc(135.0), 10, 80),
                      1e-6),
       ());
  // A 150 kW station is limited by the car to 135 kW, where the curve is flat: like a 135 kW station.
  TEST(AlmostEqualAbs(GetChargingTimeSec(elroq, Dc(150.0), 20.0, 40.0), 15.4 / 135.0 * 3600.0, 1e-6), ());

  // AC: the on-board charger (11 kW) limits a 22 kW station.
  ChargingPower const ac22{22.0, false, true};
  TEST(AlmostEqualAbs(GetChargingTimeSec(elroq, ac22, 10.0, 80.0), 53.9 / 11.0 * 3600.0, 1.0), ());
  // Nothing to charge.
  TEST_EQUAL(GetChargingTimeSec(elroq, Dc(150.0), 80.0, 80.0), 0.0, ());
  TEST_EQUAL(GetChargingTimeSec(elroq, Dc(150.0), 80.0, 70.0), 0.0, ());
}

UNIT_TEST(Charging_Table)
{
  auto const & elroq = Elroq();
  ChargingTimeTable const table(elroq, Dc(150.0));
  for (auto const & [from, to] : {std::pair{10.0, 80.0}, {0.0, 100.0}, {35.5, 36.0}, {42.0, 91.5}})
    TEST(AlmostEqualAbs(table.GetTimeSec(from, to), GetChargingTimeSec(elroq, Dc(150.0), from, to), 1e-6), (from, to));
  TEST_EQUAL(table.GetTimeSec(50.0, 40.0), 0.0, ());
}

UNIT_TEST(Charging_BestSocket)
{
  auto const & elroq = Elroq();
  // The fastest socket the car can use.
  auto power =
      GetBestChargingPower(elroq, ChargeSocketsHelper("type2_combo|4|300;type2_combo|4|150;type2|2|22").GetSockets());
  TEST(power.IsValid(), ());
  TEST(power.m_isDc, ());
  TEST_EQUAL(power.m_powerKw, 300.0, ());
  TEST(power.m_isPowerKnown, ());

  // CHAdeMO only: not for this car.
  TEST(!GetBestChargingPower(elroq, ChargeSocketsHelper("chademo|2|50").GetSockets()).IsValid(), ());

  // Unknown power: assumed.
  power = GetBestChargingPower(elroq, ChargeSocketsHelper("type2_combo|2|;type2|2|22").GetSockets());
  TEST(power.m_isDc, ());
  TEST_EQUAL(power.m_powerKw, kAssumedDcPowerKw, ());
  TEST(!power.m_isPowerKnown, ());

  // AC only.
  power = GetBestChargingPower(elroq, ChargeSocketsHelper("type2|2|22").GetSockets());
  TEST(!power.m_isDc, ());
  TEST_EQUAL(power.m_powerKw, 22.0, ());
}
}  // namespace charging_tests
