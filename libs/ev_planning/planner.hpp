#pragma once

#include "ev_planning/energy_model.hpp"
#include "ev_planning/station.hpp"
#include "ev_planning/vehicle_profile.hpp"

#include <cstdint>
#include <ctime>
#include <optional>
#include <string>
#include <vector>

namespace ev
{
class DetourProvider;

enum class Priority : uint8_t
{
  // Shortest total time: driving, detours and charging.
  Fastest,
  // As few stops as possible, then the shortest total time.
  FewestStops,
  // Long stops are fine, e.g. with children: fewer, longer stops at nice places, charging time counts half.
  LongBreaks,
};

std::string DebugPrint(Priority priority);
std::string_view ToString(Priority priority);
std::optional<Priority> PriorityFromString(std::string_view s);

struct PlanOptions
{
  double m_startSocPct = 80.0;
  double m_minArrivalSocPct = 20.0;
  // At the arrival at a charging stop.
  double m_minStopSocPct = 10.0;
  // Highest state of charge to charge to.
  double m_maxChargeSocPct = 100.0;
  Priority m_priority = Priority::Fastest;
  // Provider keys, see NormalizeProvider().
  std::vector<std::string> m_preferredProviders;
  std::vector<std::string> m_excludedProviders;
  // Stations whose best usable socket has less power are ignored.
  double m_minPowerKw = 50.0;
  std::vector<AmenityType> m_preferredAmenities;
  // E.g. "choose another stop", see ChargingStation::GetKey().
  std::vector<std::string> m_excludedStations;
  // Stations that are closed at the estimated arrival are ignored. The current time if not set.
  std::optional<time_t> m_departureTime;
};

// Weights of the optimization, see GetCostWeights().
struct CostWeights
{
  // Seconds of cost per second of charging.
  double m_chargingWeight = 1.0;
  // Extra cost per stop, in seconds of cost (not of time).
  double m_stopPenaltySec = 0.0;
  // Preferences make the charging time at a station count less: a share of the charging time for a preferred
  // provider and for each preferred kind of amenity within kAmenityBonusRadiusM, at most kMaxChargingDiscount.
  // As the discount is on the charging time, a stop is never attractive by itself, and splitting a charge into more
  // stops still costs their overhead.
  double m_preferredProviderDiscount = 0.0;
  double m_amenityDiscount = 0.0;
};

CostWeights GetCostWeights(Priority priority);

// Time at each stop for parking, plugging in and paying, on top of the charging.
double constexpr kStopOverheadSec = 3.0 * 60.0;
// Expected waiting time at a station that is occupied right now, see ChargerStatusProvider.
double constexpr kOccupiedPenaltySec = 15.0 * 60.0;
double constexpr kMaxChargingDiscount = 0.5;
// Amenities farther from the station do not give a bonus (they are shown though).
double constexpr kAmenityBonusRadiusM = 300.0;
// "Charge longer" variant of a stop.
double constexpr kLongerChargePct = 10.0;

struct ChargingStop
{
  ChargingStation m_station;
  ChargingPower m_power;
  double m_arrivalSocPct = 0.0;
  double m_departureSocPct = 0.0;
  double m_chargingTimeSec = 0.0;
  // Charging time plus kStopOverheadSec.
  double m_stopTimeSec = 0.0;
  // Average charging power over the stop.
  double m_averagePowerKw = 0.0;
  // From the start, with the previous stops.
  double m_arrivalTimeSec = 0.0;
  // Preferred amenities within kAmenityBonusRadiusM.
  std::vector<AmenityType> m_matchedAmenities;
  bool m_isPreferredProvider = false;

  // Charging up to kLongerChargePct more at this stop (empty if the stop already charges to the maximum).
  struct Longer
  {
    double m_departureSocPct = 0.0;
    double m_extraStopTimeSec = 0.0;
    // Change of the total time: the next stop needs less charging.
    double m_extraTotalTimeSec = 0.0;
    double m_arrivalSocPct = 0.0;
  };
  std::optional<Longer> m_longer;
};

struct Plan
{
  // Identifies the plan by its stations.
  bool HasSameStations(Plan const & rhs) const;
  size_t GetStopsCount() const { return m_stops.size(); }

  std::vector<ChargingStop> m_stops;
  double m_distanceM = 0.0;
  // Along the route plus the detours to the stations.
  double m_driveTimeSec = 0.0;
  // Sum of ChargingStop::m_stopTimeSec.
  double m_stopTimeSec = 0.0;
  double m_totalTimeSec = 0.0;
  double m_arrivalSocPct = 0.0;
  // Lowest state of charge on the way (at a stop or at the arrival).
  double m_minSocPct = 0.0;
  // False if the state of charge falls below the minimums of the options, e.g. after a reevaluation.
  bool m_meetsMinimums = true;
  Priority m_priority = Priority::Fastest;
  // For alternatives: why it is different, "priority" or "other_station".
  std::string m_alternativeReason;
};

enum class PlanStatus : uint8_t
{
  Ok,
  InvalidProfile,
  NoRoute,
  // Even with the best stations the battery does not reach the next station or the destination.
  Unreachable,
};

std::string DebugPrint(PlanStatus status);
std::string_view ToString(PlanStatus status);

// How many candidate stations a provider has along the route, e.g. to offer it as a filter.
struct ProviderCount
{
  Provider m_provider;
  uint32_t m_stations = 0;
};

struct PlanResult
{
  PlanStatus m_status = PlanStatus::NoRoute;
  Plan m_plan;
  // At most kMaxAlternatives, each with different stations than m_plan.
  std::vector<Plan> m_alternatives;
  // For PlanStatus::Unreachable: the farthest point that can be reached (start or a station) and the next station
  // or the destination after it, as distances along the route.
  double m_reachableUntilM = 0.0;
  double m_gapEndM = 0.0;
  // Stations along the route and those left after the filters of the options.
  size_t m_candidatesCount = 0;
  size_t m_usableCount = 0;
  // Providers of all candidate stations (before the provider filters), most stations first.
  std::vector<ProviderCount> m_providers;
  // Real detours computed with the DetourProvider (including failed ones).
  size_t m_detourChecks = 0;
};

size_t constexpr kMaxAlternatives = 2;

// Plans charging stops along a route.
//
// Algorithm: dynamic programming (label setting) over the stations sorted along the route, with the state of charge
// discretized in ChargingTimeTable::kStepPct steps. A label is (station, state of charge when leaving it) with the
// lowest cost to get there. From each label, all later stations and the destination are tried: the energy between
// them gives the arrival state of charge, which must stay above the minimum. At a station, charging from the arrival
// to every higher state of charge gives the labels of the station. The cost is the time (driving, detours, charging
// from the charge curve, stop overhead) with the weights of the priority (CostWeights): FewestStops adds a huge
// penalty per stop, which makes the order lexicographic (stops, then time). As all costs are additive and the
// stations are ordered along the route (a directed acyclic graph), the labels are optimal for the discretization.
// The plan is then evaluated exactly (EvaluateStops()). Effort: O(n^2 * s + n * s^2) for n stations and s steps,
// i.e. a few million steps for 200 stations.
//
// Real detours: the detours of the search are estimates from the distance to the route. With a |detours| provider,
// the stations of a plan get their real detour (e.g. from the router), and the dynamic programming runs again until
// all stations of the plan have one (at most kMaxDetourChecks in all, shared by the plan and its alternatives).
// Stations whose real detour is much longer than estimated, e.g. motorway services on the other carriageway, drop
// out of the plan this way, and the times of the plans include the real detours.
//
// |stations| need m_distanceAlongRouteM, m_detourSec, sockets and providers, see CandidateStations().
PlanResult PlanCharging(VehicleProfile const & profile, RouteEnergy const & route,
                        std::vector<ChargingStation> const & stations, PlanOptions const & options,
                        DetourProvider * detours = nullptr);

size_t constexpr kMaxDetourChecks = 40;

// A planned stop for EvaluateStops(): the station (with its position along the route) and the state of charge to
// charge to.
struct StopTarget
{
  ChargingStation m_station;
  double m_departureSocPct = 0.0;
};

// Evaluates fixed stops along a route exactly: states of charge, charging and travel times.
// Used for the result of the planner and to reevaluate a plan with the real route through the stops.
Plan EvaluateStops(VehicleProfile const & profile, RouteEnergy const & route, std::vector<StopTarget> const & stops,
                   PlanOptions const & options);

// Like EvaluateStops(), but charges more at a stop if the next stop or the destination would be reached below the
// minimum (e.g. the real route needs more energy than the planned one).
Plan EvaluateStopsAdjusting(VehicleProfile const & profile, RouteEnergy const & route, std::vector<StopTarget> stops,
                            PlanOptions const & options);

// Fills ChargingStop::m_longer of the stops of |plan| (made by EvaluateStops() with |route|).
void AddLongerVariants(VehicleProfile const & profile, RouteEnergy const & route, PlanOptions const & options,
                       Plan & plan);

// Stations that the options allow, e.g. with enough power and not from an excluded provider.
bool IsUsable(VehicleProfile const & profile, ChargingStation const & station, PlanOptions const & options);

std::vector<ProviderCount> CountProviders(std::vector<ChargingStation> const & stations);
}  // namespace ev
