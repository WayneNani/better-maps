#include "drape_frontend/raster_tiles.hpp"

#include "geometry/mercator.hpp"

#include "base/logging.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <system_error>
#include <tuple>

namespace df::raster
{
namespace fs = std::filesystem;

bool RasterTileKey::IsValid() const
{
  if (m_z < 0 || m_z > kMaxZoom)
    return false;
  uint64_t const n = uint64_t{1} << m_z;
  return m_x < n && m_y < n;
}

bool RasterTileKey::operator<(RasterTileKey const & other) const
{
  return std::tie(m_z, m_x, m_y) < std::tie(other.m_z, other.m_x, other.m_y);
}

size_t RasterTileKeyHash::operator()(RasterTileKey const & k) const
{
  return std::hash<uint64_t>()((static_cast<uint64_t>(k.m_z) << 58) ^ (static_cast<uint64_t>(k.m_x) << 29) ^ k.m_y);
}

std::string DebugPrint(RasterTileKey const & key)
{
  std::ostringstream out;
  out << key.m_z << "/" << key.m_x << "/" << key.m_y;
  return out.str();
}

m2::RectD TileRect(RasterTileKey const & key)
{
  double const size = mercator::Bounds::kRangeX / static_cast<double>(uint64_t{1} << key.m_z);
  double const minX = mercator::Bounds::kMinX + key.m_x * size;
  double const maxY = mercator::Bounds::kMaxY - key.m_y * size;
  return {minX, maxY - size, minX + size, maxY};
}

std::optional<RasterTileKey> Ancestor(RasterTileKey const & key, int levels)
{
  if (levels < 0 || levels > key.m_z)
    return std::nullopt;
  return RasterTileKey(key.m_z - levels, key.m_x >> levels, key.m_y >> levels);
}

int ZoomForScale(double mercatorPerPixel, double tileScreenPx, int minZoom, int maxZoom)
{
  if (!(mercatorPerPixel > 0.0) || !(tileScreenPx > 0.0))
    return minZoom;
  // A tile at zoom z is kRangeX / 2^z mercator units wide.
  double const z = std::log2(mercator::Bounds::kRangeX / (mercatorPerPixel * tileScreenPx));
  if (!std::isfinite(z))
    return minZoom;
  return std::clamp(static_cast<int>(std::lround(z)), minZoom, maxZoom);
}

std::vector<RasterTileKey> CoveringTiles(m2::RectD const & rect, int z, m2::PointD const & center, size_t maxCount)
{
  std::vector<RasterTileKey> result;
  if (z < 0 || z > kMaxZoom || rect.IsEmptyInterior())
    return result;
  m2::RectD clipped = rect;
  if (!clipped.Intersect(mercator::Bounds::FullRect()) || clipped.IsEmptyInterior())
    return result;

  int64_t const n = int64_t{1} << z;
  double const size = mercator::Bounds::kRangeX / static_cast<double>(n);
  auto const toIndex = [&](double v)
  { return std::clamp(static_cast<int64_t>(std::floor(v / size)), int64_t{0}, n - 1); };
  // A border lying exactly on a tile edge does not pull in the next tile.
  double constexpr kEps = 1e-12;
  int64_t const x0 = toIndex(clipped.minX() - mercator::Bounds::kMinX);
  int64_t const x1 = toIndex(clipped.maxX() - mercator::Bounds::kMinX - kEps);
  int64_t const y0 = toIndex(mercator::Bounds::kMaxY - clipped.maxY());
  int64_t const y1 = toIndex(mercator::Bounds::kMaxY - clipped.minY() - kEps);
  uint64_t const count = static_cast<uint64_t>(x1 - x0 + 1) * static_cast<uint64_t>(y1 - y0 + 1);
  if (count > maxCount)
    return result;

  result.reserve(count);
  for (int64_t y = y0; y <= y1; ++y)
    for (int64_t x = x0; x <= x1; ++x)
      result.emplace_back(z, static_cast<uint32_t>(x), static_cast<uint32_t>(y));

  std::sort(result.begin(), result.end(), [&center](RasterTileKey const & a, RasterTileKey const & b)
  {
    double const da = TileRect(a).Center().SquaredLength(center);
    double const db = TileRect(b).Center().SquaredLength(center);
    return da != db ? da < db : a < b;
  });
  return result;
}

std::vector<RasterTileKey> WantedTiles(m2::RectD const & rect, m2::PointD const & center, int preferredZoom,
                                       size_t maxCount, int & zoom)
{
  for (zoom = std::clamp(preferredZoom, 0, kMaxZoom); zoom >= 0; --zoom)
  {
    auto tiles = CoveringTiles(rect, zoom, center, maxCount);
    if (!tiles.empty())
      return tiles;
    if (rect.IsEmptyInterior())
      break;
  }
  zoom = 0;
  return {};
}

std::string BuildTileUrl(std::string const & baseUrl, RasterTileKey const & key)
{
  std::string url = baseUrl;
  while (!url.empty() && url.back() == '/')
    url.pop_back();
  url += "/v1/tiles/" + std::to_string(key.m_z) + "/" + std::to_string(key.m_x) + "/" + std::to_string(key.m_y) +
         ".jpg";
  return url;
}

std::string CacheDirName(std::string const & baseUrl)
{
  // FNV-1a, 64 bit.
  uint64_t h = 1469598103934665603ULL;
  for (unsigned char c : baseUrl)
  {
    h ^= c;
    h *= 1099511628211ULL;
  }
  char buf[17];
  std::snprintf(buf, sizeof(buf), "%016llx", static_cast<unsigned long long>(h));
  return std::string("srv_") + buf;
}

// MemoryTileCache --------------------------------------------------------------------------------------------------

bool MemoryTileCache::Get(RasterTileKey const & key, std::string & data)
{
  std::lock_guard lock(m_mutex);
  auto const it = m_index.find(key);
  if (it == m_index.end())
    return false;
  m_lru.splice(m_lru.begin(), m_lru, it->second);
  data = it->second->second;
  return true;
}

void MemoryTileCache::Put(RasterTileKey const & key, std::string const & data)
{
  if (data.size() > m_maxBytes)
    return;
  std::lock_guard lock(m_mutex);
  if (auto const it = m_index.find(key); it != m_index.end())
  {
    m_bytes -= it->second->second.size();
    m_lru.erase(it->second);
    m_index.erase(it);
  }
  m_lru.emplace_front(key, data);
  m_index[key] = m_lru.begin();
  m_bytes += data.size();
  EvictLocked();
}

void MemoryTileCache::EvictLocked()
{
  while (m_bytes > m_maxBytes && !m_lru.empty())
  {
    auto const & last = m_lru.back();
    m_bytes -= last.second.size();
    m_index.erase(last.first);
    m_lru.pop_back();
  }
}

void MemoryTileCache::Clear()
{
  std::lock_guard lock(m_mutex);
  m_lru.clear();
  m_index.clear();
  m_bytes = 0;
}

size_t MemoryTileCache::GetBytes() const
{
  std::lock_guard lock(m_mutex);
  return m_bytes;
}

size_t MemoryTileCache::GetCount() const
{
  std::lock_guard lock(m_mutex);
  return m_index.size();
}

// DiskTileCache ----------------------------------------------------------------------------------------------------

namespace
{
DiskTileCache::Clock::time_point ToSystemTime(fs::file_time_type t)
{
  // C++20 clock_cast is not available everywhere yet; convert through "now" of both clocks.
  return DiskTileCache::Clock::now() +
         std::chrono::duration_cast<DiskTileCache::Clock::duration>(t - fs::file_time_type::clock::now());
}
}  // namespace

DiskTileCache::DiskTileCache(std::string dir, uint64_t maxBytes, std::chrono::seconds maxAge,
                             std::function<Clock::time_point()> now)
  : m_dir(std::move(dir))
  , m_maxBytes(maxBytes)
  , m_maxAge(maxAge)
  , m_now(now ? std::move(now) : [] { return Clock::now(); })
{}

std::string DiskTileCache::FileName(RasterTileKey const & key)
{
  return std::to_string(key.m_z) + "_" + std::to_string(key.m_x) + "_" + std::to_string(key.m_y) + ".jpg";
}

std::optional<RasterTileKey> DiskTileCache::ParseFileName(std::string const & name)
{
  int z = 0;
  unsigned long long x = 0, y = 0;
  char tail[8] = {};
  if (std::sscanf(name.c_str(), "%d_%llu_%llu%7s", &z, &x, &y, tail) != 4 || std::string(tail) != ".jpg")
    return std::nullopt;
  RasterTileKey key(z, static_cast<uint32_t>(x), static_cast<uint32_t>(y));
  if (!key.IsValid() || x != key.m_x || y != key.m_y || FileName(key) != name)
    return std::nullopt;
  return key;
}

std::string DiskTileCache::PathOf(RasterTileKey const & key) const
{
  return (fs::path(m_dir) / FileName(key)).string();
}

void DiskTileCache::InitLocked()
{
  if (m_initialized)
    return;
  m_initialized = true;
  std::error_code ec;
  fs::create_directories(m_dir, ec);
  std::vector<Entry> found;
  for (auto it = fs::directory_iterator(m_dir, ec); !ec && it != fs::directory_iterator(); it.increment(ec))
  {
    auto const name = it->path().filename().string();
    if (name.starts_with(".tmp"))
    {
      std::error_code rmEc;
      fs::remove(it->path(), rmEc);
      continue;
    }
    auto const key = ParseFileName(name);
    if (!key)
      continue;
    std::error_code e1, e2;
    auto const size = it->file_size(e1);
    auto const time = it->last_write_time(e2);
    if (e1 || e2)
      continue;
    found.push_back({*key, size, ToSystemTime(time)});
  }
  // Newest first, so that the oldest end up at the back of the LRU list.
  std::sort(found.begin(), found.end(), [](Entry const & a, Entry const & b) { return a.m_stored > b.m_stored; });
  for (auto const & e : found)
  {
    m_lru.push_back(e);
    m_index[e.m_key] = std::prev(m_lru.end());
    m_bytes += e.m_size;
  }
  EvictLocked();
  LOG(LINFO, ("Raster tile disk cache:", m_index.size(), "tiles,", m_bytes / 1024, "KB"));
}

bool DiskTileCache::Get(RasterTileKey const & key, std::string & data)
{
  std::lock_guard lock(m_mutex);
  InitLocked();
  auto const it = m_index.find(key);
  if (it == m_index.end())
    return false;
  if (m_maxAge.count() > 0 && m_now() - it->second->m_stored > m_maxAge)
  {
    RemoveLocked(it->second, true /* deleteFile */);
    return false;
  }
  std::ifstream in(PathOf(key), std::ios::binary);
  std::ostringstream buf;
  buf << in.rdbuf();
  if (!in.good() && !in.eof())
  {
    RemoveLocked(it->second, true /* deleteFile */);
    return false;
  }
  data = buf.str();
  if (data.empty())
  {
    RemoveLocked(it->second, true /* deleteFile */);
    return false;
  }
  m_lru.splice(m_lru.begin(), m_lru, it->second);
  return true;
}

bool DiskTileCache::Put(RasterTileKey const & key, std::string const & data)
{
  if (data.empty() || data.size() > m_maxBytes)
    return false;
  std::lock_guard lock(m_mutex);
  InitLocked();
  auto const path = PathOf(key);
  auto const tmp = (fs::path(m_dir) / (".tmp-" + FileName(key))).string();
  {
    std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
    out.write(data.data(), static_cast<std::streamsize>(data.size()));
    if (!out.good())
    {
      std::error_code ec;
      fs::remove(tmp, ec);
      return false;
    }
  }
  std::error_code ec;
  fs::rename(tmp, path, ec);
  if (ec)
  {
    fs::remove(tmp, ec);
    return false;
  }
  if (auto const it = m_index.find(key); it != m_index.end())
    RemoveLocked(it->second, false /* deleteFile */);
  m_lru.push_front({key, data.size(), m_now()});
  m_index[key] = m_lru.begin();
  m_bytes += data.size();
  EvictLocked();
  return true;
}

void DiskTileCache::EvictLocked()
{
  if (m_bytes <= m_maxBytes)
    return;
  uint64_t const target = m_maxBytes / 10 * 9;
  while (m_bytes > target && !m_lru.empty())
    RemoveLocked(std::prev(m_lru.end()), true /* deleteFile */);
}

void DiskTileCache::RemoveLocked(List::iterator it, bool deleteFile)
{
  if (deleteFile)
  {
    std::error_code ec;
    fs::remove(PathOf(it->m_key), ec);
  }
  m_bytes -= it->m_size;
  m_index.erase(it->m_key);
  m_lru.erase(it);
}

uint64_t DiskTileCache::GetBytes()
{
  std::lock_guard lock(m_mutex);
  InitLocked();
  return m_bytes;
}

size_t DiskTileCache::GetCount()
{
  std::lock_guard lock(m_mutex);
  InitLocked();
  return m_index.size();
}
}  // namespace df::raster
