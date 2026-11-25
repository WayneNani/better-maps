#pragma once

// better-maps: raster tile layer ("Luftbild"). Pure logic of the layer, without graphics: tile math in
// WebMercator XYZ, URL building and the memory and disk caches of encoded tiles.
//
// The XYZ tiling (origin top left, y down) maps linearly onto the mercator plane of the engine
// (mercator::Bounds, [-180, 180] x [-180, 180], y up).

#include "geometry/rect2d.hpp"

#include <chrono>
#include <cstdint>
#include <functional>
#include <list>
#include <mutex>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

namespace df::raster
{
int constexpr kMaxZoom = 22;

struct RasterTileKey
{
  RasterTileKey() = default;
  RasterTileKey(int z, uint32_t x, uint32_t y) : m_z(z), m_x(x), m_y(y) {}

  bool IsValid() const;

  bool operator==(RasterTileKey const & other) const = default;
  bool operator<(RasterTileKey const & other) const;

  int m_z = 0;
  uint32_t m_x = 0;
  uint32_t m_y = 0;
};

struct RasterTileKeyHash
{
  size_t operator()(RasterTileKey const & k) const;
};

std::string DebugPrint(RasterTileKey const & key);

// Mercator rect of a tile.
m2::RectD TileRect(RasterTileKey const & key);

// The ancestor |levels| levels up, nullopt above zoom 0.
std::optional<RasterTileKey> Ancestor(RasterTileKey const & key, int levels);

// Zoom level at which a tile is drawn about |tileScreenPx| pixels wide, for |mercatorPerPixel| (ScreenBase::GetScale),
// clamped to [minZoom, maxZoom].
int ZoomForScale(double mercatorPerPixel, double tileScreenPx, int minZoom, int maxZoom);

// Tiles at zoom |z| intersecting |rect| (clipped to the mercator bounds), nearest to |center| first. Empty if there
// would be more than |maxCount|.
std::vector<RasterTileKey> CoveringTiles(m2::RectD const & rect, int z, m2::PointD const & center, size_t maxCount);

// The wanted tiles for a view: the covering tiles at |preferredZoom|, or at a lower zoom if there would be more than
// |maxCount| (e.g. in the 3D view, whose clip rect reaches far). |zoom| receives the chosen zoom.
std::vector<RasterTileKey> WantedTiles(m2::RectD const & rect, m2::PointD const & center, int preferredZoom,
                                       size_t maxCount, int & zoom);

// "<base>/v1/tiles/<z>/<x>/<y>.jpg"; a trailing slash of |baseUrl| is ignored.
std::string BuildTileUrl(std::string const & baseUrl, RasterTileKey const & key);

// Name of the cache directory of a server: tiles of different servers never mix. Contains no part of the URL.
std::string CacheDirName(std::string const & baseUrl);

// LRU cache of encoded tiles in memory, bounded by the total size. Thread-safe.
class MemoryTileCache
{
public:
  explicit MemoryTileCache(size_t maxBytes) : m_maxBytes(maxBytes) {}

  bool Get(RasterTileKey const & key, std::string & data);
  void Put(RasterTileKey const & key, std::string const & data);
  void Clear();

  size_t GetBytes() const;
  size_t GetCount() const;

private:
  using List = std::list<std::pair<RasterTileKey, std::string>>;

  void EvictLocked();

  size_t const m_maxBytes;
  mutable std::mutex m_mutex;
  List m_lru;  // front = most recently used
  std::unordered_map<RasterTileKey, List::iterator, RasterTileKeyHash> m_index;
  size_t m_bytes = 0;
};

// LRU cache of encoded tiles on disk (<dir>/<z>_<x>_<y>.jpg), bounded by the total size (evicts down to 90 %) and the
// age of a file (then it counts as missing). The order of use lives in memory and starts from the file times after a
// restart. Thread-safe; the directory is indexed on first use (call from a background thread).
class DiskTileCache
{
public:
  using Clock = std::chrono::system_clock;

  DiskTileCache(std::string dir, uint64_t maxBytes, std::chrono::seconds maxAge,
                std::function<Clock::time_point()> now = nullptr);

  bool Get(RasterTileKey const & key, std::string & data);
  bool Put(RasterTileKey const & key, std::string const & data);

  uint64_t GetBytes();
  size_t GetCount();
  std::string const & GetDir() const { return m_dir; }

  static std::optional<RasterTileKey> ParseFileName(std::string const & name);
  static std::string FileName(RasterTileKey const & key);

private:
  struct Entry
  {
    RasterTileKey m_key;
    uint64_t m_size = 0;
    Clock::time_point m_stored;
  };
  using List = std::list<Entry>;

  void InitLocked();
  void EvictLocked();
  void RemoveLocked(List::iterator it, bool deleteFile);
  std::string PathOf(RasterTileKey const & key) const;

  std::string const m_dir;
  uint64_t const m_maxBytes;
  std::chrono::seconds const m_maxAge;
  std::function<Clock::time_point()> m_now;

  std::mutex m_mutex;
  bool m_initialized = false;
  List m_lru;  // front = most recently used
  std::unordered_map<RasterTileKey, List::iterator, RasterTileKeyHash> m_index;
  uint64_t m_bytes = 0;
};
}  // namespace df::raster
