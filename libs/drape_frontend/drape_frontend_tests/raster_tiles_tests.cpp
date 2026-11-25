#include "testing/testing.hpp"

#include "drape_frontend/raster_tile_loader.hpp"
#include "drape_frontend/raster_tiles.hpp"

#include "geometry/mercator.hpp"

#include "3party/stb_image/stb_image_write.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <random>
#include <set>
#include <string>
#include <thread>
#include <vector>

namespace raster_tiles_tests
{
using namespace df::raster;
namespace fs = std::filesystem;

class TempDir
{
public:
  TempDir()
  {
    std::random_device rd;
    m_path = fs::temp_directory_path() / ("bm_raster_test_" + std::to_string(rd()) + std::to_string(rd()));
    fs::create_directories(m_path);
  }
  ~TempDir()
  {
    std::error_code ec;
    fs::remove_all(m_path, ec);
  }
  std::string Str() const { return m_path.string(); }
  fs::path const & Path() const { return m_path; }

private:
  fs::path m_path;
};

std::string MakePng(int size, uint8_t value)
{
  std::vector<uint8_t> pixels(static_cast<size_t>(size) * size * 3, value);
  std::string out;
  stbi_write_png_to_func([](void * ctx, void * data, int n)
  { static_cast<std::string *>(ctx)->append(static_cast<char const *>(data), n); }, &out, size, size, 3,
                         pixels.data(), size * 3);
  return out;
}

UNIT_TEST(RasterTiles_TileRect)
{
  TEST_EQUAL(TileRect({0, 0, 0}), mercator::Bounds::FullRect(), ());
  TEST_EQUAL(TileRect({1, 0, 0}), m2::RectD(-180, 0, 0, 180), ("top left"));
  TEST_EQUAL(TileRect({1, 1, 1}), m2::RectD(0, -180, 180, 0), ("bottom right"));
  auto const r = TileRect({16, 34034, 21956});
  TEST_ALMOST_EQUAL_ABS(r.SizeX(), 360.0 / 65536, 1e-12, ());
  TEST_ALMOST_EQUAL_ABS(r.SizeY(), 360.0 / 65536, 1e-12, ());
}

UNIT_TEST(RasterTiles_KeyValidity)
{
  TEST(RasterTileKey(0, 0, 0).IsValid(), ());
  TEST(!RasterTileKey(0, 1, 0).IsValid(), ());
  TEST(RasterTileKey(3, 7, 7).IsValid(), ());
  TEST(!RasterTileKey(3, 8, 0).IsValid(), ());
  TEST(!RasterTileKey(-1, 0, 0).IsValid(), ());
  TEST(!RasterTileKey(kMaxZoom + 1, 0, 0).IsValid(), ());
  TEST(RasterTileKey(1, 0, 1) < RasterTileKey(1, 1, 0), ());
  TEST(RasterTileKey(1, 5, 5) < RasterTileKey(2, 0, 0), ());
  TEST_EQUAL(DebugPrint(RasterTileKey(14, 1, 2)), "14/1/2", ());
}

UNIT_TEST(RasterTiles_Ancestor)
{
  TEST_EQUAL(Ancestor({16, 34034, 21956}, 0), RasterTileKey(16, 34034, 21956), ());
  TEST_EQUAL(Ancestor({16, 34034, 21956}, 2), RasterTileKey(14, 8508, 5489), ());
  TEST_EQUAL(Ancestor({3, 5, 6}, 3), RasterTileKey(0, 0, 0), ());
  TEST(!Ancestor({3, 5, 6}, 4), ());
  TEST(!Ancestor({3, 5, 6}, -1), ());
  // The ancestor contains the tile.
  TEST(TileRect({14, 8508, 5489}).IsRectInside(TileRect({16, 34034, 21956})), ());
}

UNIT_TEST(RasterTiles_KoelnMatchesStandardXyz)
{
  // Cologne Cathedral, 50.9413 N 6.9583 E, is in tile 16/34034/21956 of the usual XYZ scheme.
  auto const p = mercator::FromLatLon(50.9413, 6.9583);
  m2::RectD const r(p.x - 1e-7, p.y - 1e-7, p.x + 1e-7, p.y + 1e-7);
  auto const tiles = CoveringTiles(r, 16, p, 10);
  TEST_EQUAL(tiles.size(), 1, ());
  TEST_EQUAL(tiles[0], RasterTileKey(16, 34034, 21956), ());
}

UNIT_TEST(RasterTiles_ZoomForScale)
{
  // 256 px wide tiles at zoom 14.
  double const scale14 = 360.0 / (16384.0 * 256.0);
  TEST_EQUAL(ZoomForScale(scale14, 256, 0, 19), 14, ());
  // Same scale, but tiles drawn 512 px wide: one level less.
  TEST_EQUAL(ZoomForScale(scale14, 512, 0, 19), 13, ());
  // Rounded to the nearest level.
  TEST_EQUAL(ZoomForScale(scale14 * 1.3, 256, 0, 19), 14, ());
  TEST_EQUAL(ZoomForScale(scale14 * 1.6, 256, 0, 19), 13, ());
  // Clamped.
  TEST_EQUAL(ZoomForScale(scale14 / 1000, 256, 0, 19), 19, ());
  TEST_EQUAL(ZoomForScale(1000, 256, 2, 19), 2, ());
  TEST_EQUAL(ZoomForScale(0, 256, 3, 19), 3, ());
  TEST_EQUAL(ZoomForScale(scale14, 0, 3, 19), 3, ());
}

UNIT_TEST(RasterTiles_CoveringTiles)
{
  // Inside one tile of zoom 1.
  auto tiles = CoveringTiles({-100, 10, -50, 20}, 1, {-75, 15}, 10);
  TEST_EQUAL(tiles, std::vector<RasterTileKey>{RasterTileKey(1, 0, 0)}, ());

  // Across all four, nearest to the centre first.
  tiles = CoveringTiles({-10, -10, 10, 20}, 1, {5, 15}, 10);
  TEST_EQUAL(tiles.size(), 4, ());
  TEST_EQUAL(tiles[0], RasterTileKey(1, 1, 0), ("the centre is in the top right tile"));
  TEST_EQUAL(std::set<RasterTileKey>(tiles.begin(), tiles.end()).size(), 4, ());

  // A border exactly on a tile edge does not add the neighbour.
  tiles = CoveringTiles({-180, 0, 0, 180}, 1, {-90, 90}, 10);
  TEST_EQUAL(tiles, std::vector<RasterTileKey>{RasterTileKey(1, 0, 0)}, ());

  // Clipped to the world.
  tiles = CoveringTiles({-500, -500, 500, 500}, 1, {0, 0}, 10);
  TEST_EQUAL(tiles.size(), 4, ());
  TEST(CoveringTiles({200, 200, 300, 300}, 3, {0, 0}, 10).empty(), ("outside"));
  TEST(CoveringTiles({0, 0, 0, 0}, 3, {0, 0}, 10).empty(), ("empty rect"));

  // Too many.
  TEST(CoveringTiles(mercator::Bounds::FullRect(), 4, {0, 0}, 255).empty(), ());
  TEST_EQUAL(CoveringTiles(mercator::Bounds::FullRect(), 4, {0, 0}, 256).size(), 256, ());

  // A phone screen of 1080 x 2400 px at zoom 15 with 512 px tiles needs about 3 x 6 tiles (+ partial ones).
  double const scale = 360.0 / (32768.0 * 512.0);
  auto const c = mercator::FromLatLon(50.73, 7.1);
  m2::RectD const screen(c.x - 540 * scale, c.y - 1200 * scale, c.x + 540 * scale, c.y + 1200 * scale);
  tiles = CoveringTiles(screen, 15, c, 64);
  TEST_GREATER_OR_EQUAL(tiles.size(), 3 * 5, ());
  TEST_LESS_OR_EQUAL(tiles.size(), 4 * 6, ());
  for (auto const & t : tiles)
    TEST(TileRect(t).IsIntersect(screen), (t));
  TEST(TileRect(tiles[0]).IsPointInside(c), ("the centre tile comes first"));
}

UNIT_TEST(RasterTiles_WantedTilesLowersZoom)
{
  int zoom = -1;
  auto tiles = WantedTiles({-10, -10, 10, 10}, {0, 0}, 5, 64, zoom);
  TEST_EQUAL(zoom, 5, ());
  TEST_EQUAL(tiles.size(), 4, ());

  // The whole world at zoom 10 would be a million tiles; zoom 3 has 64.
  tiles = WantedTiles(mercator::Bounds::FullRect(), {0, 0}, 10, 64, zoom);
  TEST_EQUAL(zoom, 3, ());
  TEST_EQUAL(tiles.size(), 64, ());

  tiles = WantedTiles({}, {0, 0}, 10, 64, zoom);
  TEST(tiles.empty(), ());
}

UNIT_TEST(RasterTiles_BuildTileUrl)
{
  TEST_EQUAL(BuildTileUrl("https://luftbild.example.org", {14, 8508, 5489}),
             "https://luftbild.example.org/v1/tiles/14/8508/5489.jpg", ());
  TEST_EQUAL(BuildTileUrl("http://10.0.2.2:18200/", {0, 0, 0}), "http://10.0.2.2:18200/v1/tiles/0/0/0.jpg", ());
  TEST_EQUAL(BuildTileUrl("https://h/prefix//", {1, 1, 0}), "https://h/prefix/v1/tiles/1/1/0.jpg", ());
}

UNIT_TEST(RasterTiles_CacheDirName)
{
  auto const a = CacheDirName("https://luftbild.example.org");
  TEST_EQUAL(a, CacheDirName("https://luftbild.example.org"), ());
  TEST_NOT_EQUAL(a, CacheDirName("http://10.0.2.2:18200"), ());
  TEST(a.starts_with("srv_"), ());
  TEST_EQUAL(a.size(), 4 + 16, ());
  TEST(a.find("example") == std::string::npos, ());
}

UNIT_TEST(RasterTiles_MemoryCacheLru)
{
  MemoryTileCache cache(300);
  for (uint32_t i = 0; i < 3; ++i)
    cache.Put({5, i, 0}, std::string(100, 'a' + i));
  TEST_EQUAL(cache.GetBytes(), 300, ());
  std::string data;
  TEST(cache.Get({5, 0, 0}, data), ());
  TEST_EQUAL(data, std::string(100, 'a'), ());
  // Tile 1 is now the least recently used.
  cache.Put({5, 3, 0}, std::string(100, 'd'));
  TEST(!cache.Get({5, 1, 0}, data), ());
  TEST(cache.Get({5, 0, 0}, data), ());
  TEST(cache.Get({5, 2, 0}, data), ());
  TEST(cache.Get({5, 3, 0}, data), ());
  TEST_EQUAL(cache.GetCount(), 3, ());
  // Replacing keeps the size right.
  cache.Put({5, 3, 0}, std::string(50, 'e'));
  TEST_EQUAL(cache.GetBytes(), 250, ());
  // Too large for the cache: not stored.
  cache.Put({5, 9, 0}, std::string(301, 'x'));
  TEST(!cache.Get({5, 9, 0}, data), ());
  cache.Clear();
  TEST_EQUAL(cache.GetCount(), 0, ());
  TEST_EQUAL(cache.GetBytes(), 0, ());
}

UNIT_TEST(RasterTiles_DiskCacheFileNames)
{
  TEST_EQUAL(DiskTileCache::FileName({14, 8508, 5489}), "14_8508_5489.jpg", ());
  TEST_EQUAL(DiskTileCache::ParseFileName("14_8508_5489.jpg"), RasterTileKey(14, 8508, 5489), ());
  for (auto const * bad : {"14_8508_5489.png", "14_8508.jpg", "x_1_1.jpg", "3_8_0.jpg", "1_0_0.jpgx", ".tmp-1_0_0.jpg",
                           "01_0_0.jpg", "1_0_0.jpg.bak", "-1_0_0.jpg", "1_-0_0.jpg"})
    TEST(!DiskTileCache::ParseFileName(bad), (bad));
}

UNIT_TEST(RasterTiles_DiskCachePutGetEvict)
{
  TempDir dir;
  DiskTileCache cache(dir.Str(), 1000, std::chrono::hours(1));
  std::string data;
  TEST(!cache.Get({1, 0, 0}, data), ());
  for (uint32_t i = 0; i < 10; ++i)
    TEST(cache.Put({10, i, 0}, std::string(100, '0' + i)), ());
  TEST_EQUAL(cache.GetBytes(), 1000, ());
  TEST(fs::exists(dir.Path() / "10_3_0.jpg"), ());
  // Use tile 0; tile 1 becomes the least recently used.
  TEST(cache.Get({10, 0, 0}, data), ());
  TEST_EQUAL(data, std::string(100, '0'), ());
  TEST(cache.Put({10, 10, 0}, std::string(100, 'x')), ());
  // Evicted down to 90 % (900 bytes).
  TEST_EQUAL(cache.GetBytes(), 900, ());
  TEST_EQUAL(cache.GetCount(), 9, ());
  TEST(!cache.Get({10, 1, 0}, data), ());
  TEST(!cache.Get({10, 2, 0}, data), ());
  TEST(!fs::exists(dir.Path() / "10_1_0.jpg"), ());
  TEST(cache.Get({10, 0, 0}, data), ());
  TEST(cache.Get({10, 10, 0}, data), ());
  // Overwriting keeps the size right.
  TEST(cache.Put({10, 10, 0}, std::string(50, 'y')), ());
  TEST_EQUAL(cache.GetBytes(), 850, ());
  // Empty or too large data is refused.
  TEST(!cache.Put({10, 11, 0}, ""), ());
  TEST(!cache.Put({10, 11, 0}, std::string(1001, 'z')), ());
}

UNIT_TEST(RasterTiles_DiskCacheMaxAge)
{
  TempDir dir;
  auto now = DiskTileCache::Clock::now();
  DiskTileCache cache(dir.Str(), 1000, std::chrono::hours(24), [&now] { return now; });
  TEST(cache.Put({3, 1, 1}, "tile"), ());
  now += std::chrono::hours(23);
  std::string data;
  TEST(cache.Get({3, 1, 1}, data), ());
  now += std::chrono::hours(2);
  TEST(!cache.Get({3, 1, 1}, data), ());
  TEST(!fs::exists(dir.Path() / "3_1_1.jpg"), ());
  TEST_EQUAL(cache.GetCount(), 0, ());
}

UNIT_TEST(RasterTiles_DiskCacheReopen)
{
  TempDir dir;
  {
    DiskTileCache cache(dir.Str(), 10000, std::chrono::hours(1));
    for (uint32_t i = 0; i < 5; ++i)
    {
      TEST(cache.Put({5, i, 1}, std::string(100, 'a')), ());
      // Tile 0 is the oldest download.
      auto const t = fs::file_time_type::clock::now() - std::chrono::minutes(10 - i);
      fs::last_write_time(dir.Path() / DiskTileCache::FileName({5, i, 1}), t);
    }
  }
  // Left-overs: a temporary file is removed, unknown files are ignored.
  std::ofstream(dir.Path() / ".tmp-5_9_1.jpg") << "x";
  std::ofstream(dir.Path() / "README") << "x";

  // Reopened with a smaller limit: the oldest tiles go first.
  DiskTileCache cache(dir.Str(), 300, std::chrono::hours(1));
  TEST_EQUAL(cache.GetCount(), 2, ());
  TEST_EQUAL(cache.GetBytes(), 200, ());
  std::string data;
  TEST(cache.Get({5, 4, 1}, data), ());
  TEST(cache.Get({5, 3, 1}, data), ());
  TEST(!cache.Get({5, 0, 1}, data), ());
  TEST(!fs::exists(dir.Path() / ".tmp-5_9_1.jpg"), ());
  TEST(fs::exists(dir.Path() / "README"), ());
}

UNIT_TEST(RasterTiles_DiskCacheFileVanished)
{
  TempDir dir;
  DiskTileCache cache(dir.Str(), 1000, std::chrono::hours(1));
  TEST(cache.Put({2, 1, 1}, "tile"), ());
  fs::remove(dir.Path() / "2_1_1.jpg");
  std::string data;
  TEST(!cache.Get({2, 1, 1}, data), ());
  TEST_EQUAL(cache.GetCount(), 0, ());
}

UNIT_TEST(RasterTiles_Decode)
{
  RasterTileImage image;
  TEST(RasterTileLoader::Decode(MakePng(256, 7), image), ());
  TEST_EQUAL(image.m_width, 256, ());
  TEST_EQUAL(image.m_height, 256, ());
  TEST_EQUAL(image.m_rgba.size(), 256 * 256 * 4, ());
  TEST_EQUAL(image.m_rgba[0], 7, ());
  TEST_EQUAL(image.m_rgba[3], 255, ("opaque alpha"));

  RasterTileImage bad;
  TEST(!RasterTileLoader::Decode("", bad), ());
  TEST(!RasterTileLoader::Decode("not an image", bad), ());
  TEST(!RasterTileLoader::Decode(MakePng(100, 1), bad), ("not a power of two"));
  TEST(!RasterTileLoader::Decode(MakePng(2048, 1), bad), ("too large"));
}

class Collector
{
public:
  void Add(RasterTileImage && image)
  {
    std::lock_guard lock(m_mutex);
    m_images.push_back(std::move(image));
    m_cv.notify_all();
  }

  bool WaitFor(size_t count)
  {
    std::unique_lock lock(m_mutex);
    return m_cv.wait_for(lock, std::chrono::seconds(10), [&] { return m_images.size() >= count; });
  }

  std::vector<RasterTileImage> Images()
  {
    std::lock_guard lock(m_mutex);
    return m_images;
  }

private:
  std::mutex m_mutex;
  std::condition_variable m_cv;
  std::vector<RasterTileImage> m_images;
};

UNIT_TEST(RasterTiles_LoaderFetchesCachesAndReportsFailures)
{
  TempDir dir;
  RasterLayerParams params;
  params.m_enabled = true;
  params.m_url = "http://server";
  params.m_token = "secret-token-0123456789";
  params.m_cacheDir = dir.Str();

  std::string const png = MakePng(256, 42);
  std::mutex fetchMutex;
  std::vector<std::string> urls;
  std::atomic<int> fetches = 0;
  auto fetcher = [&](std::string const & url, std::string const & token)
  {
    ++fetches;
    {
      std::lock_guard lock(fetchMutex);
      urls.push_back(url);
    }
    TEST_EQUAL(token, "secret-token-0123456789", ());
    RasterTileLoader::FetchResult r;
    if (url.find("/v1/tiles/5/") != std::string::npos)
    {
      r.m_httpCode = 404;
      return r;
    }
    r.m_httpCode = 200;
    r.m_data = png;
    return r;
  };

  {
    Collector collector;
    RasterTileLoader loader(params, [&](RasterTileImage && img) { collector.Add(std::move(img)); }, fetcher, 2);
    loader.SetWanted({{14, 1, 2}, {14, 1, 3}, {5, 1, 1}});
    TEST(collector.WaitFor(3), ());
    int ok = 0, failed = 0;
    for (auto const & img : collector.Images())
    {
      if (img.m_failed)
      {
        ++failed;
        TEST_EQUAL(img.m_key, RasterTileKey(5, 1, 1), ());
        TEST_EQUAL(img.m_httpCode, 404, ());
      }
      else
      {
        ++ok;
        TEST_EQUAL(img.m_width, 256, ());
        TEST_EQUAL(img.m_rgba[0], 42, ());
      }
    }
    TEST_EQUAL(ok, 2, ());
    TEST_EQUAL(failed, 1, ());
    TEST_EQUAL(fetches.load(), 3, ());
    {
      std::lock_guard lock(fetchMutex);
      TEST(std::find(urls.begin(), urls.end(), "http://server/v1/tiles/14/1/2.jpg") != urls.end(), (urls));
    }

    // Again: from the memory cache, no download.
    loader.SetWanted({{14, 1, 2}});
    TEST(collector.WaitFor(4), ());
    TEST_EQUAL(fetches.load(), 3, ());
  }

  // A new loader (e.g. after a restart) takes the tile from the disk cache of this server.
  TEST(fs::exists(dir.Path() / CacheDirName("http://server") / "14_1_2.jpg"), ());
  {
    Collector collector;
    RasterTileLoader loader(params, [&](RasterTileImage && img) { collector.Add(std::move(img)); }, fetcher, 1);
    loader.SetWanted({{14, 1, 2}});
    TEST(collector.WaitFor(1), ());
    TEST(!collector.Images()[0].m_failed, ());
    TEST_EQUAL(fetches.load(), 3, ());
  }
}

UNIT_TEST(RasterTiles_LoaderNoCallbackAfterDestruction)
{
  RasterLayerParams params;
  params.m_enabled = true;
  params.m_url = "http://server";
  params.m_token = "secret-token-0123456789";
  std::atomic<bool> destroyed = false;
  std::atomic<bool> lateCallback = false;
  std::atomic<int> started = 0;
  auto fetcher = [&](std::string const &, std::string const &)
  {
    ++started;
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    return RasterTileLoader::FetchResult{};
  };
  {
    RasterTileLoader loader(params, [&](RasterTileImage &&)
    {
      if (destroyed)
        lateCallback = true;
    }, fetcher, 2);
    loader.SetWanted({{3, 1, 1}, {3, 1, 2}});
    while (started < 1)
      std::this_thread::sleep_for(std::chrono::milliseconds(5));
  }
  destroyed = true;
  // The running downloads finish in the background; their results must be dropped.
  std::this_thread::sleep_for(std::chrono::milliseconds(400));
  TEST(!lateCallback, ());
}

UNIT_TEST(RasterTiles_LayerParams)
{
  RasterLayerParams p;
  TEST(!p.IsActive(), ());
  p.m_enabled = true;
  p.m_url = "https://x";
  TEST(!p.IsActive(), ("no token"));
  p.m_token = "t";
  TEST(p.IsActive(), ());
  auto q = p;
  TEST(p == q, ());
  q.m_token = "u";
  TEST(!(p == q), ());
}
}  // namespace raster_tiles_tests
