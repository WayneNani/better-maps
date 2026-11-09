#pragma once

#include "ev_planning/station.hpp"

#include "indexer/feature_decl.hpp"

#include "geometry/point2d.hpp"

#include <map>
#include <mutex>
#include <string>
#include <vector>

class DataSource;

namespace ev
{
// Motorway services and rest areas (highway=services, highway=rest_area) around a charging station.
struct ServicesPlace
{
  std::string m_name;
  // From the station, 0 if the station lies inside the area.
  double m_distanceM = 0.0;
  // Mapped as an area (most services), or as a point only.
  bool m_isArea = false;
};

// Whether a charging station belongs to motorway services ("Rastanlage", or a rest area) that serve one carriageway,
// from the services and rest areas around it (the map has no tag for "one carriageway"):
// - The station lies inside a services or rest area (or less than kServicesAreaToleranceM outside, stations are
//   sometimes mapped at the edge), or within kServicesPointRadiusM of services that are mapped as a point only (the
//   point is usually in the middle of the services).
// - The station is at most kMaxServicesFromRouteM from the route: motorway services lie along the carriageway, their
//   chargers 50-300 m from the other carriageway. Farther stations are at an exit, even if the area around them is
//   tagged as services.
// - None of these places is an "Autohof" (or truck stop): those are at an exit and reachable from both directions,
//   but often tagged highway=services as well (in Germany about half of them).
//
// From the stations along Bonn -> Nuremberg and Cologne -> Munich: all motorway services and rest areas on the other
// side of the motorway are found, and no Autohof. Limits: services that are not mapped at all (the station then
// counts as at an exit), services mapped as a point farther than kServicesPointRadiusM from the station, and services
// that are reachable from both carriageways (rare in Germany, e.g. Limburg-Ost with a road to the town) count as one
// carriageway. The router checks the detours of the chosen stations anyway.
double constexpr kServicesAreaToleranceM = 30.0;
double constexpr kServicesPointRadiusM = 150.0;
double constexpr kMaxServicesFromRouteM = 400.0;

bool IsAtMotorwayServices(std::vector<ServicesPlace> const & places, double distanceFromRouteM);

// Whether charging stations belong to motorway services, see IsAtMotorwayServices().
class MotorwayServices
{
public:
  virtual ~MotorwayServices() = default;

  // |station| needs m_point and m_distanceFromRouteM.
  virtual bool IsAtMotorwayServices(ChargingStation const & station) = 0;
};

// MotorwayServices from the map data, the places around a station are cached. Thread-safe.
class ServicesFinder : public MotorwayServices
{
public:
  explicit ServicesFinder(DataSource const & dataSource) : m_dataSource(dataSource) {}

  // Services and rest areas within kServicesPointRadiusM of |point|, areas that contain it with distance 0.
  std::vector<ServicesPlace> Find(m2::PointD const & point) const;

  bool IsAtMotorwayServices(ChargingStation const & station) override;

private:
  DataSource const & m_dataSource;
  std::mutex m_mutex;
  std::map<FeatureID, std::vector<ServicesPlace>> m_cache;
};
}  // namespace ev
