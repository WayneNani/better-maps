#pragma once

#include "ev_planning/charger_status.hpp"
#include "ev_planning/charging.hpp"
#include "ev_planning/vehicle_profile.hpp"

#include "indexer/feature_charge_sockets.hpp"
#include "indexer/feature_decl.hpp"

#include "geometry/point2d.hpp"

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace ev
{
// Places near a charging station that make a charging stop nicer.
enum class AmenityType : uint8_t
{
  Playground,
  Toilets,
  Food,
  Shopping,
};

std::string DebugPrint(AmenityType type);
std::string_view ToString(AmenityType type);
std::optional<AmenityType> AmenityTypeFromString(std::string_view s);

struct Amenity
{
  AmenityType m_type = AmenityType::Toilets;
  // Kind of place for the user, e.g. "restaurant", "supermarket", "services" (motorway services).
  std::string m_kind;
  std::string m_name;
  // As the crow flies from the charging station.
  double m_distanceM = 0.0;
  // At the estimated arrival, if the place has opening hours.
  std::optional<bool> m_openAtArrival;
  std::string m_openingHours;
  m2::PointD m_point;
};

std::string DebugPrint(Amenity const & amenity);

// Normalized name of a charging provider (operator, brand or network) to compare them, e.g. "EnBW mobility+",
// "EnBW Energie Baden-Württemberg AG" and "enbw" give the same key "enbw".
struct Provider
{
  std::string m_key;
  // Name to show, e.g. "EnBW".
  std::string m_name;
};

// Returns an empty key for an empty name.
Provider NormalizeProvider(std::string_view name);

// A charging station along a route, see CandidateStations().
struct ChargingStation
{
  FeatureID m_featureId;
  // See GetKey(). Set for stations that do not come from the map data directly, e.g. stops of a stored plan.
  std::string m_key;
  m2::PointD m_point;
  std::string m_name;
  // operator, brand, network as in OSM.
  std::string m_operator;
  std::string m_brand;
  std::string m_network;
  // Normalized providers of the station (operator, brand, network without duplicates), the first is shown.
  std::vector<Provider> m_providers;
  ChargeSocketDescriptors m_sockets;
  std::string m_openingHours;
  // Price (OSM charge=*), as is.
  std::string m_price;
  // Number of vehicles that can charge at the same time, 0 if unknown.
  uint32_t m_capacity = 0;

  // Where the station lies relative to the route, see search::RoutePosition.
  double m_distanceAlongRouteM = 0.0;
  double m_timeAlongRouteSec = 0.0;
  double m_distanceFromRouteM = 0.0;
  // Extra travel time to leave the route, reach the station and come back: an estimate from the distance to the
  // route, or the real detour if m_detourChecked (see DetourProvider).
  double m_detourSec = 0.0;
  // Extra length of the real detour, if m_detourChecked.
  double m_detourDistanceM = 0.0;
  bool m_detourChecked = false;

  std::vector<Amenity> m_amenities;
  // Real-time status, see ChargerStatusProvider.
  ChargerStatus m_status;

  // Identifies the station across map updates of the same version and in the app: "<map name>:<feature index>".
  std::string GetKey() const;

  // Display name of the first provider, or the name of the station.
  std::string GetProviderName() const;
  // Name to show: the name of the station, or of the motorway services it belongs to (most stations have no name),
  // or the provider.
  std::string GetTitle() const;
  bool HasProvider(std::string_view key) const;
};

// Socket types of fast chargers (DC).
bool IsDcSocket(std::string_view socketType);

// The best charging power a vehicle gets at |sockets|, invalid if the vehicle can't use any of them.
// Sockets with an unknown power are assumed to have kAssumedDcPowerKw or kAssumedAcPowerKw.
double constexpr kAssumedDcPowerKw = 50.0;
double constexpr kAssumedAcPowerKw = 11.0;
ChargingPower GetBestChargingPower(VehicleProfile const & profile, ChargeSocketDescriptors const & sockets);
}  // namespace ev
