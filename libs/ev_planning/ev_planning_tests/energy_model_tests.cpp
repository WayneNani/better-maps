#include "testing/testing.hpp"

#include "ev_planning/ev_planning_tests/test_helpers.hpp"

#include "ev_planning/energy_model.hpp"

#include "base/math.hpp"

#include <cmath>

namespace energy_model_tests
{
using namespace ev;
using namespace ev_planning_tests;

double constexpr kPotential100mKwh = 2215.0 * 9.81 * 100.0 / 3.6e6;  // Elroq, 100 m.

UNIT_TEST(EnergyModel_ReferenceSpeeds)
{
  EnergyModel const model(Elroq());
  // The model goes through the two reference values of the profile.
  TEST(AlmostEqualAbs(model.GetLevelConsumptionKwhPerKm(80.0) * 100.0, 12.9, 1e-6), ());
  TEST(AlmostEqualAbs(model.GetLevelConsumptionKwhPerKm(130.0) * 100.0, 23.5, 1e-6), ());
  // EV Database: 18.6 kWh/100 km at 110 km/h.
  TEST(AlmostEqualAbs(model.GetLevelConsumptionKwhPerKm(110.0) * 100.0, 18.6, 0.4),
       (model.GetLevelConsumptionKwhPerKm(110.0)));
}

UNIT_TEST(EnergyModel_SpeedDependence)
{
  EnergyModel const model(Elroq());
  double prev = 0.0;
  for (double speed : {40.0, 60.0, 80.0, 100.0, 120.0, 140.0, 160.0})
  {
    double const consumption = model.GetLevelConsumptionKwhPerKm(speed);
    TEST_GREATER(consumption, prev, (speed));
    prev = consumption;
  }
  // Very slow: the auxiliary consumers dominate (0.5 kW for 1 km at 5 km/h is 0.1 kWh).
  TEST_GREATER(model.GetLevelConsumptionKwhPerKm(5.0), model.GetLevelConsumptionKwhPerKm(30.0), ());

  // The energy of a piece of road follows the speed from length and duration.
  double const at130 = model.GetEnergyKwh(10000.0, 10.0 / 130.0 * 3600.0, 0.0, RoadKind::Fast);
  TEST(AlmostEqualAbs(at130, 2.35, 1e-6), (at130));
}

UNIT_TEST(EnergyModel_Slopes)
{
  EnergyModel const model(Elroq());
  double const lengthM = 10000.0;
  double const durationSec = 10.0 / 100.0 * 3600.0;
  double const level = model.GetEnergyKwh(lengthM, durationSec, 0.0, RoadKind::Main);

  // Up 100 m: the potential energy through the drivetrain.
  double const up = model.GetEnergyKwh(lengthM, durationSec, 100.0, RoadKind::Main);
  TEST(AlmostEqualAbs(up - level, kPotential100mKwh / 0.9, 1e-6), (up, level));

  // Down 100 m over 10 km: the car still needs propulsion, the descent saves it completely.
  double const down = model.GetEnergyKwh(lengthM, durationSec, -100.0, RoadKind::Main);
  TEST(AlmostEqualAbs(level - down, kPotential100mKwh / 0.9, 1e-6), (down, level));
  TEST_GREATER(down, 0.0, ());

  // Down 300 m over 2 km: the recuperation charges the battery, but only partially.
  double const shortLevel = model.GetEnergyKwh(2000.0, 72.0, 0.0, RoadKind::Main);
  double const steepDown = model.GetEnergyKwh(2000.0, 72.0, -300.0, RoadKind::Main);
  double const steepUp = model.GetEnergyKwh(2000.0, 72.0, 300.0, RoadKind::Main);
  TEST_LESS(steepDown, 0.0, ());
  TEST_GREATER(-steepDown, 0.0, ());
  TEST_LESS(-steepDown, 3.0 * kPotential100mKwh * 0.6, ());
  // Up and down again costs more than the level road: the losses of the drivetrain and the recuperation.
  TEST_GREATER(steepUp + steepDown, 2.0 * shortLevel, ());
}

UNIT_TEST(EnergyModel_MinorRoadsAndFactor)
{
  EnergyModel const model(Elroq());
  double const main = model.GetEnergyKwh(5000.0, 5.0 / 40.0 * 3600.0, 0.0, RoadKind::Main);
  double const minor = model.GetEnergyKwh(5000.0, 5.0 / 40.0 * 3600.0, 0.0, RoadKind::Minor);
  TEST_GREATER(minor, main, ());
  TEST_LESS(minor, main * EnergyModel::kMinorRoadFactor, ());

  VehicleProfile winter = Elroq();
  winter.m_consumptionFactor = 1.2;
  EnergyModel const winterModel(winter);
  TEST(AlmostEqualAbs(winterModel.GetLevelConsumptionKwhPerKm(80.0), 1.2 * model.GetLevelConsumptionKwhPerKm(80.0),
                      1e-9),
       ());
}

double Hill(double km)
{
  // Up to 400 m at km 50, down again until km 100.
  return km <= 50.0 ? 100.0 + 6.0 * km : 400.0 - 6.0 * (km - 50.0);
}

double Noise(double km)
{
  // +-5 m every 500 m.
  return 200.0 + (static_cast<int>(std::lround(km * 2.0)) % 2 == 0 ? 5.0 : -5.0);
}

UNIT_TEST(EnergyModel_Route)
{
  auto const & profile = Elroq();
  EnergyModel const model(profile);

  auto const flat = ComputeRouteEnergy(model, MakeRoutePoints(100.0, 130.0));
  TEST(AlmostEqualAbs(flat.GetLengthM(), 100000.0, 1e-6), ());
  TEST(AlmostEqualAbs(flat.GetDurationSec(), 100.0 / 130.0 * 3600.0, 1e-6), ());
  TEST(AlmostEqualAbs(flat.GetTotalEnergyKwh(), 23.5, 1e-6), (flat.GetTotalEnergyKwh()));
  TEST(AlmostEqualAbs(flat.GetEnergyKwhAt(50000.0), 11.75, 1e-6), ());
  TEST(AlmostEqualAbs(flat.GetEnergyKwh(25000.0, 75000.0), 11.75, 1e-6), ());

  // Up 300 m and down again: the climb costs more than the descent gives back.
  auto const hill = ComputeRouteEnergy(model, MakeRoutePoints(100.0, 130.0, &Hill));
  TEST_GREATER(hill.GetEnergyKwhAt(50000.0), flat.GetEnergyKwhAt(50000.0), ());
  TEST_GREATER_OR_EQUAL(hill.GetTotalEnergyKwh(), flat.GetTotalEnergyKwh() - 1e-6, ());
  double const climbKwh = hill.GetEnergyKwhAt(50000.0) - flat.GetEnergyKwhAt(50000.0);
  TEST(AlmostEqualAbs(climbKwh, 3.0 * kPotential100mKwh / 0.9, 0.05), (climbKwh));

  // Noise of the altitudes does not add up.
  auto const noisy = ComputeRouteEnergy(model, MakeRoutePoints(100.0, 130.0, &Noise));
  TEST(AlmostEqualAbs(noisy.GetTotalEnergyKwh(), flat.GetTotalEnergyKwh(), 0.05), (noisy.GetTotalEnergyKwh()));
}

UNIT_TEST(EnergyModel_RouteWithoutAltitudes)
{
  auto points = MakeRoutePoints(10.0, 50.0);
  for (auto & p : points)
    p.m_altitudeM = std::numeric_limits<double>::quiet_NaN();
  auto const route = ComputeRouteEnergy(EnergyModel(Elroq()), points);
  TEST(AlmostEqualAbs(route.GetTotalEnergyKwh(), EnergyModel(Elroq()).GetLevelConsumptionKwhPerKm(50.0) * 10.0, 1e-6),
       ());
}
}  // namespace energy_model_tests
