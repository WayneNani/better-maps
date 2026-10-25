#pragma once

#include "crowd_traffic/protocol.hpp"
#include "crowd_traffic/randomness.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>

namespace crowd_traffic
{
// Daily report limits across trips (docs/CROWD_TRAFFIC_PRIVACY.md, 7.6): at most m_perDay reports
// per day and at most m_perKey reports per segment key and day.
//
// The per-key counters must survive app restarts but must not store where the user drove.
// They are therefore kept as counters in kBuckets hash buckets, indexed by a keyed BLAKE2b hash
// of the segment key with a random salt that is replaced every day. Nothing else is stored: no
// keys, tiles, times or coordinates. Hash collisions only make the limit stricter (a report may
// be skipped although its key was not used yet). Even with the salt, a bucket stands for about
// 10^5 possible keys in Germany, so the file does not reveal the driven segments.
//
// All counters and the salt are discarded when the day changes.
class DailyLimits
{
public:
  static size_t constexpr kBuckets = 4096;

  struct Limits
  {
    int m_perDay = 96;
    int m_perKey = 2;
  };

  // path: file for persistence; empty keeps the counters in memory only.
  DailyLimits(std::string path, Limits limits, RandomSource & rnd);

  // Counts a report for key on the given day and returns true, or returns false if a limit is
  // reached (nothing is counted then).
  bool TryCount(int64_t day, SegmentKey const & key);
  int GetTotal(int64_t day);
  int GetKeyCount(int64_t day, SegmentKey const & key);

private:
  void RollOver(int64_t day);
  size_t BucketOf(SegmentKey const & key) const;
  void Load();
  void Save() const;

  std::string m_path;
  Limits m_limits;
  RandomSource & m_rnd;
  int64_t m_day = std::numeric_limits<int64_t>::min();
  std::array<uint8_t, 16> m_salt{};
  uint16_t m_total = 0;
  std::array<uint8_t, kBuckets> m_counts{};
};

// Local calendar day (days since 1970-01-01 in the device's time zone) of a Unix time.
int64_t LocalDay(double unixSeconds);
}  // namespace crowd_traffic
