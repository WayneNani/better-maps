#include "ev_planning/vehicle_profile.hpp"

#include "base/logging.hpp"

#include <algorithm>
#include <cmath>
#include <sstream>

#include "glaze/json.hpp"

template <>
struct glz::meta<ev::ChargeCurvePoint>
{
  using T = ev::ChargeCurvePoint;
  static constexpr auto value = glz::object("soc", &T::m_socPct, "kw", &T::m_powerKw);
};

template <>
struct glz::meta<ev::VehicleProfile>
{
  using T = ev::VehicleProfile;
  static constexpr auto value = glz::object(
      "id", &T::m_id, "name", &T::m_name, "usableCapacityKwh", &T::m_usableCapacityKwh, "consumptionCountry",
      &T::m_consumptionCountryKwhPer100Km, "consumptionMotorway", &T::m_consumptionMotorwayKwhPer100Km,
      "auxiliaryPowerKw", &T::m_auxiliaryPowerKw, "massKg", &T::m_massKg, "drivetrainEfficiency",
      &T::m_drivetrainEfficiency, "recuperationEfficiency", &T::m_recuperationEfficiency, "consumptionFactor",
      &T::m_consumptionFactor, "sockets", &T::m_sockets, "maxDcPowerKw", &T::m_maxDcPowerKw, "maxAcPowerKw",
      &T::m_maxAcPowerKw, "chargeCurve", &T::m_chargeCurve, "source", &T::m_source);
};

namespace ev
{
namespace
{
// Templates.
//
// Values from the manufacturer where it publishes them, otherwise from EV Database (ev-database.org, "Real Energy
// Consumption" and "Fast Charging" tables, retrieved 2026-11-09), which measures and estimates them consistently for
// all models. Everything else is an estimate:
//
// - Consumption: EV Database gives the "Highway" consumption at a constant 110 km/h in mild weather (23 °C, no air
//   conditioning). The values at 80 and 130 km/h are derived from it with the shape of EnergyModel (rolling
//   resistance 0.0085, auxiliary power 0.5 kW, mass + 100 kg, drivetrain efficiency 0.9; the air resistance takes the
//   rest), i.e. they are estimates.
// - Charge curves: shape of the platform (VW MEB, Tesla, Hyundai E-GMP, Renault CMF-EV), peak power as stated,
//   scaled so that 10 -> 80 % takes the stated time (checked by EvPlanning_TemplatesChargeTime). Estimates.
// - Mass: EU unladen weight (with a 75 kg driver) + 100 kg luggage/passenger.
std::vector<ChargeCurvePoint> Curve(std::vector<double> const & powersEvery10Pct)
{
  std::vector<ChargeCurvePoint> curve;
  for (size_t i = 0; i < powersEvery10Pct.size(); ++i)
    curve.push_back({10.0 * i, powersEvery10Pct[i]});
  return curve;
}

std::vector<VehicleProfile> MakeTemplates()
{
  std::vector<VehicleProfile> templates;

  // Škoda Elroq 85, model year 2025/2026 (rear-wheel drive, 210 kW).
  // Škoda press kit "Batteries and powertrains" (9 Dec 2024, skoda-storyboard.com): 82/77 kWh gross/net, "takes 28
  // minutes to charge from 10% to 80%, with a maximum charging capacity of 135 kW", 11 kW AC.
  // EV Database (car 3033): unladen 2115 kg, 186 Wh/km at 110 km/h (mild).
  {
    VehicleProfile p;
    p.m_id = "skoda_elroq_85";
    p.m_name = "Škoda Elroq 85";
    p.m_usableCapacityKwh = 77.0;
    p.m_consumptionCountryKwhPer100Km = 12.9;   // Estimate, see above.
    p.m_consumptionMotorwayKwhPer100Km = 23.5;  // Estimate, see above.
    p.m_massKg = 2215;
    p.m_sockets = {"type2_combo", "type2"};
    p.m_maxDcPowerKw = 135;
    p.m_maxAcPowerKw = 11;
    p.m_chargeCurve = Curve({113, 135, 135, 135, 135, 129, 109, 91, 73, 45, 12});  // Estimate: 10-80 % in 28 min.
    p.m_source =
        "Škoda press kit (12/2024): 77 kWh, 135 kW DC, 10-80 % 28 min, 11 kW AC; EV Database: 2115 kg, "
        "18.6 kWh/100 km at 110 km/h. Consumption at 80/130 km/h and charge curve estimated.";
    templates.push_back(std::move(p));
  }
  // Škoda Elroq 85, model year 2027 (on sale since April 2026).
  // EV Database (car 3566): 77 kWh, 165 kW DC, 10-80 % 30 min, 11 kW AC, unladen 2131 kg, 186 Wh/km at 110 km/h.
  {
    VehicleProfile p;
    p.m_id = "skoda_elroq_85_my27";
    p.m_name = "Škoda Elroq 85 (MY27)";
    p.m_usableCapacityKwh = 77.0;
    p.m_consumptionCountryKwhPer100Km = 12.9;
    p.m_consumptionMotorwayKwhPer100Km = 23.5;
    p.m_massKg = 2231;
    p.m_sockets = {"type2_combo", "type2"};
    p.m_maxDcPowerKw = 165;
    p.m_maxAcPowerKw = 11;
    p.m_chargeCurve = Curve({114, 165, 165, 151, 130, 111, 91, 75, 59, 36, 10});
    p.m_source =
        "EV Database (MY27): 77 kWh, 165 kW DC, 10-80 % 30 min, 11 kW AC, 2131 kg, 18.6 kWh/100 km at "
        "110 km/h. Consumption at 80/130 km/h and charge curve estimated.";
    templates.push_back(std::move(p));
  }
  // VW ID.3 Pro (MY24-26). EV Database (car 2213): 59 kWh, 165 kW DC, 10-80 % 25 min, 11 kW AC, unladen 1815 kg,
  // 174 Wh/km at 110 km/h.
  {
    VehicleProfile p;
    p.m_id = "vw_id3_pro";
    p.m_name = "VW ID.3 Pro";
    p.m_usableCapacityKwh = 59.0;
    p.m_consumptionCountryKwhPer100Km = 11.9;
    p.m_consumptionMotorwayKwhPer100Km = 22.1;
    p.m_massKg = 1915;
    p.m_sockets = {"type2_combo", "type2"};
    p.m_maxDcPowerKw = 165;
    p.m_maxAcPowerKw = 11;
    p.m_chargeCurve = Curve({106, 165, 165, 136, 118, 100, 83, 68, 53, 33, 9});
    p.m_source =
        "EV Database (MY24-26): 59 kWh, 165 kW DC, 10-80 % 25 min, 11 kW AC, 1815 kg, 17.4 kWh/100 km at "
        "110 km/h. Consumption at 80/130 km/h and charge curve estimated.";
    templates.push_back(std::move(p));
  }
  // Tesla Model Y Long Range RWD (2024-2025). EV Database (car 2186): 75 kWh, 250 kW DC, 10-80 % 27 min, 11 kW AC,
  // unladen 1959 kg, 176 Wh/km at 110 km/h. CCS2 in Europe.
  {
    VehicleProfile p;
    p.m_id = "tesla_model_y_lr";
    p.m_name = "Tesla Model Y Long Range";
    p.m_usableCapacityKwh = 75.0;
    p.m_consumptionCountryKwhPer100Km = 12.2;
    p.m_consumptionMotorwayKwhPer100Km = 22.2;
    p.m_massKg = 2059;
    p.m_sockets = {"type2_combo", "type2"};
    p.m_maxDcPowerKw = 250;
    p.m_maxAcPowerKw = 11;
    p.m_chargeCurve = Curve({181, 250, 203, 181, 140, 113, 95, 77, 59, 36, 11});
    p.m_source =
        "EV Database (2024-2025): 75 kWh, 250 kW DC, 10-80 % 27 min, 11 kW AC, 1959 kg, 17.6 kWh/100 km at "
        "110 km/h. Consumption at 80/130 km/h and charge curve estimated.";
    templates.push_back(std::move(p));
  }
  // Hyundai IONIQ 5 84 kWh RWD (MY24). EV Database (car 2236): 80 kWh, 263 kW DC, 10-80 % 18 min, 11 kW AC,
  // unladen 2060 kg, 193 Wh/km at 110 km/h.
  {
    VehicleProfile p;
    p.m_id = "hyundai_ioniq5_84";
    p.m_name = "Hyundai IONIQ 5 84 kWh";
    p.m_usableCapacityKwh = 80.0;
    p.m_consumptionCountryKwhPer100Km = 13.2;
    p.m_consumptionMotorwayKwhPer100Km = 24.5;
    p.m_massKg = 2160;
    p.m_sockets = {"type2_combo", "type2"};
    p.m_maxDcPowerKw = 263;
    p.m_maxAcPowerKw = 11;
    p.m_chargeCurve = Curve({110, 180, 215, 240, 215, 200, 185, 165, 105, 45, 10});
    p.m_source =
        "EV Database (MY24): 80 kWh, 263 kW DC, 10-80 % 18 min, 11 kW AC, 2060 kg, 19.3 kWh/100 km at "
        "110 km/h. Consumption at 80/130 km/h and charge curve estimated.";
    templates.push_back(std::move(p));
  }
  // Renault Mégane E-Tech EV60 220 hp (2025). EV Database (car 3132): 60 kWh, 129 kW DC, 10-80 % 33 min, 11 kW AC,
  // unladen 1719 kg, 167 Wh/km at 110 km/h.
  {
    VehicleProfile p;
    p.m_id = "renault_megane_ev60";
    p.m_name = "Renault Mégane E-Tech EV60";
    p.m_usableCapacityKwh = 60.0;
    p.m_consumptionCountryKwhPer100Km = 11.4;
    p.m_consumptionMotorwayKwhPer100Km = 21.2;
    p.m_massKg = 1819;
    p.m_sockets = {"type2_combo", "type2"};
    p.m_maxDcPowerKw = 129;
    p.m_maxAcPowerKw = 11;
    p.m_chargeCurve = Curve({95, 129, 129, 108, 89, 74, 63, 53, 42, 25, 8});
    p.m_source =
        "EV Database (2025): 60 kWh, 129 kW DC, 10-80 % 33 min, 11 kW AC, 1719 kg, 16.7 kWh/100 km at "
        "110 km/h. Consumption at 80/130 km/h and charge curve estimated.";
    templates.push_back(std::move(p));
  }
  return templates;
}

bool IsPositiveFinite(double value)
{
  return std::isfinite(value) && value > 0.0;
}
}  // namespace

bool VehicleProfile::IsValid() const
{
  if (!IsPositiveFinite(m_usableCapacityKwh) || !IsPositiveFinite(m_consumptionCountryKwhPer100Km) ||
      !IsPositiveFinite(m_consumptionMotorwayKwhPer100Km) || !IsPositiveFinite(m_massKg) ||
      !IsPositiveFinite(m_drivetrainEfficiency) || m_drivetrainEfficiency > 1.0 || m_recuperationEfficiency < 0.0 ||
      m_recuperationEfficiency > 1.0 || !IsPositiveFinite(m_consumptionFactor) || m_auxiliaryPowerKw < 0.0 ||
      m_maxDcPowerKw < 0.0 || m_maxAcPowerKw < 0.0 || m_sockets.empty())
  {
    return false;
  }
  // Level consumption must grow with the speed.
  if (m_consumptionMotorwayKwhPer100Km <= m_consumptionCountryKwhPer100Km)
    return false;
  if (m_maxDcPowerKw > 0.0 && m_chargeCurve.size() < 2)
    return false;
  for (size_t i = 0; i < m_chargeCurve.size(); ++i)
  {
    auto const & p = m_chargeCurve[i];
    if (p.m_socPct < 0.0 || p.m_socPct > 100.0 || p.m_powerKw < 0.0)
      return false;
    if (i > 0 && p.m_socPct <= m_chargeCurve[i - 1].m_socPct)
      return false;
  }
  return true;
}

double VehicleProfile::GetDcPowerKw(double socPct) const
{
  if (m_chargeCurve.empty())
    return m_maxDcPowerKw;

  double power = m_chargeCurve.back().m_powerKw;
  if (socPct <= m_chargeCurve.front().m_socPct)
  {
    power = m_chargeCurve.front().m_powerKw;
  }
  else
  {
    for (size_t i = 1; i < m_chargeCurve.size(); ++i)
    {
      auto const & a = m_chargeCurve[i - 1];
      auto const & b = m_chargeCurve[i];
      if (socPct <= b.m_socPct)
      {
        power = a.m_powerKw + (b.m_powerKw - a.m_powerKw) * (socPct - a.m_socPct) / (b.m_socPct - a.m_socPct);
        break;
      }
    }
  }
  return m_maxDcPowerKw > 0.0 ? std::min(power, m_maxDcPowerKw) : power;
}

bool VehicleProfile::HasSocket(std::string_view socketType) const
{
  return std::find(m_sockets.begin(), m_sockets.end(), socketType) != m_sockets.end();
}

std::vector<VehicleProfile> const & GetVehicleTemplates()
{
  static std::vector<VehicleProfile> const templates = MakeTemplates();
  return templates;
}

VehicleProfile const * FindVehicleTemplate(std::string_view id)
{
  for (auto const & t : GetVehicleTemplates())
    if (t.m_id == id)
      return &t;
  return nullptr;
}

std::string ToJson(VehicleProfile const & profile)
{
  std::string json;
  if (auto const error = glz::write_json(profile, json))
    LOG(LERROR, ("Can't write a vehicle profile:", glz::format_error(error, json)));
  return json;
}

std::optional<VehicleProfile> VehicleProfileFromJson(std::string_view json)
{
  VehicleProfile profile;
  // Unknown keys are allowed for newer versions of the app.
  if (auto const error = glz::read<glz::opts{.error_on_unknown_keys = false}>(profile, json))
  {
    LOG(LWARNING, ("Can't read a vehicle profile:", glz::format_error(error, json)));
    return {};
  }
  return profile;
}

std::string DebugPrint(VehicleProfile const & profile)
{
  std::ostringstream out;
  out << "VehicleProfile [ " << profile.m_id << " " << profile.m_name << ", " << profile.m_usableCapacityKwh << " kWh, "
      << profile.m_consumptionCountryKwhPer100Km << "/" << profile.m_consumptionMotorwayKwhPer100Km << " kWh/100 km, "
      << profile.m_maxDcPowerKw << " kW DC, " << profile.m_maxAcPowerKw << " kW AC ]";
  return out.str();
}
}  // namespace ev
