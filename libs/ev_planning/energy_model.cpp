#include "ev_planning/energy_model.hpp"

#include "base/assert.hpp"

#include <algorithm>
#include <cmath>

namespace ev
{
std::string DebugPrint(RoadKind kind)
{
  switch (kind)
  {
  case RoadKind::Fast: return "Fast";
  case RoadKind::Main: return "Main";
  case RoadKind::Minor: return "Minor";
  case RoadKind::Unknown: return "Unknown";
  }
  UNREACHABLE();
}

EnergyModel::EnergyModel(VehicleProfile const & profile) : m_profile(profile)
{
  // E(v) = A + B * v^2 + 100 * P_aux / v through the two reference points.
  double constexpr v1 = VehicleProfile::kCountrySpeedKmh;
  double constexpr v2 = VehicleProfile::kMotorwaySpeedKmh;
  double const e1 = profile.m_consumptionCountryKwhPer100Km - 100.0 * profile.m_auxiliaryPowerKw / v1;
  double const e2 = profile.m_consumptionMotorwayKwhPer100Km - 100.0 * profile.m_auxiliaryPowerKw / v2;
  m_airKwhPer100KmPerKmh2 = std::max(0.0, (e2 - e1) / (v2 * v2 - v1 * v1));
  m_rollingKwhPer100Km = std::max(0.0, e1 - m_airKwhPer100KmPerKmh2 * v1 * v1);
}

double EnergyModel::GetLevelConsumptionKwhPerKm(double speedKmh) const
{
  double const v = std::max(speedKmh, kMinSpeedKmh);
  double const per100Km =
      m_rollingKwhPer100Km + m_airKwhPer100KmPerKmh2 * v * v + 100.0 * m_profile.m_auxiliaryPowerKw / v;
  return per100Km / 100.0 * m_profile.m_consumptionFactor;
}

double EnergyModel::GetEnergyKwh(double lengthM, double durationSec, double climbM, RoadKind kind) const
{
  if (lengthM <= 0.0)
    return 0.0;

  double const speedKmh = durationSec > 0.0 ? lengthM / durationSec * 3.6 : 50.0;
  double const lengthKm = lengthM / 1000.0;
  double const hours = lengthKm / std::max(speedKmh, kMinSpeedKmh);
  double const auxKwh = m_profile.m_auxiliaryPowerKw * hours * m_profile.m_consumptionFactor;

  double levelKwh = GetLevelConsumptionKwhPerKm(speedKmh) * lengthKm - auxKwh;
  if (kind == RoadKind::Minor)
    levelKwh *= kMinorRoadFactor;

  // Energy at the wheels: level driving plus the potential energy.
  double const potentialKwh = m_profile.m_massKg * kGravity * climbM / 3.6e6;
  double const wheelKwh = levelKwh * m_profile.m_drivetrainEfficiency + potentialKwh;
  double const batteryKwh =
      wheelKwh >= 0.0 ? wheelKwh / m_profile.m_drivetrainEfficiency : wheelKwh * m_profile.m_recuperationEfficiency;
  return batteryKwh + auxKwh;
}

RouteEnergy::RouteEnergy(std::vector<double> distancesM, std::vector<double> timesSec, std::vector<double> energiesKwh)
  : m_distancesM(std::move(distancesM))
  , m_timesSec(std::move(timesSec))
  , m_energiesKwh(std::move(energiesKwh))
{
  CHECK_EQUAL(m_distancesM.size(), m_timesSec.size(), ());
  CHECK_EQUAL(m_distancesM.size(), m_energiesKwh.size(), ());
}

double RouteEnergy::Interpolate(std::vector<double> const & values, double distanceM) const
{
  if (m_distancesM.empty())
    return 0.0;
  if (distanceM <= m_distancesM.front())
    return values.front();
  if (distanceM >= m_distancesM.back())
    return values.back();

  auto const it = std::upper_bound(m_distancesM.begin(), m_distancesM.end(), distanceM);
  size_t const i = static_cast<size_t>(it - m_distancesM.begin());
  double const d0 = m_distancesM[i - 1];
  double const d1 = m_distancesM[i];
  if (d1 <= d0)
    return values[i];
  return values[i - 1] + (values[i] - values[i - 1]) * (distanceM - d0) / (d1 - d0);
}

double RouteEnergy::GetEnergyKwhAt(double distanceM) const
{
  return Interpolate(m_energiesKwh, distanceM);
}

double RouteEnergy::GetTimeSecAt(double distanceM) const
{
  return Interpolate(m_timesSec, distanceM);
}

RouteEnergy ComputeRouteEnergy(EnergyModel const & model, std::vector<RoutePoint> const & points, double minStepM)
{
  std::vector<double> distances;
  std::vector<double> times;
  std::vector<double> energies;
  if (points.empty())
    return {};

  distances.push_back(points.front().m_distanceM);
  times.push_back(points.front().m_timeSec);
  energies.push_back(0.0);

  // The last known altitude, to bridge points without altitudes.
  double lastAltitude = points.front().m_altitudeM;
  double stepStartAltitude = lastAltitude;
  // Length of the step by road kind, to weight the stop and go factor.
  double minorM = 0.0;

  size_t stepStart = 0;
  for (size_t i = 1; i < points.size(); ++i)
  {
    auto const & p = points[i];
    if (!std::isnan(p.m_altitudeM))
    {
      lastAltitude = p.m_altitudeM;
      if (std::isnan(stepStartAltitude))
        stepStartAltitude = p.m_altitudeM;
    }
    if (p.m_roadKind == RoadKind::Minor)
      minorM += p.m_distanceM - points[i - 1].m_distanceM;

    double const stepM = p.m_distanceM - points[stepStart].m_distanceM;
    if (stepM < minStepM && i + 1 < points.size())
      continue;

    double const durationSec = p.m_timeSec - points[stepStart].m_timeSec;
    double const climbM =
        std::isnan(stepStartAltitude) || std::isnan(lastAltitude) ? 0.0 : lastAltitude - stepStartAltitude;
    // Split the step into its minor and other parts, both at the average speed of the step.
    double const minorShare = stepM > 0.0 ? std::min(1.0, minorM / stepM) : 0.0;
    double const energy =
        model.GetEnergyKwh(stepM * minorShare, durationSec * minorShare, climbM * minorShare, RoadKind::Minor) +
        model.GetEnergyKwh(stepM * (1.0 - minorShare), durationSec * (1.0 - minorShare), climbM * (1.0 - minorShare),
                           RoadKind::Main);

    distances.push_back(p.m_distanceM);
    times.push_back(p.m_timeSec);
    energies.push_back(energies.back() + energy);

    stepStart = i;
    stepStartAltitude = lastAltitude;
    minorM = 0.0;
  }
  return RouteEnergy(std::move(distances), std::move(times), std::move(energies));
}
}  // namespace ev
