#include "ev_planning/ev_planning_tests/test_helpers.hpp"

#include "geometry/mercator.hpp"

#include "base/assert.hpp"

#include <cmath>

namespace ev_planning_tests
{
ev::VehicleProfile const & Elroq()
{
  auto const * profile = ev::FindVehicleTemplate("skoda_elroq_85");
  CHECK(profile, ());
  return *profile;
}

std::vector<ev::RoutePoint> MakeRoutePoints(double lengthKm, double speedKmh, double (*altitudeAtKm)(double km))
{
  std::vector<ev::RoutePoint> points;
  double constexpr kStepKm = 0.5;
  size_t const steps = static_cast<size_t>(std::lround(lengthKm / kStepKm));
  for (size_t i = 0; i <= steps; ++i)
  {
    double const km = i * kStepKm;
    ev::RoutePoint p;
    p.m_distanceM = km * 1000.0;
    p.m_timeSec = km / speedKmh * 3600.0;
    p.m_altitudeM = altitudeAtKm ? altitudeAtKm(km) : 100.0;
    p.m_roadKind = ev::RoadKind::Fast;
    points.push_back(p);
  }
  return points;
}

ev::RouteEnergy MakeRoute(ev::VehicleProfile const & profile, double lengthKm, double speedKmh)
{
  return ev::ComputeRouteEnergy(ev::EnergyModel(profile), MakeRoutePoints(lengthKm, speedKmh));
}

ev::ChargingStation MakeStation(uint32_t index, double km, std::string const & sockets,
                                std::string const & operatorName, double detourSec)
{
  ev::ChargingStation s;
  s.m_featureId.m_index = index;
  s.m_point = mercator::FromLatLon(50.0, 7.0 + km / 71.5);
  s.m_name = "Station " + std::to_string(index);
  s.m_operator = operatorName;
  if (!operatorName.empty())
    s.m_providers.push_back(ev::NormalizeProvider(operatorName));
  s.m_sockets = ChargeSocketsHelper(sockets).GetSockets();
  s.m_distanceAlongRouteM = km * 1000.0;
  s.m_timeAlongRouteSec = km / 100.0 * 3600.0;
  s.m_distanceFromRouteM = 100.0;
  s.m_detourSec = detourSec;
  return s;
}
}  // namespace ev_planning_tests
