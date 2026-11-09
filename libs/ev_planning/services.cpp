#include "ev_planning/services.hpp"

#include "indexer/classificator.hpp"
#include "indexer/data_source.hpp"
#include "indexer/feature.hpp"
#include "indexer/feature_algo.hpp"

#include "i18n/localisation.hpp"

#include "geometry/mercator.hpp"

#include "base/string_utils.hpp"

#include <algorithm>
#include <array>
#include <string_view>

namespace ev
{
namespace
{
// Services are shown from zoom level 13 or 14 on, rest areas from 15 (data/styles/default/include/Icons.mapcss): the
// features of this scale are enough, which skips most buildings and small roads around.
int constexpr kScale = 15;

bool IsTruckStop(std::string const & name)
{
  // "Autohof" in Germany and Austria, e.g. "MAXI Autohof Wertheim", "Aral Autohof Dettelbach".
  std::string const lower = strings::MakeLowerCase(name);
  std::array<std::string_view, 3> constexpr kNames = {"autohof", "truck stop", "truckstop"};
  return std::any_of(kNames.begin(), kNames.end(),
                     [&lower](std::string_view n) { return lower.find(n) != std::string::npos; });
}

bool IsRelevant(ServicesPlace const & place)
{
  return place.m_isArea ? place.m_distanceM <= kServicesAreaToleranceM : place.m_distanceM <= kServicesPointRadiusM;
}
}  // namespace

bool IsAtMotorwayServices(std::vector<ServicesPlace> const & places, double distanceFromRouteM)
{
  if (distanceFromRouteM > kMaxServicesFromRouteM)
    return false;
  bool found = false;
  for (auto const & place : places)
  {
    if (!IsRelevant(place))
      continue;
    if (IsTruckStop(place.m_name))
      return false;
    found = true;
  }
  return found;
}

std::vector<ServicesPlace> ServicesFinder::Find(m2::PointD const & point) const
{
  static uint32_t const services = classif().GetTypeByPath({"highway", "services"});
  static uint32_t const restArea = classif().GetTypeByPath({"highway", "rest_area"});

  std::vector<ServicesPlace> places;
  auto const rect = mercator::RectByCenterXYAndSizeInMeters(point, kServicesPointRadiusM);
  m_dataSource.ForEachInRect([&](FeatureType & ft)
  {
    bool matched = false;
    ft.ForEachType([&](uint32_t type)
    {
      type = ftype::Trunc(type, 2);
      matched |= type == services || type == restArea;
    });
    if (!matched)
      return;
    auto const geomType = ft.GetGeomType();
    if (geomType != feature::GeomType::Area && geomType != feature::GeomType::Point)
      return;
    ServicesPlace place;
    place.m_isArea = geomType == feature::GeomType::Area;
    // 0 inside an area.
    place.m_distanceM = feature::GetMinDistanceMeters(ft, point);
    if (place.m_distanceM > kServicesPointRadiusM)
      return;
    place.m_name = std::string(ft.GetName(localisation::kDefaultNameIndex));
    places.push_back(std::move(place));
  }, rect, kScale);
  return places;
}

bool ServicesFinder::IsAtMotorwayServices(ChargingStation const & station)
{
  // The places around a station, the distance to the route may differ (another route).
  if (!station.m_featureId.IsValid())
    return ev::IsAtMotorwayServices(Find(station.m_point), station.m_distanceFromRouteM);
  {
    std::lock_guard lock(m_mutex);
    auto const it = m_cache.find(station.m_featureId);
    if (it != m_cache.end())
      return ev::IsAtMotorwayServices(it->second, station.m_distanceFromRouteM);
  }
  auto places = Find(station.m_point);
  bool const result = ev::IsAtMotorwayServices(places, station.m_distanceFromRouteM);
  std::lock_guard lock(m_mutex);
  m_cache[station.m_featureId] = std::move(places);
  return result;
}
}  // namespace ev
