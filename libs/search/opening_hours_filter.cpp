#include "search/opening_hours_filter.hpp"

#include "base/assert.hpp"

#include "3party/opening_hours/opening_hours.hpp"

namespace search
{
std::string DebugPrint(OpeningHoursFilter filter)
{
  switch (filter)
  {
  case OpeningHoursFilter::Off: return "Off";
  case OpeningHoursFilter::OpenNow: return "OpenNow";
  case OpeningHoursFilter::OpenNowOrUnknown: return "OpenNowOrUnknown";
  }
  UNREACHABLE();
}

OpeningHoursStatus GetOpeningHoursStatus(std::string const & openingHours, time_t time)
{
  OpeningHoursStatus status;
  if (openingHours.empty())
    return status;

  osmoh::OpeningHours const oh(openingHours);
  if (!oh.IsValid())
    return status;

  /// @todo We should check closed/open time for specific feature's timezone.
  auto const info = oh.GetInfo(time);
  if (info.state == osmoh::RuleState::Unknown)
    return status;

  status.m_isOpen = (info.state == osmoh::RuleState::Open) ? osm::Yes : osm::No;
  status.m_minutesUntilOpen = (info.nextTimeOpen - time) / 60;
  status.m_minutesUntilClosed = (info.nextTimeClosed - time) / 60;
  return status;
}

bool PassesOpeningHoursFilter(OpeningHoursFilter filter, osm::YesNoUnknown isOpen)
{
  switch (filter)
  {
  case OpeningHoursFilter::Off: return true;
  case OpeningHoursFilter::OpenNow: return isOpen == osm::Yes;
  case OpeningHoursFilter::OpenNowOrUnknown: return isOpen != osm::No;
  }
  UNREACHABLE();
}
}  // namespace search
