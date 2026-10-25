#include "crowd_traffic/protocol.hpp"

#include <cmath>
#include <cstdio>
#include <ctime>
#include <sstream>

namespace crowd_traffic
{
int BucketOf(double kmh)
{
  for (int i = kNumSpeedBuckets - 1; i > 0; --i)
    if (kmh >= kSpeedBucketEdges[i])
      return i;
  return 0;
}

bool IsReportable(RoadClass cls)
{
  return cls >= RoadClass::Motorway && cls <= RoadClass::Tertiary;
}

char const * RoadClassName(RoadClass cls)
{
  switch (cls)
  {
  case RoadClass::Motorway: return "MOTORWAY";
  case RoadClass::Trunk: return "TRUNK";
  case RoadClass::Primary: return "PRIMARY";
  case RoadClass::Secondary: return "SECONDARY";
  case RoadClass::Tertiary: return "TERTIARY";
  case RoadClass::None: return "";
  }
  return "";
}

std::string DebugPrint(SegmentKey const & key)
{
  std::ostringstream out;
  out << DebugPrint(key.m_tile) << "/" << SideName(key.m_entry) << "-" << SideName(key.m_exit) << "/"
      << RoadClassName(key.m_class);
  return out.str();
}

int64_t WindowStart(double t, int64_t windowSeconds)
{
  auto const s = static_cast<int64_t>(std::floor(t));
  return s - ((s % windowSeconds) + windowSeconds) % windowSeconds;
}

std::string EncodeBundleJson(std::vector<Report> const & reports)
{
  // Only integers and fixed ASCII names are written, so no escaping is needed.
  std::ostringstream out;
  out << "{\"version\":" << kProtocolVersion << ",\"reports\":[";
  bool first = true;
  for (auto const & r : reports)
  {
    if (!first)
      out << ",";
    first = false;
    out << "{\"window\":" << r.m_window << ",\"tile\":{\"x\":" << r.m_tile.m_x << ",\"y\":" << r.m_tile.m_y
        << "},\"entry\":{\"side\":\"" << SideName(r.m_entry.m_side) << "\",\"offset\":" << r.m_entry.m_offset
        << "},\"exit\":{\"side\":\"" << SideName(r.m_exit.m_side) << "\",\"offset\":" << r.m_exit.m_offset
        << "},\"road_class\":\"" << RoadClassName(r.m_class) << "\",\"speed_bucket\":" << r.m_speedBucket << "}";
  }
  out << "]}";
  return out.str();
}

std::string FormatHttpDate(int64_t unixSeconds)
{
  static char const * const kDays[] = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
  static char const * const kMonths[] = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
                                         "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
  std::time_t const t = static_cast<std::time_t>(unixSeconds);
  std::tm tm{};
  gmtime_r(&t, &tm);
  char buf[64];
  std::snprintf(buf, sizeof(buf), "%s, %02d %s %04d %02d:%02d:%02d GMT", kDays[tm.tm_wday], tm.tm_mday,
                kMonths[tm.tm_mon], tm.tm_year + 1900, tm.tm_hour, tm.tm_min, tm.tm_sec);
  return buf;
}

void AppendVarint(std::vector<uint8_t> & out, uint64_t v)
{
  if (v < (1ULL << 6))
  {
    out.push_back(static_cast<uint8_t>(v));
  }
  else if (v < (1ULL << 14))
  {
    out.push_back(static_cast<uint8_t>(0x40 | (v >> 8)));
    out.push_back(static_cast<uint8_t>(v));
  }
  else if (v < (1ULL << 30))
  {
    out.push_back(static_cast<uint8_t>(0x80 | (v >> 24)));
    for (int shift = 16; shift >= 0; shift -= 8)
      out.push_back(static_cast<uint8_t>(v >> shift));
  }
  else
  {
    out.push_back(static_cast<uint8_t>(0xc0 | ((v >> 56) & 0x3f)));
    for (int shift = 48; shift >= 0; shift -= 8)
      out.push_back(static_cast<uint8_t>(v >> shift));
  }
}

namespace
{
void AppendString(std::vector<uint8_t> & out, std::string const & s)
{
  AppendVarint(out, s.size());
  out.insert(out.end(), s.begin(), s.end());
}

void AppendFields(std::vector<uint8_t> & out, std::vector<std::pair<std::string, std::string>> const & fields)
{
  std::vector<uint8_t> section;
  for (auto const & [name, value] : fields)
  {
    AppendString(section, name);
    AppendString(section, value);
  }
  AppendVarint(out, section.size());
  out.insert(out.end(), section.begin(), section.end());
}
}  // namespace

std::vector<uint8_t> EncodeRequest(std::vector<Report> const & reports, int64_t nowUnixSeconds,
                                   std::string const & authority)
{
  std::string const body = EncodeBundleJson(reports);
  if (body.size() > kMaxBodyBytes)
    return {};

  std::vector<uint8_t> out;
  out.reserve(kPadTo);
  AppendVarint(out, 0);  // Framing indicator: known-length request.
  AppendString(out, "POST");
  AppendString(out, "https");
  AppendString(out, authority);
  AppendString(out, kReportPath);
  AppendFields(out, {{"date", FormatHttpDate(nowUnixSeconds)}, {"content-type", kContentType}});
  AppendString(out, body);
  AppendFields(out, {});  // Empty trailer section.
  if (out.size() > kPadTo)
    return {};
  out.resize(kPadTo, 0);
  return out;
}
}  // namespace crowd_traffic
