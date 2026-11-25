#include "drape_frontend/raster_tile_loader.hpp"

#include "platform/http_client.hpp"

#include "base/logging.hpp"

#include "3party/stb_image/stb_image.h"

#include <condition_variable>
#include <deque>
#include <filesystem>
#include <mutex>
#include <thread>
#include <unordered_set>

namespace df::raster
{
struct RasterTileLoader::State
{
  State(RasterLayerParams const & params, Callback && callback, Fetcher && fetcher)
    : m_url(params.m_url)
    , m_token(params.m_token)
    , m_callback(std::move(callback))
    , m_fetcher(std::move(fetcher))
    , m_memory(kMemoryCacheBytes)
  {
    if (!params.m_cacheDir.empty() && params.m_diskCacheBytes > 0)
    {
      auto const dir = (std::filesystem::path(params.m_cacheDir) / CacheDirName(params.m_url)).string();
      m_disk = std::make_unique<DiskTileCache>(dir, params.m_diskCacheBytes, std::chrono::hours(24 * 30));
    }
  }

  std::string const m_url;
  std::string const m_token;
  Callback const m_callback;
  Fetcher const m_fetcher;
  MemoryTileCache m_memory;
  std::unique_ptr<DiskTileCache> m_disk;

  std::mutex m_mutex;
  std::condition_variable m_cv;
  std::deque<RasterTileKey> m_queue;
  std::unordered_set<RasterTileKey, RasterTileKeyHash> m_inFlight;
  bool m_cancelled = false;

  // Held while calling m_callback, so that the destructor can wait for a running callback.
  std::mutex m_callbackMutex;
};

RasterTileLoader::RasterTileLoader(RasterLayerParams const & params, Callback callback, Fetcher fetcher,
                                   size_t threads)
  : m_state(std::make_shared<State>(params, std::move(callback), fetcher ? std::move(fetcher) : DefaultFetcher()))
{
  for (size_t i = 0; i < std::max<size_t>(threads, 1); ++i)
    std::thread(&RasterTileLoader::Work, m_state).detach();
}

RasterTileLoader::~RasterTileLoader()
{
  {
    std::lock_guard lock(m_state->m_mutex);
    m_state->m_cancelled = true;
    m_state->m_queue.clear();
  }
  m_state->m_cv.notify_all();
  // Wait for a callback in progress; afterwards none starts (they check m_cancelled under this mutex).
  std::lock_guard lock(m_state->m_callbackMutex);
}

void RasterTileLoader::SetWanted(std::vector<RasterTileKey> const & keys)
{
  {
    std::lock_guard lock(m_state->m_mutex);
    m_state->m_queue.clear();
    for (auto const & k : keys)
      if (k.IsValid() && !m_state->m_inFlight.contains(k))
        m_state->m_queue.push_back(k);
  }
  m_state->m_cv.notify_all();
}

// static
void RasterTileLoader::Work(std::shared_ptr<State> state)
{
  while (true)
  {
    RasterTileKey key;
    {
      std::unique_lock lock(state->m_mutex);
      state->m_cv.wait(lock, [&] { return state->m_cancelled || !state->m_queue.empty(); });
      if (state->m_cancelled)
        return;
      key = state->m_queue.front();
      state->m_queue.pop_front();
      if (state->m_inFlight.contains(key))
        continue;
      state->m_inFlight.insert(key);
    }

    RasterTileImage image;
    image.m_key = key;
    std::string data;
    bool found = state->m_memory.Get(key, data);
    if (!found && state->m_disk && state->m_disk->Get(key, data))
    {
      found = true;
      state->m_memory.Put(key, data);
    }
    if (!found)
    {
      auto result = state->m_fetcher(BuildTileUrl(state->m_url, key), state->m_token);
      if (result.m_httpCode == 200 && !result.m_data.empty())
      {
        data = std::move(result.m_data);
        found = true;
        state->m_memory.Put(key, data);
        if (state->m_disk)
          state->m_disk->Put(key, data);
      }
      else
      {
        image.m_httpCode = result.m_httpCode;
      }
    }
    image.m_failed = !found || !Decode(data, image);

    {
      std::lock_guard lock(state->m_mutex);
      state->m_inFlight.erase(key);
    }
    std::lock_guard callbackLock(state->m_callbackMutex);
    {
      std::lock_guard lock(state->m_mutex);
      if (state->m_cancelled)
        return;
    }
    state->m_callback(std::move(image));
  }
}

// static
RasterTileLoader::Fetcher RasterTileLoader::DefaultFetcher()
{
  return [](std::string const & url, std::string const & token)
  {
    FetchResult result;
    platform::HttpClient request(url);
    request.SetTimeout(15.0);
    request.SetRawHeader("Authorization", "Bearer " + token);
    request.SetRawHeader("Accept", "image/jpeg, image/png");
    if (!request.RunHttpRequest())
      return result;
    result.m_httpCode = request.ErrorCode();
    if (result.m_httpCode == 200)
      result.m_data = request.ServerResponse();
    return result;
  };
}

// static
bool RasterTileLoader::Decode(std::string const & data, RasterTileImage & image)
{
  if (data.empty() || data.size() > (16 << 20))
    return false;
  int w = 0, h = 0, channels = 0;
  auto const * bytes = reinterpret_cast<stbi_uc const *>(data.data());
  if (!stbi_info_from_memory(bytes, static_cast<int>(data.size()), &w, &h, &channels))
    return false;
  bool const powerOfTwo = w > 0 && (w & (w - 1)) == 0;
  if (w != h || !powerOfTwo || w > 1024)
    return false;
  stbi_uc * pixels = stbi_load_from_memory(bytes, static_cast<int>(data.size()), &w, &h, &channels, 4 /* RGBA */);
  if (pixels == nullptr)
    return false;
  image.m_width = static_cast<uint32_t>(w);
  image.m_height = static_cast<uint32_t>(h);
  image.m_rgba.assign(pixels, pixels + static_cast<size_t>(w) * h * 4);
  stbi_image_free(pixels);
  return true;
}
}  // namespace df::raster
