#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace ev
{
// Charging power of the vehicle at a state of charge, measured at a DC fast charger that is not the bottleneck.
struct ChargeCurvePoint
{
  double m_socPct = 0.0;
  double m_powerKw = 0.0;
};

// An electric vehicle as far as the charging planner needs it.
//
// Consumption is given at two reference speeds on a level road in mild weather, as drivers know it from the
// trip computer. EnergyModel derives the consumption at other speeds, on slopes and in towns from it.
struct VehicleProfile
{
  // Reference speeds of the consumption values.
  static double constexpr kCountrySpeedKmh = 80.0;
  static double constexpr kMotorwaySpeedKmh = 130.0;

  // Checks the values the planner relies on, e.g. after reading a profile from the user.
  bool IsValid() const;

  // Charging power from the charge curve at |socPct|, limited by the maximum DC power.
  double GetDcPowerKw(double socPct) const;

  // True if the vehicle can use an OSM socket type (https://wiki.openstreetmap.org/wiki/Key:socket:*).
  bool HasSocket(std::string_view socketType) const;

  // Template id (e.g. "skoda_elroq_85"), or "custom" for a profile of the user.
  std::string m_id;
  std::string m_name;

  double m_usableCapacityKwh = 0.0;
  // Consumption at kCountrySpeedKmh and kMotorwaySpeedKmh, level road, mild weather, in kWh/100 km.
  double m_consumptionCountryKwhPer100Km = 0.0;
  double m_consumptionMotorwayKwhPer100Km = 0.0;
  // Heating, air conditioning, electronics. Matters at low speeds and in the cold.
  double m_auxiliaryPowerKw = 0.5;
  // With the driver and some luggage. Used for climbs and descents.
  double m_massKg = 0.0;
  // Share of the battery energy that moves the car, and share of the potential energy of a descent that the
  // recuperation brings back into the battery.
  double m_drivetrainEfficiency = 0.9;
  double m_recuperationEfficiency = 0.6;
  // Factor for all consumption, e.g. 1.2 in winter or with a roof box. 1 for the reference consumption.
  double m_consumptionFactor = 1.0;

  // OSM socket types, e.g. "type2_combo" (CCS), "type2", "chademo".
  std::vector<std::string> m_sockets;
  double m_maxDcPowerKw = 0.0;
  double m_maxAcPowerKw = 0.0;
  // Sorted by m_socPct, from 0 to 100 %.
  std::vector<ChargeCurvePoint> m_chargeCurve;

  // Where the values come from, for templates.
  std::string m_source;
};

// Vehicle templates with researched values, see vehicle_profile.cpp for the sources.
std::vector<VehicleProfile> const & GetVehicleTemplates();
VehicleProfile const * FindVehicleTemplate(std::string_view id);

// JSON (de)serialization, e.g. to store a profile of the user or to pass it from the Android app.
std::string ToJson(VehicleProfile const & profile);
std::optional<VehicleProfile> VehicleProfileFromJson(std::string_view json);

std::string DebugPrint(VehicleProfile const & profile);
}  // namespace ev
