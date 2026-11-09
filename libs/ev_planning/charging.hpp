#pragma once

#include "ev_planning/vehicle_profile.hpp"

#include <vector>

namespace ev
{
// What a charging station offers to a vehicle: the best socket the vehicle can use.
struct ChargingPower
{
  bool IsValid() const { return m_powerKw > 0.0; }

  // Power of the socket, or an assumed one if unknown (see m_isPowerKnown).
  double m_powerKw = 0.0;
  bool m_isDc = false;
  bool m_isPowerKnown = true;
};

// Charging power at |socPct|: the charge curve for DC, the on-board charger for AC, both limited by the station.
double GetChargingPowerKw(VehicleProfile const & profile, ChargingPower const & station, double socPct);

// Time to charge from |fromSocPct| to |toSocPct|, by integrating over the state of charge in kStepPct steps.
double GetChargingTimeSec(VehicleProfile const & profile, ChargingPower const & station, double fromSocPct,
                          double toSocPct);

// Charging times from 0 % to every step of the state of charge, to look up many charging times quickly.
class ChargingTimeTable
{
public:
  static double constexpr kStepPct = 0.5;

  ChargingTimeTable(VehicleProfile const & profile, ChargingPower const & station);

  // |fromSocPct| and |toSocPct| are rounded to the steps.
  double GetTimeSec(double fromSocPct, double toSocPct) const;

private:
  std::vector<double> m_timesSec;
};
}  // namespace ev
