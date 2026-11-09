#include "ev_planning/charger_status.hpp"

#include "base/assert.hpp"

namespace ev
{
std::string DebugPrint(Availability availability)
{
  switch (availability)
  {
  case Availability::Unknown: return "Unknown";
  case Availability::Available: return "Available";
  case Availability::Occupied: return "Occupied";
  case Availability::OutOfOrder: return "OutOfOrder";
  }
  UNREACHABLE();
}
}  // namespace ev
