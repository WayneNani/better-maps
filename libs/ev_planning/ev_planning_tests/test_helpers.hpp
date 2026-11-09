#pragma once

#include "ev_planning/energy_model.hpp"
#include "ev_planning/station.hpp"
#include "ev_planning/vehicle_profile.hpp"

#include <string>
#include <vector>

namespace ev_planning_tests
{
// The Škoda Elroq 85 template.
ev::VehicleProfile const & Elroq();

// A straight route of |lengthKm| at a constant |speedKmh|, a point every 500 m. |altitudeAtKm| gives the altitude.
std::vector<ev::RoutePoint> MakeRoutePoints(double lengthKm, double speedKmh,
                                            double (*altitudeAtKm)(double km) = nullptr);
ev::RouteEnergy MakeRoute(ev::VehicleProfile const & profile, double lengthKm, double speedKmh);

// A station at |km| along the route with sockets like "type2_combo|4|150;type2|2|22".
ev::ChargingStation MakeStation(uint32_t index, double km, std::string const & sockets,
                                std::string const & operatorName = "", double detourSec = 60.0);
}  // namespace ev_planning_tests
