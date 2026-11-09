#include "ev_planning/plan_json.hpp"

#include "geometry/mercator.hpp"

#include "base/logging.hpp"

#include <cstdint>
#include <vector>

#include "glaze/json.hpp"

namespace ev
{
namespace json
{
struct OptionsDto
{
  double startSoc = 80.0;
  double minArrivalSoc = 20.0;
  double minStopSoc = 10.0;
  double maxChargeSoc = 100.0;
  std::string priority = "fastest";
  std::vector<std::string> preferredProviders;
  std::vector<std::string> excludedProviders;
  double minPowerKw = 50.0;
  std::vector<std::string> preferredAmenities;
  std::vector<std::string> excludedStations;
  int64_t departureTime = 0;
};

struct SocketDto
{
  std::string type;
  uint32_t count = 0;
  double powerKw = 0.0;
};

struct AmenityDto
{
  std::string type;
  std::string kind;
  std::string name;
  double distanceM = 0.0;
  std::optional<bool> openAtArrival;
  double lat = 0.0;
  double lon = 0.0;
};

struct LongerDto
{
  double departureSocPct = 0.0;
  double extraStopTimeSec = 0.0;
  double extraTotalTimeSec = 0.0;
  double arrivalSocPct = 0.0;
};

struct StopDto
{
  std::string key;
  std::string name;
  std::string title;
  std::string provider;
  std::string operatorName;
  std::string brand;
  std::string network;
  double lat = 0.0;
  double lon = 0.0;
  double distanceAlongRouteM = 0.0;
  double distanceFromRouteM = 0.0;
  double detourSec = 0.0;
  double powerKw = 0.0;
  bool powerKnown = true;
  bool dc = false;
  std::vector<SocketDto> sockets;
  uint32_t capacity = 0;
  std::string openingHours;
  std::string price;
  std::string availability;
  double arrivalSocPct = 0.0;
  double departureSocPct = 0.0;
  double chargingTimeSec = 0.0;
  double stopTimeSec = 0.0;
  double averagePowerKw = 0.0;
  double arrivalTimeSec = 0.0;
  bool preferredProvider = false;
  std::vector<std::string> matchedAmenities;
  std::vector<AmenityDto> amenities;
  std::optional<LongerDto> longer;
};

struct PlanDto
{
  std::vector<StopDto> stops;
  double distanceM = 0.0;
  double driveTimeSec = 0.0;
  double stopTimeSec = 0.0;
  double totalTimeSec = 0.0;
  double arrivalSocPct = 0.0;
  double minSocPct = 0.0;
  bool meetsMinimums = true;
  std::string priority;
  std::string reason;
};

struct ProviderDto
{
  std::string key;
  std::string name;
  uint32_t stations = 0;
};

struct StopInputDto
{
  std::string key;
  std::string title;
  double lat = 0.0;
  double lon = 0.0;
  double powerKw = 0.0;
  bool dc = true;
  double departureSoc = 0.0;
};

struct PlanResultDto
{
  std::string status;
  PlanDto plan;
  std::vector<PlanDto> alternatives;
  double reachableUntilM = 0.0;
  double gapEndM = 0.0;
  uint64_t candidates = 0;
  uint64_t usable = 0;
  std::vector<ProviderDto> providers;
};

std::string ToString(Availability availability)
{
  switch (availability)
  {
  case Availability::Unknown: return "unknown";
  case Availability::Available: return "available";
  case Availability::Occupied: return "occupied";
  case Availability::OutOfOrder: return "out_of_order";
  }
  return "unknown";
}

StopDto MakeStop(ChargingStop const & stop)
{
  auto const & s = stop.m_station;
  StopDto dto;
  dto.key = s.GetKey();
  dto.name = s.m_name;
  dto.title = s.GetTitle();
  dto.provider = s.GetProviderName();
  dto.operatorName = s.m_operator;
  dto.brand = s.m_brand;
  dto.network = s.m_network;
  auto const ll = mercator::ToLatLon(s.m_point);
  dto.lat = ll.m_lat;
  dto.lon = ll.m_lon;
  dto.distanceAlongRouteM = s.m_distanceAlongRouteM;
  dto.distanceFromRouteM = s.m_distanceFromRouteM;
  dto.detourSec = s.m_detourSec;
  dto.powerKw = stop.m_power.m_powerKw;
  dto.powerKnown = stop.m_power.m_isPowerKnown;
  dto.dc = stop.m_power.m_isDc;
  for (auto const & socket : s.m_sockets)
    dto.sockets.push_back({socket.type, socket.count, socket.power});
  dto.capacity = s.m_capacity;
  dto.openingHours = s.m_openingHours;
  dto.price = s.m_price;
  dto.availability = ToString(s.m_status.m_availability);
  dto.arrivalSocPct = stop.m_arrivalSocPct;
  dto.departureSocPct = stop.m_departureSocPct;
  dto.chargingTimeSec = stop.m_chargingTimeSec;
  dto.stopTimeSec = stop.m_stopTimeSec;
  dto.averagePowerKw = stop.m_averagePowerKw;
  dto.arrivalTimeSec = stop.m_arrivalTimeSec;
  dto.preferredProvider = stop.m_isPreferredProvider;
  for (auto const type : stop.m_matchedAmenities)
    dto.matchedAmenities.emplace_back(ev::ToString(type));
  for (auto const & a : s.m_amenities)
  {
    auto const all = mercator::ToLatLon(a.m_point);
    dto.amenities.push_back({std::string(ev::ToString(a.m_type)), a.m_kind, a.m_name, a.m_distanceM, a.m_openAtArrival,
                             all.m_lat, all.m_lon});
  }
  if (stop.m_longer)
  {
    dto.longer = LongerDto{stop.m_longer->m_departureSocPct, stop.m_longer->m_extraStopTimeSec,
                           stop.m_longer->m_extraTotalTimeSec, stop.m_longer->m_arrivalSocPct};
  }
  return dto;
}

PlanDto MakePlan(Plan const & plan)
{
  PlanDto dto;
  for (auto const & stop : plan.m_stops)
    dto.stops.push_back(MakeStop(stop));
  dto.distanceM = plan.m_distanceM;
  dto.driveTimeSec = plan.m_driveTimeSec;
  dto.stopTimeSec = plan.m_stopTimeSec;
  dto.totalTimeSec = plan.m_totalTimeSec;
  dto.arrivalSocPct = plan.m_arrivalSocPct;
  dto.minSocPct = plan.m_minSocPct;
  dto.meetsMinimums = plan.m_meetsMinimums;
  dto.priority = std::string(ev::ToString(plan.m_priority));
  dto.reason = plan.m_alternativeReason;
  return dto;
}

template <typename T>
std::string Write(T const & value)
{
  std::string json;
  if (auto const error = glz::write_json(value, json))
    LOG(LERROR, ("Can't write JSON:", glz::format_error(error, json)));
  return json;
}
}  // namespace json

std::optional<PlanOptions> PlanOptionsFromJson(std::string_view json)
{
  json::OptionsDto dto;
  if (auto const error = glz::read<glz::opts{.error_on_unknown_keys = false}>(dto, json))
  {
    LOG(LWARNING, ("Can't read plan options:", glz::format_error(error, json)));
    return {};
  }
  PlanOptions options;
  options.m_startSocPct = dto.startSoc;
  options.m_minArrivalSocPct = dto.minArrivalSoc;
  options.m_minStopSocPct = dto.minStopSoc;
  options.m_maxChargeSocPct = dto.maxChargeSoc;
  if (auto const priority = PriorityFromString(dto.priority))
    options.m_priority = *priority;
  options.m_preferredProviders = std::move(dto.preferredProviders);
  options.m_excludedProviders = std::move(dto.excludedProviders);
  options.m_minPowerKw = dto.minPowerKw;
  for (auto const & a : dto.preferredAmenities)
    if (auto const type = AmenityTypeFromString(a))
      options.m_preferredAmenities.push_back(*type);
  options.m_excludedStations = std::move(dto.excludedStations);
  if (dto.departureTime > 0)
    options.m_departureTime = static_cast<time_t>(dto.departureTime);
  return options;
}

std::optional<std::vector<StopInput>> StopInputsFromJson(std::string_view json)
{
  std::vector<json::StopInputDto> dtos;
  if (auto const error = glz::read<glz::opts{.error_on_unknown_keys = false}>(dtos, json))
  {
    LOG(LWARNING, ("Can't read stops:", glz::format_error(error, json)));
    return {};
  }
  std::vector<StopInput> stops;
  for (auto const & dto : dtos)
  {
    StopInput stop;
    stop.m_station.m_key = dto.key;
    stop.m_station.m_name = dto.title;
    stop.m_station.m_point = mercator::FromLatLon(dto.lat, dto.lon);
    // The socket that the plan used.
    stop.m_station.m_sockets = {{dto.dc ? "type2_combo" : "type2", 1, dto.powerKw}};
    stop.m_departureSocPct = dto.departureSoc;
    stops.push_back(std::move(stop));
  }
  return stops;
}

std::string ToJson(PlanOptions const & options)
{
  json::OptionsDto dto;
  dto.startSoc = options.m_startSocPct;
  dto.minArrivalSoc = options.m_minArrivalSocPct;
  dto.minStopSoc = options.m_minStopSocPct;
  dto.maxChargeSoc = options.m_maxChargeSocPct;
  dto.priority = std::string(ToString(options.m_priority));
  dto.preferredProviders = options.m_preferredProviders;
  dto.excludedProviders = options.m_excludedProviders;
  dto.minPowerKw = options.m_minPowerKw;
  for (auto const type : options.m_preferredAmenities)
    dto.preferredAmenities.emplace_back(ToString(type));
  dto.excludedStations = options.m_excludedStations;
  dto.departureTime = options.m_departureTime ? static_cast<int64_t>(*options.m_departureTime) : 0;
  return json::Write(dto);
}

std::string ToJson(PlanResult const & result)
{
  json::PlanResultDto dto;
  dto.status = std::string(ToString(result.m_status));
  dto.plan = json::MakePlan(result.m_plan);
  for (auto const & a : result.m_alternatives)
    dto.alternatives.push_back(json::MakePlan(a));
  dto.reachableUntilM = result.m_reachableUntilM;
  dto.gapEndM = result.m_gapEndM;
  dto.candidates = result.m_candidatesCount;
  dto.usable = result.m_usableCount;
  for (auto const & p : result.m_providers)
    dto.providers.push_back({p.m_provider.m_key, p.m_provider.m_name, p.m_stations});
  return json::Write(dto);
}

std::string ToJson(Plan const & plan)
{
  return json::Write(json::MakePlan(plan));
}
}  // namespace ev
