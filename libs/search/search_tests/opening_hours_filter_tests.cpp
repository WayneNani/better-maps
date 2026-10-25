#include "testing/testing.hpp"

#include "search/opening_hours_filter.hpp"

#include <ctime>
#include <string>

namespace opening_hours_filter_tests
{
using search::GetOpeningHoursStatus;
using search::OpeningHoursFilter;
using search::PassesOpeningHoursFilter;

// Wednesday, 2026-10-21 at |hour|:|minute| in local time, as opening hours are evaluated in local time.
time_t MakeLocalTime(int hour, int minute = 0)
{
  tm t = {};
  t.tm_year = 2026 - 1900;
  t.tm_mon = 10 - 1;
  t.tm_mday = 21;
  t.tm_hour = hour;
  t.tm_min = minute;
  t.tm_isdst = -1;
  return mktime(&t);
}

UNIT_TEST(OpeningHoursFilter_Passes)
{
  TEST(PassesOpeningHoursFilter(OpeningHoursFilter::Off, osm::Yes), ());
  TEST(PassesOpeningHoursFilter(OpeningHoursFilter::Off, osm::No), ());
  TEST(PassesOpeningHoursFilter(OpeningHoursFilter::Off, osm::Unknown), ());

  TEST(PassesOpeningHoursFilter(OpeningHoursFilter::OpenNow, osm::Yes), ());
  TEST(!PassesOpeningHoursFilter(OpeningHoursFilter::OpenNow, osm::No), ());
  TEST(!PassesOpeningHoursFilter(OpeningHoursFilter::OpenNow, osm::Unknown), ());

  TEST(PassesOpeningHoursFilter(OpeningHoursFilter::OpenNowOrUnknown, osm::Yes), ());
  TEST(!PassesOpeningHoursFilter(OpeningHoursFilter::OpenNowOrUnknown, osm::No), ());
  TEST(PassesOpeningHoursFilter(OpeningHoursFilter::OpenNowOrUnknown, osm::Unknown), ());
}

UNIT_TEST(OpeningHoursFilter_JavaValues)
{
  // These values are passed from Java code via JNI.
  TEST_EQUAL(static_cast<int>(OpeningHoursFilter::Off), 0, ());
  TEST_EQUAL(static_cast<int>(OpeningHoursFilter::OpenNow), 1, ());
  TEST_EQUAL(static_cast<int>(OpeningHoursFilter::OpenNowOrUnknown), 2, ());
}

UNIT_TEST(OpeningHoursFilter_Status)
{
  time_t const noon = MakeLocalTime(12);

  {
    auto const status = GetOpeningHoursStatus("Mo-Su 08:00-20:00", noon);
    TEST_EQUAL(status.m_isOpen, osm::Yes, ());
    TEST_EQUAL(status.m_minutesUntilClosed, 8 * 60, ());
  }
  {
    auto const status = GetOpeningHoursStatus("Mo-Su 18:00-23:00", noon);
    TEST_EQUAL(status.m_isOpen, osm::No, ());
    TEST_EQUAL(status.m_minutesUntilOpen, 6 * 60, ());
  }
  {
    // Wednesday is off.
    auto const status = GetOpeningHoursStatus("Mo-Tu,Th-Su 08:00-20:00; We off", noon);
    TEST_EQUAL(status.m_isOpen, osm::No, ());
  }
  TEST_EQUAL(GetOpeningHoursStatus("24/7", noon).m_isOpen, osm::Yes, ());
  TEST_EQUAL(GetOpeningHoursStatus("", noon).m_isOpen, osm::Unknown, ());
  TEST_EQUAL(GetOpeningHoursStatus("sometimes, ask the owner", noon).m_isOpen, osm::Unknown, ());
}

UNIT_TEST(OpeningHoursFilter_StatusDependsOnTime)
{
  std::string const hours = "Mo-Fr 09:00-12:30,13:30-18:00";
  TEST_EQUAL(GetOpeningHoursStatus(hours, MakeLocalTime(8, 59)).m_isOpen, osm::No, ());
  TEST_EQUAL(GetOpeningHoursStatus(hours, MakeLocalTime(9, 0)).m_isOpen, osm::Yes, ());
  TEST_EQUAL(GetOpeningHoursStatus(hours, MakeLocalTime(12, 45)).m_isOpen, osm::No, ());
  TEST_EQUAL(GetOpeningHoursStatus(hours, MakeLocalTime(17, 59)).m_isOpen, osm::Yes, ());
  // The closing minute is still treated as open.
  TEST_EQUAL(GetOpeningHoursStatus(hours, MakeLocalTime(18, 1)).m_isOpen, osm::No, ());
}
}  // namespace opening_hours_filter_tests
