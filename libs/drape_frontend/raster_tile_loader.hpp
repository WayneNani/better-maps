#pragma once

// better-maps: raster tile layer ("Luftbild"). Loads the tiles of the layer in background threads: memory cache, disk
// cache, then the server (services/imagery-proxy, Bearer token), and decodes them to RGBA for the renderer.

#include "drape_frontend/raster_tiles.hpp"

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace df::raster
{
struct RasterLayerParams
{
  bool IsActive() const { return m_enabled && !m_url.empty() && !m_token.empty(); }
  bool operator==(RasterLayerParams const & other) const = default;

  bool m_enabled = false;
  // Base URL of the server, e.g. https://luftbild.example.org; tiles are <url>/v1/tiles/{z}/{x}/{y}.jpg.
  std::string m_url;
  // Device token. Never logged.
  std::string m_token;
  // Parent directory of the disk cache (one sub-directory per server); empty = no disk cache.
  std::string m_cacheDir;
  uint64_t m_diskCacheBytes = 200 * 1024 * 1024;
};

struct RasterTileImage
{
  RasterTileKey m_key;
  // Set when the tile could not be loaded (network, HTTP error, broken image).
  bool m_failed = false;
  // HTTP status of a failed download (0 if none).
  int m_httpCode = 0;
  uint32_t m_width = 0;
  uint32_t m_height = 0;
  std::vector<uint8_t> m_rgba;
};

class RasterTileLoader
{
public:
  struct FetchResult
  {
    // HTTP status, or -1 if there was no answer.
    int m_httpCode = -1;
    std::string m_data;
  };
  using Fetcher = std::function<FetchResult(std::string const & url, std::string const & token)>;
  // Called from a loader thread for each finished tile, never after the loader was destroyed.
  using Callback = std::function<void(RasterTileImage && image)>;

  static size_t constexpr kThreads = 4;
  static size_t constexpr kMemoryCacheBytes = 16 * 1024 * 1024;

  RasterTileLoader(RasterLayerParams const & params, Callback callback, Fetcher fetcher = nullptr,
                   size_t threads = kThreads);
  // Does not wait for running downloads: they finish in the background and are dropped.
  ~RasterTileLoader();

  // The tiles to load, most important first. Replaces the previous list; tiles being loaded are not loaded twice.
  void SetWanted(std::vector<RasterTileKey> const & keys);

  // Platform HttpClient, 15 s timeout.
  static Fetcher DefaultFetcher();
  // Decodes a JPEG/PNG tile to RGBA. Only square power-of-two images up to 1024 px are accepted.
  static bool Decode(std::string const & data, RasterTileImage & image);

private:
  struct State;
  static void Work(std::shared_ptr<State> state);

  std::shared_ptr<State> m_state;
};
}  // namespace df::raster
