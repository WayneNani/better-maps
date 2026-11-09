#pragma once

#include "ev_planning/planner.hpp"
#include "ev_planning/station.hpp"
#include "ev_planning/vehicle_profile.hpp"

#include "search/result.hpp"
#include "search/route_corridor.hpp"
#include "search/search_params.hpp"

#include "indexer/feature_decl.hpp"

#include "geometry/point2d.hpp"

#include <ctime>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

class DataSource;
class FeatureType;

namespace ev
{
// Half width of the corridor to search for charging stations: stations at motorway exits ("Autohof") are often
// 1-2 km from the motorway.
double constexpr kStationCorridorHalfWidthM = 2500.0;
// Up to this many stations along the route (spread along it, see search::SpreadAlongRoute()).
size_t constexpr kMaxCandidateStations = 300;

// True for charging stations for cars (amenity=charging_station without a vehicle or with motorcar).
bool IsCarChargingStation(FeatureType & ft);

// Filter for SearchParams::m_poiFilter: car charging stations with a socket of the vehicle and at least
// |minPowerKw| at it (an unknown power counts as kAssumedDcPowerKw / kAssumedAcPowerKw).
search::SearchParams::PoiFilter MakeChargingStationFilter(VehicleProfile const & profile, double minPowerKw);

// Search for charging stations in |corridor| (search::Mode::Route), for the search engine or SearchAPI.
search::SearchParams MakeStationSearchParams(std::shared_ptr<search::RouteCorridor const> corridor,
                                             search::SearchParams::PoiFilter filter);

// Reads the station data (sockets, operator, ...) from a feature.
ChargingStation ReadChargingStation(FeatureType & ft);

// Charging stations from the results of the search along the route, with their positions along the route.
std::vector<ChargingStation> MakeCandidates(DataSource const & dataSource, std::vector<search::Result> const & results);

// Finds amenities (playgrounds, toilets, food, shops) around charging stations in the map data. Results are cached
// by station. Thread-safe.
class AmenityFinder
{
public:
  // Amenities farther away are not searched.
  static double constexpr kRadiusM = 500.0;

  explicit AmenityFinder(DataSource const & dataSource) : m_dataSource(dataSource) {}

  // Amenities around |station|, nearest first, at most kMaxPerType of each type. |arrival| is the moment to evaluate
  // the opening hours at (none if not set).
  std::vector<Amenity> Find(ChargingStation const & station, std::optional<time_t> arrival);

  // Fills ChargingStation::m_amenities. |departure| plus the time along the route gives the arrival.
  void AddAmenities(std::vector<ChargingStation> & stations, std::optional<time_t> departure);

  static size_t constexpr kMaxPerType = 3;

  // Fills the amenities of the stations of |plan| (copies of the stations), with the opening hours at the arrival.
  void AddAmenities(Plan & plan, std::optional<time_t> departure);

private:
  std::vector<Amenity> FindUncached(m2::PointD const & center);

  DataSource const & m_dataSource;
  std::mutex m_mutex;
  std::map<FeatureID, std::vector<Amenity>> m_cache;
};
// Plans with the amenities of the stations. They are needed for all stations only if the options prefer some
// (they change the choice); otherwise only the stations of the plans get them, which is much faster.
PlanResult PlanChargingWithAmenities(VehicleProfile const & profile, RouteEnergy const & route,
                                     std::vector<ChargingStation> & stations, PlanOptions const & options,
                                     AmenityFinder & finder);
}  // namespace ev
