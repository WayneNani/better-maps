#include "ev_planning/charging.hpp"

#include <algorithm>
#include <cmath>

namespace ev
{
namespace
{
// The on-board charger reduces the power near a full battery as well.
double constexpr kAcTaperStartPct = 95.0;
double constexpr kMinPowerKw = 1.0;
}  // namespace

double GetChargingPowerKw(VehicleProfile const & profile, ChargingPower const & station, double socPct)
{
  double power = 0.0;
  if (station.m_isDc)
  {
    power = std::min(profile.GetDcPowerKw(socPct), station.m_powerKw);
  }
  else
  {
    power = std::min(profile.m_maxAcPowerKw, station.m_powerKw);
    if (socPct > kAcTaperStartPct)
      power *= std::max(0.3, (100.0 - socPct) / (100.0 - kAcTaperStartPct));
  }
  return std::max(power, kMinPowerKw);
}

double GetChargingTimeSec(VehicleProfile const & profile, ChargingPower const & station, double fromSocPct,
                          double toSocPct)
{
  fromSocPct = std::clamp(fromSocPct, 0.0, 100.0);
  toSocPct = std::clamp(toSocPct, 0.0, 100.0);
  if (toSocPct <= fromSocPct || !station.IsValid())
    return 0.0;

  double constexpr kStepPct = ChargingTimeTable::kStepPct;
  double timeSec = 0.0;
  for (double soc = fromSocPct; soc < toSocPct; soc += kStepPct)
  {
    double const step = std::min(kStepPct, toSocPct - soc);
    double const energyKwh = profile.m_usableCapacityKwh * step / 100.0;
    // Midpoint rule.
    timeSec += energyKwh / GetChargingPowerKw(profile, station, soc + step / 2.0) * 3600.0;
  }
  return timeSec;
}

ChargingTimeTable::ChargingTimeTable(VehicleProfile const & profile, ChargingPower const & station)
{
  size_t const steps = static_cast<size_t>(std::lround(100.0 / kStepPct));
  m_timesSec.reserve(steps + 1);
  m_timesSec.push_back(0.0);
  for (size_t i = 0; i < steps; ++i)
  {
    double const soc = i * kStepPct;
    m_timesSec.push_back(m_timesSec.back() + GetChargingTimeSec(profile, station, soc, soc + kStepPct));
  }
}

double ChargingTimeTable::GetTimeSec(double fromSocPct, double toSocPct) const
{
  auto const index = [this](double soc)
  {
    auto const i = static_cast<long>(std::lround(soc / kStepPct));
    return static_cast<size_t>(std::clamp<long>(i, 0, static_cast<long>(m_timesSec.size()) - 1));
  };
  size_t const from = index(fromSocPct);
  size_t const to = index(toSocPct);
  return to > from ? m_timesSec[to] - m_timesSec[from] : 0.0;
}
}  // namespace ev
