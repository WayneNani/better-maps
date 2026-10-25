#include "crowd_traffic/crowd_traffic_tests/test_support.hpp"

#include <cmath>
#include <cstring>

namespace crowd_traffic::tests
{
void FixedRandom::Fill(uint8_t * out, size_t size)
{
  std::memset(out, 0x5a, size);
}

Side SideFromChar(char c)
{
  switch (c)
  {
  case 'N': return Side::North;
  case 'E': return Side::East;
  case 'S': return Side::South;
  case 'W': return Side::West;
  default: return Side::None;
  }
}

RoadClass ClassFromInt(int c)
{
  return c >= 1 && c <= 5 ? static_cast<RoadClass>(c) : RoadClass::None;
}

TracePoint ToTracePoint(go_vectors::Pt const & p)
{
  return {p.lat, p.lon, p.t, ClassFromInt(p.cls)};
}

Report ToReport(go_vectors::Rep const & r)
{
  Report res;
  res.m_window = r.window;
  res.m_tile = {r.x, r.y};
  res.m_entry = {SideFromChar(r.entry), r.entryOffset};
  res.m_exit = {SideFromChar(r.exit), r.exitOffset};
  res.m_class = ClassFromInt(r.cls);
  res.m_speedBucket = r.bucket;
  return res;
}

int64_t UtcDay(double unixSeconds)
{
  return static_cast<int64_t>(std::floor(unixSeconds / 86400));
}

std::unique_ptr<Client> MakeClient(std::unique_ptr<RandomSource> rnd, ClientConfig const & config,
                                   std::string const & countersPath)
{
  return std::make_unique<Client>(config, std::move(rnd), countersPath, UtcDay);
}

std::vector<Bundle> RunTrip(Client & client, std::vector<TracePoint> const & points)
{
  std::vector<Bundle> res;
  if (points.empty())
    return res;
  std::vector<double> cum(points.size(), 0.0);
  for (size_t i = 1; i < points.size(); ++i)
    cum[i] = cum[i - 1] + DistanceM(points[i - 1].m_lat, points[i - 1].m_lon, points[i].m_lat, points[i].m_lon);
  double const total = cum.back();
  client.StartTrip({points.back().m_lat, points.back().m_lon});
  for (size_t i = 0; i < points.size(); ++i)
  {
    auto const & p = points[i];
    client.AddSample({p.m_lat, p.m_lon, p.m_time, p.m_class, total - cum[i]});
    for (auto & b : client.TakeDue(p.m_time))
      res.push_back(std::move(b));
  }
  client.EndTrip();
  while (auto const next = client.GetNextDueTime())
    for (auto & b : client.TakeDue(*next))
      res.push_back(std::move(b));
  return res;
}

std::vector<TracePoint> StraightTrip(double lat1, double lon1, double lat2, double lon2, double kmh, double t0,
                                     RoadClass cls)
{
  double const d = DistanceM(lat1, lon1, lat2, lon2);
  auto const n = static_cast<size_t>(d / (kmh / 3.6)) + 1;
  std::vector<TracePoint> res;
  for (size_t i = 0; i <= n; ++i)
  {
    double const f = static_cast<double>(i) / static_cast<double>(n);
    res.push_back({lat1 + f * (lat2 - lat1), lon1 + f * (lon2 - lon1), t0 + f * d / (kmh / 3.6), cls});
  }
  return res;
}

std::string ToHex(std::vector<uint8_t> const & data)
{
  static char const kDigits[] = "0123456789abcdef";
  std::string s;
  s.reserve(data.size() * 2);
  for (uint8_t const b : data)
  {
    s.push_back(kDigits[b >> 4]);
    s.push_back(kDigits[b & 0xf]);
  }
  return s;
}
}  // namespace crowd_traffic::tests
