#pragma once

#include "ev_planning/vehicle_profile.hpp"

#include <cstdint>
#include <limits>
#include <string>
#include <vector>

namespace ev
{
// Kind of road, as far as it matters for the consumption.
enum class RoadKind : uint8_t
{
  // Motorways and trunk roads: constant speed.
  Fast,
  // Primary to tertiary roads outside of towns.
  Main,
  // Residential and other minor roads: stop and go in towns.
  Minor,
  Unknown,
};

std::string DebugPrint(RoadKind kind);

// Altitude of a route point that is not known. Not NaN: the code is built with -ffast-math.
double constexpr kUnknownAltitude = -1e6;

inline bool IsKnownAltitude(double altitudeM)
{
  return altitudeM > kUnknownAltitude / 2.0;
}

// A point of a route with the distance and travel time from the route start.
struct RoutePoint
{
  double m_distanceM = 0.0;
  double m_timeSec = 0.0;
  // kUnknownAltitude if unknown.
  double m_altitudeM = kUnknownAltitude;
  // Kind of the road from the previous point to this one.
  RoadKind m_roadKind = RoadKind::Unknown;
};

// Energy consumption of a vehicle.
//
// On a level road at a constant speed v (km/h) the battery delivers, in kWh/100 km:
//   E(v) = A + B * v^2 + 100 * P_aux / v
// A is the rolling resistance, B * v^2 the air resistance and P_aux (kW) the auxiliary consumers. A and B come from
// the two reference values of the profile (country road at 80 km/h, motorway at 130 km/h).
//
// On slopes, the wheels need the level energy plus the potential energy m * g * dh. If that sum is positive the
// battery delivers it divided by the drivetrain efficiency, otherwise the recuperation brings a share of it back.
// Stop and go on minor roads costs kMinorRoadFactor more.
class EnergyModel
{
public:
  static double constexpr kGravity = 9.81;
  static double constexpr kMinorRoadFactor = 1.15;
  static double constexpr kMinSpeedKmh = 5.0;

  explicit EnergyModel(VehicleProfile const & profile);

  // Battery energy per km on a level road at a constant speed, without kMinorRoadFactor.
  double GetLevelConsumptionKwhPerKm(double speedKmh) const;

  // Battery energy for a piece of road (negative if the recuperation charges the battery).
  double GetEnergyKwh(double lengthM, double durationSec, double climbM, RoadKind kind) const;

  VehicleProfile const & GetProfile() const { return m_profile; }

private:
  VehicleProfile m_profile;
  double m_rollingKwhPer100Km = 0.0;
  double m_airKwhPer100KmPerKmh2 = 0.0;
};

// Cumulative battery energy and travel time along a route, see ComputeRouteEnergy().
class RouteEnergy
{
public:
  RouteEnergy() = default;
  RouteEnergy(std::vector<double> distancesM, std::vector<double> timesSec, std::vector<double> energiesKwh);

  bool IsEmpty() const { return m_distancesM.size() < 2; }
  double GetLengthM() const { return m_distancesM.empty() ? 0.0 : m_distancesM.back(); }
  double GetDurationSec() const { return m_timesSec.empty() ? 0.0 : m_timesSec.back(); }
  double GetTotalEnergyKwh() const { return m_energiesKwh.empty() ? 0.0 : m_energiesKwh.back(); }

  // Linear interpolation between the route points, clamped to the route.
  double GetEnergyKwhAt(double distanceM) const;
  double GetTimeSecAt(double distanceM) const;

  // Energy between two points along the route, e.g. from a charging stop to the next one.
  double GetEnergyKwh(double fromM, double toM) const { return GetEnergyKwhAt(toM) - GetEnergyKwhAt(fromM); }

  std::vector<double> const & GetDistancesM() const { return m_distancesM; }
  std::vector<double> const & GetEnergiesKwh() const { return m_energiesKwh; }

private:
  double Interpolate(std::vector<double> const & values, double distanceM) const;

  std::vector<double> m_distancesM;
  std::vector<double> m_timesSec;
  std::vector<double> m_energiesKwh;
};

// Energy along a route. Points closer than |minStepM| are merged, so that the noise of the altitudes does not add
// up to a climb (the efficiency differs between up and down).
RouteEnergy ComputeRouteEnergy(EnergyModel const & model, std::vector<RoutePoint> const & points,
                               double minStepM = 200.0);
}  // namespace ev
