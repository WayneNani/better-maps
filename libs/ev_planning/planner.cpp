#include "ev_planning/planner.hpp"

#include "ev_planning/detours.hpp"

#include "search/opening_hours_filter.hpp"

#include "geometry/mercator.hpp"

#include "base/assert.hpp"
#include "base/logging.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <map>
#include <optional>
#include <set>
#include <utility>

namespace ev
{
namespace
{
// Not infinity: the code is built with -ffast-math.
double constexpr kInf = std::numeric_limits<double>::max();
double constexpr kStepPct = ChargingTimeTable::kStepPct;
int constexpr kMaxLevel = static_cast<int>(100.0 / kStepPct);
// Speed on the way from the route to a station and back, as in RoutingManager::MakeSearchCorridor().
double constexpr kDetourSpeedKmh = 40.0;
// Two stops closer than this along the route make no sense (e.g. two charging stations at the same services).
double constexpr kMinStopDistanceM = 5000.0;
double constexpr kEps = 1e-9;

int ToLevelFloor(double socPct)
{
  return std::clamp(static_cast<int>(std::floor(socPct / kStepPct + kEps)), 0, kMaxLevel);
}

double ToSoc(int level)
{
  return level * kStepPct;
}

double GetDetourEnergyKwh(EnergyModel const & model, ChargingStation const & station)
{
  if (station.m_detourChecked && station.m_detourDistanceM > 0.0 && station.m_detourSec > 0.0)
  {
    // The real detour, e.g. on the motorway to the next junction and back.
    double const km = station.m_detourDistanceM / 1000.0;
    double const speedKmh = std::clamp(km / (station.m_detourSec / 3600.0), kDetourSpeedKmh / 2.0, 130.0);
    double const factor = speedKmh < 60.0 ? EnergyModel::kMinorRoadFactor : 1.0;
    return model.GetLevelConsumptionKwhPerKm(speedKmh) * km * factor;
  }
  double const detourKm = station.m_detourSec / 3600.0 * kDetourSpeedKmh;
  return model.GetLevelConsumptionKwhPerKm(kDetourSpeedKmh) * detourKm * EnergyModel::kMinorRoadFactor;
}

std::vector<AmenityType> GetMatchedAmenities(ChargingStation const & station, PlanOptions const & options)
{
  std::vector<AmenityType> matched;
  for (auto const type : options.m_preferredAmenities)
  {
    bool const found = std::any_of(station.m_amenities.begin(), station.m_amenities.end(), [type](Amenity const & a)
    { return a.m_type == type && a.m_distanceM <= kAmenityBonusRadiusM; });
    if (found)
      matched.push_back(type);
  }
  return matched;
}

bool IsPreferredProvider(ChargingStation const & station, PlanOptions const & options)
{
  return std::any_of(options.m_preferredProviders.begin(), options.m_preferredProviders.end(),
                     [&station](std::string const & key) { return station.HasProvider(key); });
}

bool IsClosedAtArrival(ChargingStation const & station, PlanOptions const & options)
{
  if (station.m_openingHours.empty())
    return false;
  time_t const departure = options.m_departureTime ? *options.m_departureTime : time(nullptr);
  auto const arrival = departure + static_cast<time_t>(station.m_timeAlongRouteSec + station.m_detourSec / 2.0);
  return search::GetOpeningHoursStatus(station.m_openingHours, arrival).m_isOpen == osm::No;
}

// A station in the dynamic programming.
struct Node
{
  ChargingStation const * m_station = nullptr;
  ChargingPower m_power;
  double m_positionM = 0.0;
  double m_detourHalfSec = 0.0;
  double m_detourHalfKwh = 0.0;
  // Cost of a stop apart from the charging time: overhead and penalties.
  double m_stopBaseCostSec = 0.0;
  // Weight of the charging time with the preferences, see CostWeights.
  double m_chargingWeight = 1.0;
  std::unique_ptr<ChargingTimeTable> m_chargingTimes;
};

struct Label
{
  double m_cost = kInf;
  // For arrival labels: node and departure level before. For departure labels: the arrival level at the node.
  int m_fromNode = -1;
  int m_fromLevel = -1;
};

struct DpResult
{
  bool m_found = false;
  std::vector<StopTarget> m_stops;
  // Indices of the stations of m_stops in the stations of RunDp().
  std::vector<size_t> m_stationIndices;
  double m_reachableUntilM = 0.0;
  double m_gapEndM = 0.0;
};

DpResult RunDp(VehicleProfile const & profile, EnergyModel const & model, RouteEnergy const & route,
               std::vector<ChargingStation const *> const & stations, PlanOptions const & options)
{
  CostWeights const weights = GetCostWeights(options.m_priority);

  // Node 0 is the start, the last one the destination.
  std::vector<Node> nodes(stations.size() + 2);
  nodes.back().m_positionM = route.GetLengthM();
  for (size_t i = 0; i < stations.size(); ++i)
  {
    auto const & s = *stations[i];
    auto & node = nodes[i + 1];
    node.m_station = &s;
    node.m_power = GetBestChargingPower(profile, s.m_sockets);
    node.m_positionM = std::clamp(s.m_distanceAlongRouteM, 0.0, route.GetLengthM());
    node.m_detourHalfSec = s.m_detourSec / 2.0;
    node.m_detourHalfKwh = GetDetourEnergyKwh(model, s) / 2.0;
    double discount = GetMatchedAmenities(s, options).size() * weights.m_amenityDiscount;
    if (IsPreferredProvider(s, options))
      discount += weights.m_preferredProviderDiscount;
    node.m_chargingWeight = weights.m_chargingWeight * (1.0 - std::min(discount, kMaxChargingDiscount));
    double const occupied = s.m_status.m_availability == Availability::Occupied ? kOccupiedPenaltySec : 0.0;
    node.m_stopBaseCostSec = kStopOverheadSec + weights.m_stopPenaltySec + occupied;
    node.m_chargingTimes = std::make_unique<ChargingTimeTable>(profile, node.m_power);
  }

  size_t const n = nodes.size();
  size_t const destination = n - 1;
  int const maxChargeLevel = ToLevelFloor(std::min(options.m_maxChargeSocPct, 100.0));
  double const capacity = profile.m_usableCapacityKwh;

  std::vector<std::vector<Label>> arrivals(n, std::vector<Label>(kMaxLevel + 1));
  std::vector<std::vector<Label>> departures(n, std::vector<Label>(kMaxLevel + 1));
  // Levels with a finite departure label, to skip the empty ones.
  std::vector<std::vector<int>> departureLevels(n);

  int const startLevel = ToLevelFloor(options.m_startSocPct);
  departures[0][startLevel].m_cost = 0.0;
  departureLevels[0].push_back(startLevel);

  for (size_t k = 1; k < n; ++k)
  {
    auto const & to = nodes[k];
    double const minimum = k == destination ? options.m_minArrivalSocPct : options.m_minStopSocPct;
    for (size_t p = 0; p < k; ++p)
    {
      if (departureLevels[p].empty())
        continue;
      auto const & from = nodes[p];
      if (p > 0 && k != destination && to.m_positionM - from.m_positionM < kMinStopDistanceM)
        continue;
      double const energy =
          route.GetEnergyKwh(from.m_positionM, to.m_positionM) + from.m_detourHalfKwh + to.m_detourHalfKwh;
      double const time = route.GetTimeSecAt(to.m_positionM) - route.GetTimeSecAt(from.m_positionM) +
                          from.m_detourHalfSec + to.m_detourHalfSec;
      double const socDrop = energy / capacity * 100.0;
      for (int const level : departureLevels[p])
      {
        double const arrivalSoc = ToSoc(level) - socDrop;
        if (arrivalSoc < minimum - kEps)
          continue;
        int const arrivalLevel = ToLevelFloor(arrivalSoc);
        double const cost = departures[p][level].m_cost + time;
        auto & label = arrivals[k][arrivalLevel];
        if (cost < label.m_cost)
          label = {cost, static_cast<int>(p), level};
      }
    }

    if (k == destination)
      break;

    // Charging at the station.
    for (int a = 0; a <= kMaxLevel; ++a)
    {
      double const arrivalCost = arrivals[k][a].m_cost;
      if (arrivalCost == kInf)
        continue;
      for (int d = a + 1; d <= maxChargeLevel; ++d)
      {
        double const charging = to.m_chargingTimes->GetTimeSec(ToSoc(a), ToSoc(d));
        double const stopCost = to.m_chargingWeight * charging + to.m_stopBaseCostSec;
        double const cost = arrivalCost + stopCost;
        auto & label = departures[k][d];
        if (cost < label.m_cost)
          label = {cost, static_cast<int>(k), a};
      }
    }
    for (int d = 0; d <= kMaxLevel; ++d)
      if (departures[k][d].m_cost < kInf)
        departureLevels[k].push_back(d);
  }

  DpResult result;
  int bestLevel = -1;
  for (int a = 0; a <= kMaxLevel; ++a)
  {
    // Ties: the higher state of charge.
    if (arrivals[destination][a].m_cost < kInf &&
        (bestLevel < 0 || arrivals[destination][a].m_cost <= arrivals[destination][bestLevel].m_cost))
    {
      bestLevel = a;
    }
  }

  if (bestLevel < 0)
  {
    // The farthest node that can be left, and the next node after it.
    size_t farthest = 0;
    for (size_t k = 1; k < destination; ++k)
      if (!departureLevels[k].empty() && nodes[k].m_positionM >= nodes[farthest].m_positionM)
        farthest = k;
    result.m_reachableUntilM = nodes[farthest].m_positionM;
    result.m_gapEndM = route.GetLengthM();
    for (size_t k = 1; k < destination; ++k)
      if (nodes[k].m_positionM > result.m_reachableUntilM)
        result.m_gapEndM = std::min(result.m_gapEndM, nodes[k].m_positionM);
    return result;
  }

  // Back from the destination.
  result.m_found = true;
  int node = static_cast<int>(destination);
  int level = bestLevel;
  while (node > 0)
  {
    auto const & arrival = arrivals[node][level];
    CHECK_GREATER_OR_EQUAL(arrival.m_fromNode, 0, ());
    int const prevNode = arrival.m_fromNode;
    int const prevDepartureLevel = arrival.m_fromLevel;
    if (prevNode == 0)
      break;
    result.m_stops.push_back({*nodes[prevNode].m_station, ToSoc(prevDepartureLevel)});
    result.m_stationIndices.push_back(static_cast<size_t>(prevNode - 1));
    node = prevNode;
    level = departures[prevNode][prevDepartureLevel].m_fromLevel;
  }
  std::reverse(result.m_stops.begin(), result.m_stops.end());
  std::reverse(result.m_stationIndices.begin(), result.m_stationIndices.end());
  return result;
}

// Several stations at one place, e.g. of several providers at the same services, or a station mapped twice.
double constexpr kSamePlaceM = 50.0;

bool IsSamePlace(ChargingStation const & a, ChargingStation const & b)
{
  return mercator::DistanceOnEarth(a.m_point, b.m_point) < kSamePlaceM;
}

bool HasSamePlaces(Plan const & a, Plan const & b)
{
  if (a.m_stops.size() != b.m_stops.size())
    return false;
  for (size_t i = 0; i < a.m_stops.size(); ++i)
    if (!IsSamePlace(a.m_stops[i].m_station, b.m_stops[i].m_station))
      return false;
  return true;
}

// Excludes the stations at the same place as an excluded one too: "choose another stop" means another place.
void ExcludeSamePlaces(std::vector<ChargingStation> const & stations, PlanOptions & options)
{
  auto & excluded = options.m_excludedStations;
  std::vector<ChargingStation const *> places;
  for (auto const & s : stations)
    if (std::find(excluded.begin(), excluded.end(), s.GetKey()) != excluded.end())
      places.push_back(&s);
  for (auto const & s : stations)
  {
    bool const atPlace = std::any_of(places.begin(), places.end(), [&s](auto const * p) { return IsSamePlace(*p, s); });
    if (atPlace && std::find(excluded.begin(), excluded.end(), s.GetKey()) == excluded.end())
      excluded.push_back(s.GetKey());
  }
}

// Real detours of stations with a DetourProvider, at most kMaxDetourChecks.
class DetourChecker
{
public:
  explicit DetourChecker(DetourProvider * provider) : m_provider(provider) {}

  bool CanCheck() const { return m_provider && m_checks < kMaxDetourChecks; }
  size_t GetChecksCount() const { return m_checks; }

  // Better estimates of the provider for all stations.
  void Estimate(std::vector<ChargingStation> & stations) const
  {
    if (!m_provider)
      return;
    for (auto & s : stations)
      if (!s.m_detourChecked)
        if (auto const estimate = m_provider->EstimateDetourSec(s))
          s.m_detourSec = *estimate;
  }

  // False if the station was tried before, or no check is left.
  bool Check(ChargingStation & station)
  {
    if (station.m_detourChecked || !CanCheck() || !m_tried.insert(&station).second)
      return false;
    // Several stations at one place have the same detour.
    auto const samePlace = std::find_if(m_detours.begin(), m_detours.end(), [&station](auto const & known)
    { return mercator::DistanceOnEarth(known.first, station.m_point) < kSamePlaceM; });
    std::optional<Detour> detour;
    if (samePlace != m_detours.end())
    {
      detour = samePlace->second;
    }
    else
    {
      ++m_checks;
      detour = m_provider->GetDetour(station);
      if (!detour)
        return true;  // The estimate stays.
      m_detours.emplace_back(station.m_point, *detour);
    }
    LOG(LDEBUG, ("Detour of", station.GetTitle(), "at km", station.m_distanceAlongRouteM / 1000.0, ":",
                 station.m_detourSec, "s estimated,", detour->m_timeSec, "s and", detour->m_distanceM, "m real"));
    station.m_detourSec = detour->m_timeSec;
    station.m_detourDistanceM = detour->m_distanceM;
    station.m_detourChecked = true;
    return true;
  }

private:
  DetourProvider * m_provider;
  size_t m_checks = 0;
  std::set<ChargingStation const *> m_tried;
  std::vector<std::pair<m2::PointD, Detour>> m_detours;
};

// RunDp() until all stations of the plan have their real detour: a station with a longer real detour than estimated
// may drop out of the plan, then the next plan is checked. Ends after a few rounds: each one checks at least one more
// station.
DpResult RunDpWithDetours(VehicleProfile const & profile, EnergyModel const & model, RouteEnergy const & route,
                          std::vector<ChargingStation *> const & stations, PlanOptions const & options,
                          DetourChecker & checker)
{
  std::vector<ChargingStation const *> const constStations(stations.begin(), stations.end());
  while (true)
  {
    DpResult dp = RunDp(profile, model, route, constStations, options);
    if (!dp.m_found || !checker.CanCheck())
      return dp;
    bool checked = false;
    for (size_t const index : dp.m_stationIndices)
      checked |= checker.Check(*stations[index]);
    if (!checked)
      return dp;
  }
}

void AddLongerVariantsImpl(VehicleProfile const & profile, RouteEnergy const & route, PlanOptions const & options,
                           Plan & plan)
{
  std::vector<StopTarget> targets;
  for (auto const & stop : plan.m_stops)
    targets.push_back({stop.m_station, stop.m_departureSocPct});

  for (size_t i = 0; i < plan.m_stops.size(); ++i)
  {
    auto & stop = plan.m_stops[i];
    double const maxSoc = std::min(options.m_maxChargeSocPct, 100.0);
    if (stop.m_departureSocPct >= maxSoc - kStepPct)
      continue;
    auto longerTargets = targets;
    longerTargets[i].m_departureSocPct = std::min(maxSoc, stop.m_departureSocPct + kLongerChargePct);
    Plan const longer = EvaluateStops(profile, route, longerTargets, options);
    ChargingStop::Longer variant;
    variant.m_departureSocPct = longerTargets[i].m_departureSocPct;
    variant.m_extraStopTimeSec = longer.m_stops[i].m_stopTimeSec - stop.m_stopTimeSec;
    variant.m_extraTotalTimeSec = longer.m_totalTimeSec - plan.m_totalTimeSec;
    for (size_t j = i + 1; j < plan.m_stops.size(); ++j)
      variant.m_laterStopsSavedSec += plan.m_stops[j].m_stopTimeSec - longer.m_stops[j].m_stopTimeSec;
    variant.m_arrivalSocPct = longer.m_arrivalSocPct;
    stop.m_longer = variant;
  }
}

Plan MakePlan(VehicleProfile const & profile, RouteEnergy const & route, std::vector<StopTarget> const & stops,
              PlanOptions const & options)
{
  Plan plan = EvaluateStops(profile, route, stops, options);
  for (auto & stop : plan.m_stops)
  {
    stop.m_matchedAmenities = GetMatchedAmenities(stop.m_station, options);
    stop.m_isPreferredProvider = IsPreferredProvider(stop.m_station, options);
  }
  AddLongerVariantsImpl(profile, route, options, plan);
  plan.m_priority = options.m_priority;
  return plan;
}
}  // namespace

std::string DebugPrint(Priority priority)
{
  return std::string(ToString(priority));
}

std::string_view ToString(Priority priority)
{
  switch (priority)
  {
  case Priority::Fastest: return "fastest";
  case Priority::FewestStops: return "fewest_stops";
  case Priority::LongBreaks: return "long_breaks";
  }
  UNREACHABLE();
}

std::optional<Priority> PriorityFromString(std::string_view s)
{
  for (auto const p : {Priority::Fastest, Priority::FewestStops, Priority::LongBreaks})
    if (ToString(p) == s)
      return p;
  return {};
}

std::string DebugPrint(PlanStatus status)
{
  return std::string(ToString(status));
}

std::string_view ToString(PlanStatus status)
{
  switch (status)
  {
  case PlanStatus::Ok: return "ok";
  case PlanStatus::InvalidProfile: return "invalid_profile";
  case PlanStatus::NoRoute: return "no_route";
  case PlanStatus::Unreachable: return "unreachable";
  }
  UNREACHABLE();
}

CostWeights GetCostWeights(Priority priority)
{
  CostWeights weights;
  weights.m_preferredProviderDiscount = 0.25;
  weights.m_amenityDiscount = 0.1;
  switch (priority)
  {
  case Priority::Fastest: break;
  case Priority::FewestStops:
    // Larger than any travel time: the number of stops comes first.
    weights.m_stopPenaltySec = 1e6;
    break;
  case Priority::LongBreaks:
    weights.m_chargingWeight = 0.5;
    weights.m_stopPenaltySec = 30.0 * 60.0;
    weights.m_amenityDiscount = 0.2;
    break;
  }
  return weights;
}

bool Plan::HasSameStations(Plan const & rhs) const
{
  if (m_stops.size() != rhs.m_stops.size())
    return false;
  for (size_t i = 0; i < m_stops.size(); ++i)
    if (m_stops[i].m_station.m_featureId != rhs.m_stops[i].m_station.m_featureId)
      return false;
  return true;
}

bool IsUsable(VehicleProfile const & profile, ChargingStation const & station, PlanOptions const & options)
{
  auto const power = GetBestChargingPower(profile, station.m_sockets);
  if (!power.IsValid() || power.m_powerKw < options.m_minPowerKw)
    return false;
  if (station.m_status.m_availability == Availability::OutOfOrder)
    return false;
  for (auto const & key : options.m_excludedProviders)
    if (station.HasProvider(key))
      return false;
  if (std::find(options.m_excludedStations.begin(), options.m_excludedStations.end(), station.GetKey()) !=
      options.m_excludedStations.end())
  {
    return false;
  }
  return !IsClosedAtArrival(station, options);
}

std::vector<ProviderCount> CountProviders(std::vector<ChargingStation> const & stations)
{
  std::map<std::string, ProviderCount> counts;
  for (auto const & s : stations)
  {
    for (auto const & provider : s.m_providers)
    {
      auto & count = counts[provider.m_key];
      count.m_provider = provider;
      ++count.m_stations;
    }
  }
  std::vector<ProviderCount> result;
  for (auto & [key, count] : counts)
    result.push_back(std::move(count));
  std::stable_sort(result.begin(), result.end(),
                   [](ProviderCount const & a, ProviderCount const & b) { return a.m_stations > b.m_stations; });
  return result;
}

Plan EvaluateStops(VehicleProfile const & profile, RouteEnergy const & route, std::vector<StopTarget> const & stops,
                   PlanOptions const & options)
{
  EnergyModel const model(profile);
  double const capacity = profile.m_usableCapacityKwh;

  Plan plan;
  plan.m_priority = options.m_priority;
  plan.m_distanceM = route.GetLengthM();

  double soc = options.m_startSocPct;
  double time = 0.0;
  double driveTime = 0.0;
  double position = 0.0;
  double detourHalfSec = 0.0;
  double detourHalfKwh = 0.0;
  double minSoc = soc;

  for (auto const & target : stops)
  {
    auto const & station = target.m_station;
    double const stationPosition = std::clamp(station.m_distanceAlongRouteM, 0.0, route.GetLengthM());
    double const energy =
        route.GetEnergyKwh(position, stationPosition) + detourHalfKwh + GetDetourEnergyKwh(model, station) / 2.0;
    double const legTime =
        route.GetTimeSecAt(stationPosition) - route.GetTimeSecAt(position) + detourHalfSec + station.m_detourSec / 2.0;
    soc = std::min(100.0, soc - energy / capacity * 100.0);
    time += legTime;
    driveTime += legTime;
    minSoc = std::min(minSoc, soc);
    if (soc < options.m_minStopSocPct - 0.5)
      plan.m_meetsMinimums = false;

    ChargingStop stop;
    stop.m_station = station;
    stop.m_power = GetBestChargingPower(profile, station.m_sockets);
    stop.m_arrivalSocPct = soc;
    stop.m_arrivalTimeSec = time;
    double const departureSoc = std::max(soc, std::min(target.m_departureSocPct, 100.0));
    stop.m_departureSocPct = departureSoc;
    stop.m_chargingTimeSec = GetChargingTimeSec(profile, stop.m_power, soc, departureSoc);
    stop.m_stopTimeSec = stop.m_chargingTimeSec + kStopOverheadSec;
    if (stop.m_chargingTimeSec > 0.0)
      stop.m_averagePowerKw = capacity * (departureSoc - soc) / 100.0 / (stop.m_chargingTimeSec / 3600.0);
    time += stop.m_stopTimeSec;
    plan.m_stopTimeSec += stop.m_stopTimeSec;
    soc = departureSoc;
    position = stationPosition;
    detourHalfSec = station.m_detourSec / 2.0;
    detourHalfKwh = GetDetourEnergyKwh(model, station) / 2.0;
    plan.m_stops.push_back(std::move(stop));
  }

  double const lastLegTime = route.GetDurationSec() - route.GetTimeSecAt(position) + detourHalfSec;
  soc = std::min(100.0, soc - (route.GetEnergyKwh(position, route.GetLengthM()) + detourHalfKwh) / capacity * 100.0);
  time += lastLegTime;
  driveTime += lastLegTime;
  minSoc = std::min(minSoc, soc);
  if (soc < options.m_minArrivalSocPct - 0.5)
    plan.m_meetsMinimums = false;

  plan.m_arrivalSocPct = soc;
  plan.m_minSocPct = minSoc;
  plan.m_driveTimeSec = driveTime;
  plan.m_totalTimeSec = time;
  return plan;
}

void AddLongerVariants(VehicleProfile const & profile, RouteEnergy const & route, PlanOptions const & options,
                       Plan & plan)
{
  AddLongerVariantsImpl(profile, route, options, plan);
}

Plan EvaluateStopsAdjusting(VehicleProfile const & profile, RouteEnergy const & route, std::vector<StopTarget> stops,
                            PlanOptions const & options)
{
  double const maxSoc = std::min(options.m_maxChargeSocPct, 100.0);
  Plan plan = EvaluateStops(profile, route, stops, options);
  for (size_t attempt = 0; attempt <= stops.size() && !plan.m_meetsMinimums; ++attempt)
  {
    bool changed = false;
    // The first leg that ends below the minimum: charge more at the stop before it.
    for (size_t i = 0; i <= plan.m_stops.size() && !changed; ++i)
    {
      bool const isDestination = i == plan.m_stops.size();
      double const arrival = isDestination ? plan.m_arrivalSocPct : plan.m_stops[i].m_arrivalSocPct;
      double const minimum = isDestination ? options.m_minArrivalSocPct : options.m_minStopSocPct;
      if (arrival >= minimum - 0.5 || i == 0)
        continue;
      auto & target = stops[i - 1].m_departureSocPct;
      double const raised = std::min(maxSoc, target + (minimum - arrival) + kStepPct);
      if (raised > target)
      {
        target = raised;
        changed = true;
      }
    }
    if (!changed)
      break;
    plan = EvaluateStops(profile, route, stops, options);
  }
  return plan;
}

namespace
{
PlanResult PlanChargingImpl(VehicleProfile const & profile, RouteEnergy const & route,
                            std::vector<ChargingStation> const & allStations, PlanOptions const & requestedOptions,
                            DetourChecker & checker)
{
  PlanOptions options = requestedOptions;
  ExcludeSamePlaces(allStations, options);

  PlanResult result;
  if (!profile.IsValid())
  {
    result.m_status = PlanStatus::InvalidProfile;
    return result;
  }
  if (route.IsEmpty())
  {
    result.m_status = PlanStatus::NoRoute;
    return result;
  }

  result.m_candidatesCount = allStations.size();
  result.m_providers = CountProviders(allStations);

  // A copy for the real detours, which all plans share.
  std::vector<ChargingStation> stations = allStations;
  checker.Estimate(stations);

  EnergyModel const model(profile);
  auto const usableStations = [&](PlanOptions const & opts)
  {
    std::vector<ChargingStation *> usable;
    for (auto & s : stations)
      if (IsUsable(profile, s, opts))
        usable.push_back(&s);
    std::stable_sort(usable.begin(), usable.end(), [](ChargingStation const * a, ChargingStation const * b)
    { return a->m_distanceAlongRouteM < b->m_distanceAlongRouteM; });
    return usable;
  };

  auto const usable = usableStations(options);
  result.m_usableCount = usable.size();

  DpResult const best = RunDpWithDetours(profile, model, route, usable, options, checker);
  if (!best.m_found)
  {
    result.m_status = PlanStatus::Unreachable;
    result.m_reachableUntilM = best.m_reachableUntilM;
    result.m_gapEndM = best.m_gapEndM;
    return result;
  }
  result.m_status = PlanStatus::Ok;
  result.m_plan = MakePlan(profile, route, best.m_stops, options);
  if (result.m_plan.m_stops.empty())
    return result;

  // Alternatives: the other priorities and the plan without each of the stations.
  std::vector<Plan> candidates;
  for (auto const priority : {Priority::Fastest, Priority::FewestStops, Priority::LongBreaks})
  {
    if (priority == options.m_priority)
      continue;
    PlanOptions other = options;
    other.m_priority = priority;
    auto const dp = RunDpWithDetours(profile, model, route, usable, other, checker);
    if (!dp.m_found)
      continue;
    Plan plan = MakePlan(profile, route, dp.m_stops, other);
    plan.m_alternativeReason = "priority";
    candidates.push_back(std::move(plan));
  }
  for (auto const & stop : result.m_plan.m_stops)
  {
    PlanOptions other = options;
    other.m_excludedStations.push_back(stop.m_station.GetKey());
    ExcludeSamePlaces(stations, other);
    auto const dp = RunDpWithDetours(profile, model, route, usableStations(other), other, checker);
    if (!dp.m_found)
      continue;
    Plan plan = MakePlan(profile, route, dp.m_stops, options);
    plan.m_alternativeReason = "other_station";
    candidates.push_back(std::move(plan));
  }

  // Variety first: the best plan with another number of stops (fewest stops first), then the fastest of the rest.
  auto const isNew = [&result](Plan const & plan)
  {
    return !plan.m_stops.empty() && !HasSamePlaces(plan, result.m_plan) &&
           std::none_of(result.m_alternatives.begin(), result.m_alternatives.end(),
                        [&plan](Plan const & p) { return HasSamePlaces(p, plan); });
  };
  std::stable_sort(candidates.begin(), candidates.end(), [](Plan const & a, Plan const & b)
  {
    if (a.m_stops.size() != b.m_stops.size())
      return a.m_stops.size() < b.m_stops.size();
    return a.m_totalTimeSec < b.m_totalTimeSec;
  });
  for (auto const & plan : candidates)
  {
    if (plan.m_stops.size() != result.m_plan.m_stops.size() && isNew(plan))
    {
      result.m_alternatives.push_back(plan);
      break;
    }
  }
  std::stable_sort(candidates.begin(), candidates.end(),
                   [](Plan const & a, Plan const & b) { return a.m_totalTimeSec < b.m_totalTimeSec; });
  for (auto const & plan : candidates)
  {
    if (result.m_alternatives.size() >= kMaxAlternatives)
      break;
    if (isNew(plan))
      result.m_alternatives.push_back(plan);
  }
  std::stable_sort(result.m_alternatives.begin(), result.m_alternatives.end(),
                   [](Plan const & a, Plan const & b) { return a.m_totalTimeSec < b.m_totalTimeSec; });
  return result;
}
}  // namespace

PlanResult PlanCharging(VehicleProfile const & profile, RouteEnergy const & route,
                        std::vector<ChargingStation> const & candidates, PlanOptions const & options,
                        DetourProvider * detours)
{
  DetourChecker checker(detours);
  auto result = PlanChargingImpl(profile, route, candidates, options, checker);
  result.m_detourChecks = checker.GetChecksCount();
  return result;
}
}  // namespace ev
