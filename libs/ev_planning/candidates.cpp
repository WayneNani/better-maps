#include "ev_planning/candidates.hpp"

#include "search/mode.hpp"
#include "search/opening_hours_filter.hpp"

#include "indexer/classificator.hpp"
#include "indexer/data_source.hpp"
#include "indexer/feature.hpp"
#include "indexer/feature_algo.hpp"
#include "indexer/feature_meta.hpp"
#include "indexer/scales.hpp"

#include "i18n/localisation.hpp"

#include "geometry/mercator.hpp"

#include "base/stl_helpers.hpp"
#include "base/string_utils.hpp"

#include <algorithm>
#include <array>
#include <utility>

namespace ev
{
namespace
{
struct AmenityKind
{
  uint32_t m_type = 0;
  AmenityType m_amenity;
  std::string_view m_kind;
};

// Types of amenities (two levels, e.g. amenity-restaurant).
std::vector<AmenityKind> const & GetAmenityKinds()
{
  static std::vector<AmenityKind> const kinds = []
  {
    auto const & c = classif();
    std::vector<AmenityKind> result;
    auto const add = [&](std::string_view a, std::string_view b, AmenityType type)
    { result.push_back({c.GetTypeByPath({a, b}), type, b}); };
    add("leisure", "playground", AmenityType::Playground);
    add("amenity", "toilets", AmenityType::Toilets);
    // Motorway services ("Raststätte") have toilets and food.
    add("highway", "services", AmenityType::Toilets);
    add("highway", "services", AmenityType::Food);
    add("amenity", "restaurant", AmenityType::Food);
    add("amenity", "fast_food", AmenityType::Food);
    add("amenity", "cafe", AmenityType::Food);
    add("amenity", "food_court", AmenityType::Food);
    add("shop", "supermarket", AmenityType::Shopping);
    add("shop", "convenience", AmenityType::Shopping);
    add("shop", "bakery", AmenityType::Shopping);
    add("shop", "mall", AmenityType::Shopping);
    return result;
  }();
  return kinds;
}

std::string ReadName(FeatureType & ft)
{
  std::string name(ft.GetName(localisation::kDefaultNameIndex));
  if (name.empty())
    name = ft.GetName(localisation::kEnglishLanguageIndex);
  return name;
}

void AddProvider(std::vector<Provider> & providers, std::string_view name)
{
  auto provider = NormalizeProvider(name);
  if (provider.m_key.empty())
    return;
  bool const known = std::any_of(providers.begin(), providers.end(),
                                 [&provider](Provider const & p) { return p.m_key == provider.m_key; });
  if (!known)
    providers.push_back(std::move(provider));
}
}  // namespace

bool IsCarChargingStation(FeatureType & ft)
{
  static uint32_t const station = classif().GetTypeByPath({"amenity", "charging_station"});
  static uint32_t const motorcar = classif().GetTypeByPath({"amenity", "charging_station", "motorcar"});
  bool found = false;
  ft.ForEachType([&found](uint32_t type)
  {
    if (type == station || type == motorcar)
      found = true;
  });
  return found;
}

search::SearchParams::PoiFilter MakeChargingStationFilter(VehicleProfile const & profile, double minPowerKw)
{
  return [profile, minPowerKw](FeatureType & ft)
  {
    if (!IsCarChargingStation(ft))
      return false;
    auto const sockets =
        ChargeSocketsHelper(std::string(ft.GetMetadata(feature::Metadata::FMD_CHARGE_SOCKETS))).GetSockets();
    auto const power = GetBestChargingPower(profile, sockets);
    return power.IsValid() && power.m_powerKw >= minPowerKw;
  };
}

search::SearchParams MakeStationSearchParams(std::shared_ptr<search::RouteCorridor const> corridor,
                                             search::SearchParams::PoiFilter filter)
{
  search::SearchParams params;
  // The category of charging stations (@charging_station in categories.txt), as the category buttons search it.
  params.m_query = "Charging Station ";
  params.m_inputLocale = "en";
  params.m_categorialRequest = true;
  params.m_mode = search::Mode::Route;
  params.m_viewport = corridor->GetLimitRect();
  params.m_routeCorridor = std::move(corridor);
  params.m_maxNumResults = kMaxCandidateStations;
  params.m_poiFilter = std::move(filter);
  params.m_needAddress = false;
  params.m_suggestsEnabled = false;
  return params;
}

ChargingStation ReadChargingStation(FeatureType & ft)
{
  using feature::Metadata;
  ChargingStation station;
  station.m_featureId = ft.GetID();
  station.m_point = feature::GetCenter(ft);
  station.m_name = ReadName(ft);
  station.m_operator = std::string(ft.GetMetadata(Metadata::FMD_OPERATOR));
  station.m_brand = std::string(ft.GetMetadata(Metadata::FMD_BRAND));
  station.m_network = std::string(ft.GetMetadata(Metadata::FMD_NETWORK));
  AddProvider(station.m_providers, station.m_operator);
  AddProvider(station.m_providers, station.m_brand);
  AddProvider(station.m_providers, station.m_network);
  if (station.m_providers.empty())
    AddProvider(station.m_providers, station.m_name);
  station.m_sockets = ChargeSocketsHelper(std::string(ft.GetMetadata(Metadata::FMD_CHARGE_SOCKETS))).GetSockets();
  station.m_openingHours = std::string(ft.GetMetadata(Metadata::FMD_OPEN_HOURS));
  station.m_price = std::string(ft.GetMetadata(Metadata::FMD_CHARGE));
  uint32_t capacity = 0;
  if (strings::to_uint(ft.GetMetadata(Metadata::FMD_CAPACITY_CHARGING), capacity) ||
      strings::to_uint(ft.GetMetadata(Metadata::FMD_CAPACITY), capacity))
  {
    station.m_capacity = capacity;
  }
  return station;
}

std::vector<ChargingStation> MakeCandidates(DataSource const & dataSource, std::vector<search::Result> const & results)
{
  std::vector<std::pair<FeatureID, search::RoutePosition>> ids;
  for (auto const & r : results)
    if (r.GetResultType() == search::Result::Type::Feature && r.GetRoutePosition())
      ids.emplace_back(r.GetFeatureID(), *r.GetRoutePosition());
  // Read the features of one map in a row.
  std::sort(ids.begin(), ids.end(), base::LessBy(&std::pair<FeatureID, search::RoutePosition>::first));

  std::vector<ChargingStation> stations;
  std::unique_ptr<FeaturesLoaderGuard> loader;
  for (auto const & [id, position] : ids)
  {
    if (!id.m_mwmId.IsAlive())
      continue;
    if (!loader || loader->GetId() != id.m_mwmId)
      loader = std::make_unique<FeaturesLoaderGuard>(dataSource, id.m_mwmId);
    auto ft = loader->GetFeatureByIndex(id.m_index);
    if (!ft)
      continue;
    auto station = ReadChargingStation(*ft);
    station.m_distanceAlongRouteM = position.m_distanceAlongRouteM;
    station.m_timeAlongRouteSec = position.m_timeAlongRouteSec;
    station.m_distanceFromRouteM = position.m_distanceFromRouteM;
    station.m_detourSec = position.m_detourSec;
    stations.push_back(std::move(station));
  }
  std::sort(stations.begin(), stations.end(), [](ChargingStation const & a, ChargingStation const & b)
  { return a.m_distanceAlongRouteM < b.m_distanceAlongRouteM; });
  return stations;
}

std::vector<Amenity> AmenityFinder::FindUncached(m2::PointD const & center)
{
  auto const & kinds = GetAmenityKinds();
  std::vector<Amenity> amenities;
  auto const rect = mercator::RectByCenterXYAndSizeInMeters(center, kRadiusM);
  m_dataSource.ForEachInRect([&](FeatureType & ft)
  {
    std::vector<AmenityKind const *> matched;
    ft.ForEachType([&](uint32_t type)
    {
      uint32_t const trunc = ftype::Trunc(type, 2);
      for (auto const & kind : kinds)
        if (kind.m_type == trunc)
          matched.push_back(&kind);
    });
    if (matched.empty())
      return;

    auto const point = feature::GetCenter(ft);
    double const distance = mercator::DistanceOnEarth(center, point);
    if (distance > kRadiusM)
      return;
    for (auto const * kind : matched)
    {
      Amenity amenity;
      amenity.m_type = kind->m_amenity;
      amenity.m_kind = std::string(kind->m_kind);
      amenity.m_name = ReadName(ft);
      amenity.m_distanceM = distance;
      amenity.m_point = point;
      amenity.m_openingHours = std::string(ft.GetMetadata(feature::Metadata::FMD_OPEN_HOURS));
      amenities.push_back(std::move(amenity));
    }
  }, rect, scales::GetUpperScale());

  std::sort(amenities.begin(), amenities.end(),
            [](Amenity const & a, Amenity const & b) { return a.m_distanceM < b.m_distanceM; });
  // The same place several times, e.g. motorway services mapped as a point and an area, or a shop and its building.
  std::vector<Amenity> unique;
  for (auto & a : amenities)
  {
    bool const duplicate = std::any_of(unique.begin(), unique.end(), [&a](Amenity const & u)
    {
      return u.m_type == a.m_type && u.m_kind == a.m_kind &&
             (a.m_name.empty() ? mercator::DistanceOnEarth(u.m_point, a.m_point) < 30.0 : u.m_name == a.m_name);
    });
    if (!duplicate)
      unique.push_back(std::move(a));
  }
  amenities = std::move(unique);
  // At most kMaxPerType of each type.
  std::array<size_t, 4> counts = {};
  base::EraseIf(amenities,
                [&counts](Amenity const & a) { return ++counts[static_cast<size_t>(a.m_type)] > kMaxPerType; });
  return amenities;
}

std::vector<Amenity> AmenityFinder::Find(ChargingStation const & station, std::optional<time_t> arrival)
{
  std::vector<Amenity> amenities;
  bool cached = false;
  {
    std::lock_guard lock(m_mutex);
    auto const it = m_cache.find(station.m_featureId);
    if (it != m_cache.end())
    {
      amenities = it->second;
      cached = true;
    }
  }
  if (!cached)
  {
    amenities = FindUncached(station.m_point);
    std::lock_guard lock(m_mutex);
    m_cache[station.m_featureId] = amenities;
  }

  for (auto & a : amenities)
  {
    a.m_openAtArrival.reset();
    if (arrival && !a.m_openingHours.empty())
    {
      auto const isOpen = search::GetOpeningHoursStatus(a.m_openingHours, *arrival).m_isOpen;
      if (isOpen != osm::Unknown)
        a.m_openAtArrival = isOpen == osm::Yes;
    }
  }
  return amenities;
}

void AmenityFinder::AddAmenities(Plan & plan, std::optional<time_t> departure)
{
  for (auto & stop : plan.m_stops)
  {
    std::optional<time_t> arrival;
    if (departure)
      arrival = *departure + static_cast<time_t>(stop.m_arrivalTimeSec);
    stop.m_station.m_amenities = Find(stop.m_station, arrival);
  }
}

PlanResult PlanChargingWithAmenities(VehicleProfile const & profile, RouteEnergy const & route,
                                     std::vector<ChargingStation> & stations, PlanOptions const & options,
                                     AmenityFinder & finder)
{
  std::optional<time_t> const departure = options.m_departureTime ? *options.m_departureTime : time(nullptr);
  if (!options.m_preferredAmenities.empty())
  {
    for (auto & s : stations)
      if (IsUsable(profile, s, options))
        s.m_amenities = finder.Find(s, *departure + static_cast<time_t>(s.m_timeAlongRouteSec + s.m_detourSec / 2.0));
  }
  auto result = PlanCharging(profile, route, stations, options);
  finder.AddAmenities(result.m_plan, departure);
  for (auto & alternative : result.m_alternatives)
    finder.AddAmenities(alternative, departure);
  return result;
}

void AmenityFinder::AddAmenities(std::vector<ChargingStation> & stations, std::optional<time_t> departure)
{
  for (auto & s : stations)
  {
    std::optional<time_t> arrival;
    if (departure)
      arrival = *departure + static_cast<time_t>(s.m_timeAlongRouteSec + s.m_detourSec / 2.0);
    s.m_amenities = Find(s, arrival);
  }
}
}  // namespace ev
