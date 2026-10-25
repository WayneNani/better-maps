#include "testing/testing.hpp"

#include "crowd_traffic/crowd_traffic_tests/test_support.hpp"
#include "crowd_traffic/daily_limits.hpp"

#include "platform/platform.hpp"

#include <cstdio>
#include <ctime>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

namespace daily_limits_tests
{
using namespace crowd_traffic;

class ScopedPath
{
public:
  ScopedPath() : m_path(GetPlatform().WritablePathForFile("crowd_traffic_test_counters.bin"))
  {
    std::remove(m_path.c_str());
  }
  ~ScopedPath() { std::remove(m_path.c_str()); }
  std::string const & Get() const { return m_path; }

private:
  std::string m_path;
};

std::vector<uint8_t> ReadAll(std::string const & path)
{
  std::ifstream in(path, std::ios::binary);
  return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

SegmentKey Key(int x, int y = 43010)
{
  return {{x, y}, Side::West, Side::East, RoadClass::Motorway};
}

UNIT_TEST(CrowdTraffic_DailyLimitPerKey)
{
  SeededRandom rnd(1);
  DailyLimits limits({}, {96, 2}, rnd);
  TEST(limits.TryCount(100, Key(1)), ());
  TEST(limits.TryCount(100, Key(1)), ());
  // At most two reports per segment key and day (there and back again).
  TEST(!limits.TryCount(100, Key(1)), ());
  TEST_EQUAL(limits.GetKeyCount(100, Key(1)), 2, ());
  TEST_EQUAL(limits.GetTotal(100), 2, ());
  // The next day starts from zero.
  TEST(limits.TryCount(101, Key(1)), ());
  TEST_EQUAL(limits.GetTotal(101), 1, ());
}

UNIT_TEST(CrowdTraffic_DailyLimitTotal)
{
  SeededRandom rnd(2);
  DailyLimits limits({}, {96, 2}, rnd);
  int accepted = 0;
  for (int i = 0; i < 200; ++i)
    accepted += limits.TryCount(5, Key(1000 + i)) ? 1 : 0;
  // Hash collisions may only make the limit stricter.
  TEST_EQUAL(accepted, 96, ());
  TEST_EQUAL(limits.GetTotal(5), 96, ());
  TEST(!limits.TryCount(5, Key(5000)), ());
}

UNIT_TEST(CrowdTraffic_DailyLimitsPersistWithoutLocations)
{
  ScopedPath path;
  SeededRandom rnd(3);
  {
    DailyLimits limits(path.Get(), {96, 2}, rnd);
    TEST(limits.TryCount(200, Key(70394)), ());
    TEST(limits.TryCount(200, Key(70394)), ());
    TEST(limits.TryCount(200, Key(70395)), ());
  }
  auto const data = ReadAll(path.Get());
  // Fixed size: magic, day, salt, total and the bucket counters; no keys, tiles or times.
  TEST_EQUAL(data.size(), 4 + 8 + 16 + 2 + DailyLimits::kBuckets, ());
  std::string const raw(data.begin(), data.end());
  for (std::string const & needle : {std::string("70394"), std::string("\x00\x01\x12\xfa", 4)})
    TEST_EQUAL(raw.find(needle), std::string::npos, ());
  int nonZero = 0;
  for (size_t i = 4 + 8 + 16 + 2; i < data.size(); ++i)
    nonZero += data[i] != 0 ? 1 : 0;
  TEST_LESS_OR_EQUAL(nonZero, 2, ());

  {
    // Another app start on the same day continues counting.
    SeededRandom rnd2(4);
    DailyLimits limits(path.Get(), {96, 2}, rnd2);
    TEST_EQUAL(limits.GetTotal(200), 3, ());
    TEST(!limits.TryCount(200, Key(70394)), ());
    TEST(limits.TryCount(200, Key(70395)), ());
    // The next day deletes the counters and the salt.
    TEST_EQUAL(limits.GetTotal(201), 0, ());
  }
  auto const next = ReadAll(path.Get());
  TEST_EQUAL(next.size(), data.size(), ());
  TEST(!std::equal(data.begin() + 12, data.begin() + 28, next.begin() + 12), ("salt must change"));
}

UNIT_TEST(CrowdTraffic_DailyLimitsDamagedFile)
{
  ScopedPath path;
  {
    std::ofstream out(path.Get(), std::ios::binary);
    out << "garbage";
  }
  SeededRandom rnd(5);
  DailyLimits limits(path.Get(), {96, 2}, rnd);
  TEST_EQUAL(limits.GetTotal(1), 0, ());
  TEST(limits.TryCount(1, Key(1)), ());
}

UNIT_TEST(CrowdTraffic_LocalDay)
{
  // Independent of the time zone: one day later is one day more, and the day of a time is
  // stable within a few hours of local noon (also on days with a daylight saving change).
  std::time_t const now = std::time(nullptr);
  std::tm tm{};
  localtime_r(&now, &tm);
  tm.tm_hour = 12;
  tm.tm_min = tm.tm_sec = 0;
  double const noon = static_cast<double>(std::mktime(&tm));
  TEST_EQUAL(LocalDay(noon + 86400) - LocalDay(noon), 1, ());
  TEST_EQUAL(LocalDay(noon - 3 * 3600), LocalDay(noon + 3 * 3600), ());
  TEST_EQUAL(LocalDay(noon - 18 * 3600) + 1, LocalDay(noon), ());
  // Days since the epoch.
  TEST_LESS(std::abs(LocalDay(noon) - static_cast<int64_t>(noon / 86400)), 2, ());
}
}  // namespace daily_limits_tests
