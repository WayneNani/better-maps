#pragma once

#include "indexer/feature_decl.hpp"

#include "geometry/point2d.hpp"

#include <cstdint>
#include <ctime>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace ev
{
// Real-time status of a charging station, e.g. from the AFIR data of the operators (Mobilithek in Germany).
// Not used yet: a server that collects the data and an implementation of ChargerStatusProvider are missing.
enum class Availability : uint8_t
{
  Unknown,
  // At least one charging point is free.
  Available,
  // All charging points are in use.
  Occupied,
  OutOfOrder,
};

std::string DebugPrint(Availability availability);

struct ChargerStatus
{
  Availability m_availability = Availability::Unknown;
  uint32_t m_freePoints = 0;
  uint32_t m_totalPoints = 0;
  // When the operator reported the status.
  time_t m_updated = 0;
};

// What a provider needs to identify a charging station: the map feature, its position and operator.
struct ChargerStatusRequest
{
  FeatureID m_featureId;
  m2::PointD m_point;
  std::string m_operator;
};

// Source of real-time charging station status. The planner asks it for the candidate stations and avoids
// occupied (waiting time kOccupiedPenaltySec) and broken stations.
class ChargerStatusProvider
{
public:
  using OnStatuses = std::function<void(std::vector<ChargerStatus> const & statuses)>;

  virtual ~ChargerStatusProvider() = default;

  // Calls |onStatuses| (on any thread) with the status of each request, in the same order. Must call it also on
  // errors, with Availability::Unknown.
  virtual void RequestStatuses(std::vector<ChargerStatusRequest> const & requests, OnStatuses const & onStatuses) = 0;
};

// No real-time data: every station is Availability::Unknown.
class NoChargerStatusProvider : public ChargerStatusProvider
{
public:
  void RequestStatuses(std::vector<ChargerStatusRequest> const & requests, OnStatuses const & onStatuses) override
  {
    onStatuses(std::vector<ChargerStatus>(requests.size()));
  }
};
}  // namespace ev
