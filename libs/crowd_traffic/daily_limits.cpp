#include "crowd_traffic/daily_limits.hpp"

#include "3party/monocypher/monocypher.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <ctime>
#include <fstream>
#include <iterator>
#include <utility>
#include <vector>

namespace crowd_traffic
{
namespace
{
char constexpr kMagic[4] = {'C', 'T', 'D', '1'};
size_t constexpr kFileSize = sizeof(kMagic) + 8 + 16 + 2 + DailyLimits::kBuckets;

void PutInt(std::vector<uint8_t> & out, uint64_t v, int bytes)
{
  for (int i = bytes - 1; i >= 0; --i)
    out.push_back(static_cast<uint8_t>(v >> (8 * i)));
}

uint64_t GetInt(uint8_t const * p, int bytes)
{
  uint64_t v = 0;
  for (int i = 0; i < bytes; ++i)
    v = (v << 8) | p[i];
  return v;
}
}  // namespace

int64_t LocalDay(double unixSeconds)
{
  auto const t = static_cast<std::time_t>(std::floor(unixSeconds));
  std::tm tm{};
  localtime_r(&t, &tm);
  // Days since 1970-01-01 of the local calendar date (proleptic Gregorian, Howard Hinnant).
  int64_t y = tm.tm_year + 1900;
  int64_t const m = tm.tm_mon + 1;
  int64_t const d = tm.tm_mday;
  y -= m <= 2;
  int64_t const era = (y >= 0 ? y : y - 399) / 400;
  int64_t const yoe = y - era * 400;
  int64_t const doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
  int64_t const doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
  return era * 146097 + doe - 719468;
}

DailyLimits::DailyLimits(std::string path, Limits limits, RandomSource & rnd)
  : m_path(std::move(path))
  , m_limits(limits)
  , m_rnd(rnd)
{
  Load();
}

void DailyLimits::RollOver(int64_t day)
{
  if (day == m_day)
    return;
  m_day = day;
  m_rnd.Fill(m_salt.data(), m_salt.size());
  m_total = 0;
  m_counts.fill(0);
  Save();
}

size_t DailyLimits::BucketOf(SegmentKey const & key) const
{
  uint8_t msg[11];
  for (int i = 0; i < 4; ++i)
  {
    msg[i] = static_cast<uint8_t>(static_cast<uint32_t>(key.m_tile.m_x) >> (8 * (3 - i)));
    msg[4 + i] = static_cast<uint8_t>(static_cast<uint32_t>(key.m_tile.m_y) >> (8 * (3 - i)));
  }
  msg[8] = static_cast<uint8_t>(key.m_entry);
  msg[9] = static_cast<uint8_t>(key.m_exit);
  msg[10] = static_cast<uint8_t>(key.m_class);
  uint8_t hash[8];
  crypto_blake2b_keyed(hash, sizeof(hash), m_salt.data(), m_salt.size(), msg, sizeof(msg));
  return static_cast<size_t>(GetInt(hash, sizeof(hash)) % kBuckets);
}

bool DailyLimits::TryCount(int64_t day, SegmentKey const & key)
{
  RollOver(day);
  size_t const b = BucketOf(key);
  if (m_total >= m_limits.m_perDay || m_counts[b] >= m_limits.m_perKey)
    return false;
  ++m_total;
  ++m_counts[b];
  Save();
  return true;
}

int DailyLimits::GetTotal(int64_t day)
{
  RollOver(day);
  return m_total;
}

int DailyLimits::GetKeyCount(int64_t day, SegmentKey const & key)
{
  RollOver(day);
  return m_counts[BucketOf(key)];
}

void DailyLimits::Load()
{
  if (m_path.empty())
    return;
  std::ifstream in(m_path, std::ios::binary);
  if (!in)
    return;
  std::vector<uint8_t> data((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
  if (data.size() != kFileSize || !std::equal(std::begin(kMagic), std::end(kMagic), data.begin()))
    return;  // Unknown or damaged file: start with fresh (empty) counters.
  uint8_t const * p = data.data() + sizeof(kMagic);
  m_day = static_cast<int64_t>(GetInt(p, 8));
  p += 8;
  std::copy(p, p + m_salt.size(), m_salt.begin());
  p += m_salt.size();
  m_total = static_cast<uint16_t>(GetInt(p, 2));
  p += 2;
  std::copy(p, p + kBuckets, m_counts.begin());
}

void DailyLimits::Save() const
{
  if (m_path.empty())
    return;
  std::vector<uint8_t> data(std::begin(kMagic), std::end(kMagic));
  PutInt(data, static_cast<uint64_t>(m_day), 8);
  data.insert(data.end(), m_salt.begin(), m_salt.end());
  PutInt(data, m_total, 2);
  data.insert(data.end(), m_counts.begin(), m_counts.end());
  // Write to a temporary file and rename it, so that a crash never leaves a damaged file.
  std::string const tmp = m_path + ".tmp";
  {
    std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
    if (!out)
      return;
    out.write(reinterpret_cast<char const *>(data.data()), static_cast<std::streamsize>(data.size()));
    if (!out)
      return;
  }
  std::rename(tmp.c_str(), m_path.c_str());
}
}  // namespace crowd_traffic
