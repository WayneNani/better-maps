#include "drape_frontend/indoor_cache.hpp"

#include "geometry/mercator.hpp"

#include "base/logging.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <set>
#include <sstream>
#include <system_error>

namespace df::indoor
{
namespace fs = std::filesystem;

namespace
{
double SnapDown(double v)
{
  return std::floor(v / kListGridDeg + 1e-9) * kListGridDeg;
}

double SnapUp(double v)
{
  return std::ceil(v / kListGridDeg - 1e-9) * kListGridDeg;
}

double IntersectionArea(m2::RectD a, m2::RectD const & b)
{
  if (!a.Intersect(b))
    return 0;
  return a.SizeX() * a.SizeY();
}

bool ReadFile(fs::path const & path, std::string & data)
{
  std::ifstream in(path, std::ios::binary);
  if (!in)
    return false;
  std::ostringstream ss;
  ss << in.rdbuf();
  data = ss.str();
  return !in.bad();
}

bool WriteFileAtomically(fs::path const & path, std::string const & data)
{
  auto tmp = path;
  tmp += ".tmp";
  {
    std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
    if (!out)
      return false;
    out.write(data.data(), static_cast<std::streamsize>(data.size()));
    if (!out)
      return false;
  }
  std::error_code ec;
  fs::rename(tmp, path, ec);
  return !ec;
}
}  // namespace

bool LatLonBox::Contains(LatLonBox const & o) const
{
  return m_minLon <= o.m_minLon && m_minLat <= o.m_minLat && m_maxLon >= o.m_maxLon && m_maxLat >= o.m_maxLat;
}

LatLonBox ToLatLonBox(m2::RectD const & r)
{
  auto const lb = mercator::ToLatLon(r.LeftBottom());
  auto const rt = mercator::ToLatLon(r.RightTop());
  return {lb.m_lon, lb.m_lat, rt.m_lon, rt.m_lat};
}

LatLonBox ListQueryBox(m2::RectD const & viewMercator)
{
  auto const view = ToLatLonBox(viewMercator);
  double const w = std::min(view.m_maxLon - view.m_minLon, kMaxListQueryDeg / 2);
  double const h = std::min(view.m_maxLat - view.m_minLat, kMaxListQueryDeg / 2);
  double const cx = (view.m_minLon + view.m_maxLon) / 2, cy = (view.m_minLat + view.m_maxLat) / 2;
  // View plus half its size on each side = twice its size around the center.
  LatLonBox box{SnapDown(cx - w), SnapDown(cy - h), SnapUp(cx + w), SnapUp(cy + h)};
  box.m_minLon = std::max(box.m_minLon, -180.0);
  box.m_maxLon = std::min(box.m_maxLon, 180.0);
  box.m_minLat = std::max(box.m_minLat, -85.0);
  box.m_maxLat = std::min(box.m_maxLat, 85.0);
  return box;
}

std::string ToQueryString(LatLonBox const & b)
{
  return FormatFixed(b.m_minLon, 6) + "," + FormatFixed(b.m_minLat, 6) + "," + FormatFixed(b.m_maxLon, 6) + "," +
         FormatFixed(b.m_maxLat, 6);
}

bool VenueListCache::Covers(m2::RectD const & view, Clock::time_point now) const
{
  auto const box = ToLatLonBox(view);
  return std::any_of(m_entries.begin(), m_entries.end(), [&](Entry const & e)
  {
    auto const maxAge = e.m_failed ? std::chrono::duration_cast<Clock::duration>(kFailureBackoff)
                                   : std::chrono::duration_cast<Clock::duration>(kMaxAge);
    return now - e.m_time < maxAge && e.m_box.Contains(box);
  });
}

void VenueListCache::Add(LatLonBox const & box, std::vector<VenueInfo> && venues, Clock::time_point now)
{
  // Newer answers replace those they contain.
  std::erase_if(m_entries, [&](Entry const & e) { return box.Contains(e.m_box); });
  m_entries.push_front({box, std::move(venues), now, false});
  while (m_entries.size() > kMaxEntries)
    m_entries.pop_back();
}

void VenueListCache::AddFailure(LatLonBox const & box, Clock::time_point now)
{
  m_entries.push_front({box, {}, now, true});
  while (m_entries.size() > kMaxEntries)
    m_entries.pop_back();
}

std::vector<VenueInfo> VenueListCache::VenuesIntersecting(m2::RectD const & rect, Clock::time_point now) const
{
  std::vector<VenueInfo> result;
  std::set<std::string> seen;
  for (auto const & e : m_entries)
  {
    if (e.m_failed || now - e.m_time >= kMaxAge)
      continue;
    for (auto const & v : e.m_venues)
      if (v.m_rect.IsIntersect(rect) && seen.insert(v.m_id).second)
        result.push_back(v);
  }
  return result;
}

std::optional<size_t> SelectVenue(std::vector<VenueInfo> const & venues, m2::RectD const & view,
                                  std::string const & currentId)
{
  m2::PointD const center = view.Center();
  std::optional<size_t> current, containing, covering;
  double containingArea = 0, coveringArea = 0;
  for (size_t i = 0; i < venues.size(); ++i)
  {
    auto const & r = venues[i].m_rect;
    if (!r.IsIntersect(view))
      continue;
    if (venues[i].m_id == currentId)
      current = i;
    if (r.IsPointInside(center))
    {
      double const a = r.SizeX() * r.SizeY();
      if (!containing || a < containingArea)
      {
        containing = i;
        containingArea = a;
      }
    }
    double const a = IntersectionArea(r, view);
    if (!covering || a > coveringArea)
    {
      covering = i;
      coveringArea = a;
    }
  }
  if (current && venues[*current].m_rect.IsPointInside(center))
    return current;
  if (containing)
    return containing;
  if (current)
    return current;
  return covering;
}

VenueDiskCache::VenueDiskCache(std::string dir, size_t maxFiles, uint64_t maxBytes)
  : m_dir(std::move(dir))
  , m_maxFiles(maxFiles)
  , m_maxBytes(maxBytes)
{
  std::error_code ec;
  fs::create_directories(m_dir, ec);
}

bool VenueDiskCache::Get(std::string const & id, std::string & json, std::string & etag)
{
  if (!IsValidVenueId(id))
    return false;
  fs::path const base = fs::path(m_dir) / id;
  if (!ReadFile(fs::path(base).concat(".json"), json) || json.empty())
    return false;
  if (!ReadFile(fs::path(base).concat(".etag"), etag))
    etag.clear();
  Touch(id);
  return true;
}

void VenueDiskCache::Put(std::string const & id, std::string const & json, std::string const & etag)
{
  if (!IsValidVenueId(id) || m_dir.empty())
    return;
  fs::path const base = fs::path(m_dir) / id;
  if (!WriteFileAtomically(fs::path(base).concat(".etag"), etag) ||
      !WriteFileAtomically(fs::path(base).concat(".json"), json))
  {
    LOG(LWARNING, ("Indoor: can't write the venue cache"));
    Remove(id);
    return;
  }
  Evict();
}

void VenueDiskCache::Touch(std::string const & id)
{
  if (!IsValidVenueId(id))
    return;
  std::error_code ec;
  fs::last_write_time((fs::path(m_dir) / id).concat(".json"), fs::file_time_type::clock::now(), ec);
}

void VenueDiskCache::Remove(std::string const & id)
{
  if (!IsValidVenueId(id))
    return;
  std::error_code ec;
  fs::remove((fs::path(m_dir) / id).concat(".json"), ec);
  fs::remove((fs::path(m_dir) / id).concat(".etag"), ec);
}

size_t VenueDiskCache::GetCount() const
{
  size_t n = 0;
  std::error_code ec;
  for (auto const & e : fs::directory_iterator(m_dir, ec))
    if (e.path().extension() == ".json")
      ++n;
  return n;
}

uint64_t VenueDiskCache::GetBytes() const
{
  uint64_t n = 0;
  std::error_code ec;
  for (auto const & e : fs::directory_iterator(m_dir, ec))
    if (e.path().extension() == ".json")
      n += e.file_size(ec);
  return n;
}

void VenueDiskCache::Evict()
{
  struct Item
  {
    fs::file_time_type m_time;
    std::string m_id;
    uint64_t m_bytes;
  };
  std::vector<Item> items;
  uint64_t total = 0;
  std::error_code ec;
  for (auto const & e : fs::directory_iterator(m_dir, ec))
  {
    if (e.path().extension() != ".json")
      continue;
    std::error_code ec2;
    auto const bytes = e.file_size(ec2);
    auto const time = e.last_write_time(ec2);
    if (ec2)
      continue;
    items.push_back({time, e.path().stem().string(), bytes});
    total += bytes;
  }
  std::sort(items.begin(), items.end(), [](Item const & a, Item const & b) { return a.m_time < b.m_time; });
  size_t count = items.size();
  for (auto const & it : items)
  {
    if (count <= m_maxFiles && total <= m_maxBytes)
      break;
    Remove(it.m_id);
    --count;
    total -= it.m_bytes;
  }
}
}  // namespace df::indoor
