#include "map/indoor_manager.hpp"

#include "drape_frontend/drape_engine.hpp"
#include "drape_frontend/visual_params.hpp"

#include "platform/http_client.hpp"
#include "platform/platform.hpp"

#include "base/logging.hpp"
#include "base/string_utils.hpp"

#include <atomic>
#include <deque>
#include <map>
#include <mutex>
#include <set>

using df::indoor::Clock;
using df::indoor::LatLonBox;
using df::indoor::VenueInfo;
using df::indoor::VenuePtr;

namespace
{
size_t constexpr kMaxVenuesInMemory = 6;
size_t constexpr kMaxVenuesOnDisk = 40;
uint64_t constexpr kMaxDiskBytes = 30 * 1024 * 1024;
// Venue list queries wait until the map stands still for a moment.
auto constexpr kListDebounce = std::chrono::milliseconds(400);
auto constexpr kVenueRetryAfterFailure = std::chrono::minutes(2);

std::string TrimServer(std::string url)
{
  strings::Trim(url);
  while (!url.empty() && url.back() == '/')
    url.pop_back();
  return url;
}
}  // namespace

struct IndoorManager::Impl : public std::enable_shared_from_this<Impl>
{
  // Settings (GUI thread).
  bool m_enabled = false;
  std::string m_url;
  std::string m_token;
  std::string m_cacheDir;
  // Changes with the server: answers for an older server are dropped.
  uint64_t m_serverGen = 0;

  Fetcher m_fetcher;
  DelayedRunner m_network;
  Runner m_gui;
  df::DrapeEngineSafePtr m_drapeEngine;
  StateListener m_listener;

  // State (GUI thread).
  ScreenBase m_screen;
  bool m_hasScreen = false;
  df::indoor::VenueListCache m_lists;
  std::optional<LatLonBox> m_pendingList;
  std::atomic<uint64_t> m_listToken{0};
  std::map<std::string, VenuePtr> m_venues;
  std::deque<std::string> m_venueLru;  // most recent first
  std::set<std::string> m_loading;
  std::map<std::string, Clock::time_point> m_failedVenues;
  std::map<std::string, double> m_chosenLevels;
  std::string m_currentId;
  VenuePtr m_shownVenue;
  double m_shownLevel = 0.0;
  State m_state;

  // Disk cache, used on the network thread only.
  std::mutex m_diskMutex;
  std::unique_ptr<df::indoor::VenueDiskCache> m_disk;
  std::string m_diskDir;

  bool IsActive() const { return m_enabled && !m_url.empty() && !m_token.empty(); }

  void Notify()
  {
    if (m_listener)
      m_listener(m_state);
  }

  void Hide()
  {
    if (m_shownVenue != nullptr)
    {
      m_shownVenue.reset();
      m_drapeEngine.SafeCall(&df::DrapeEngine::SetIndoorLevel, df::IndoorLevelParams{});
    }
    if (m_state.m_visible)
    {
      m_state = State{};
      Notify();
    }
  }

  void Show(VenuePtr const & venue, double level)
  {
    if (m_shownVenue != venue || !df::indoor::SameLevel(m_shownLevel, level))
    {
      m_shownVenue = venue;
      m_shownLevel = level;
      m_drapeEngine.SafeCall(&df::DrapeEngine::SetIndoorLevel, df::IndoorLevelParams{venue, level});
    }
    State state;
    state.m_visible = true;
    state.m_venueId = venue->m_info.m_id;
    state.m_venueName = venue->m_info.m_name;
    state.m_attribution = venue->m_attribution.empty() ? "© OpenStreetMap" : venue->m_attribution;
    state.m_levels = venue->m_info.m_levels;
    state.m_level = level;
    if (!(state == m_state))
    {
      m_state = std::move(state);
      Notify();
    }
  }

  void Evaluate()
  {
    if (!IsActive() || !m_hasScreen)
      return Hide();
    int const zoom = df::GetDrawTileScale(m_screen);
    if (zoom < df::indoor::kMinListZoom)
      return Hide();
    m2::RectD const view = m_screen.ClipRect();
    auto const now = Clock::now();
    if (!m_lists.Covers(view, now))
      ScheduleList(df::indoor::ListQueryBox(view));
    if (zoom < df::indoor::kMinDrawZoom)
      return Hide();

    auto const venues = m_lists.VenuesIntersecting(view, now);
    auto const sel = df::indoor::SelectVenue(venues, view, m_currentId);
    if (!sel)
      return Hide();
    VenueInfo const & info = venues[*sel];
    m_currentId = info.m_id;

    VenuePtr venue;
    if (auto const it = m_venues.find(info.m_id); it != m_venues.end())
      venue = it->second;
    bool const stale = venue != nullptr && !info.m_etag.empty() && venue->m_info.m_etag != info.m_etag;
    if (venue == nullptr || stale)
      Load(info);
    if (venue == nullptr)
      return Hide();

    double level = venue->m_info.m_defaultLevel;
    if (auto const it = m_chosenLevels.find(info.m_id); it != m_chosenLevels.end() && venue->HasLevel(it->second))
      level = it->second;
    Show(venue, level);
  }

  void ScheduleList(LatLonBox const & box)
  {
    if (m_pendingList && *m_pendingList == box)
      return;
    m_pendingList = box;
    uint64_t const token = ++m_listToken;
    std::weak_ptr<Impl> weak = shared_from_this();
    auto const url = VenueListUrl(m_url, box);
    auto const deviceToken = m_token;
    auto const serverGen = m_serverGen;
    auto fetcher = m_fetcher;
    auto gui = m_gui;
    m_network(kListDebounce, [weak, token, url, deviceToken, serverGen, box, fetcher, gui]()
    {
      {
        auto self = weak.lock();
        // A newer query replaced this one while waiting.
        if (self == nullptr || self->m_listToken.load() != token)
          return;
      }
      auto const result = fetcher(url, deviceToken, {});
      std::vector<VenueInfo> venues;
      bool const ok = result.m_httpCode == 200 && df::indoor::ParseVenueList(result.m_body, venues);
      if (!ok)
        LOG(LINFO, ("Indoor: venue list not available, HTTP", result.m_httpCode));
      gui([weak, serverGen, box, ok, venues = std::move(venues)]() mutable
      {
        auto self = weak.lock();
        if (self == nullptr)
          return;
        self->OnList(serverGen, box, ok, std::move(venues));
      });
    });
  }

  void OnList(uint64_t serverGen, LatLonBox const & box, bool ok, std::vector<VenueInfo> && venues)
  {
    if (serverGen != m_serverGen)
      return;
    if (m_pendingList && *m_pendingList == box)
      m_pendingList.reset();
    if (ok)
      m_lists.Add(box, std::move(venues), Clock::now());
    else
      m_lists.AddFailure(box, Clock::now());
    Evaluate();
  }

  void Load(VenueInfo const & info)
  {
    if (m_loading.contains(info.m_id))
      return;
    if (auto const it = m_failedVenues.find(info.m_id);
        it != m_failedVenues.end() && Clock::now() - it->second < kVenueRetryAfterFailure)
      return;
    m_loading.insert(info.m_id);
    std::weak_ptr<Impl> weak = shared_from_this();
    auto const url = VenueUrl(m_url, info.m_id);
    auto const deviceToken = m_token;
    auto const serverGen = m_serverGen;
    auto const cacheDir = m_cacheDir.empty() ? std::string() : m_cacheDir + "/" + CacheSubdir(m_url);
    auto fetcher = m_fetcher;
    auto gui = m_gui;
    m_network(std::chrono::milliseconds(0), [weak, info, url, deviceToken, serverGen, cacheDir, fetcher, gui]()
    {
      auto self = weak.lock();
      if (self == nullptr)
        return;
      VenuePtr venue = self->LoadOnNetworkThread(info, url, deviceToken, cacheDir, fetcher);
      self.reset();
      gui([weak, serverGen, id = info.m_id, venue]()
      {
        auto self = weak.lock();
        if (self == nullptr)
          return;
        self->OnVenue(serverGen, id, venue);
      });
    });
  }

  // A short, stable directory name per server, so that venues of different servers do not mix.
  static std::string CacheSubdir(std::string const & server)
  {
    uint64_t h = 1469598103934665603ULL;
    for (unsigned char c : server)
      h = (h ^ c) * 1099511628211ULL;
    return "v1_" + strings::to_string(h % 1000000007ULL);
  }

  VenuePtr LoadOnNetworkThread(VenueInfo const & info, std::string const & url, std::string const & deviceToken,
                               std::string const & cacheDir, Fetcher const & fetcher)
  {
    std::lock_guard lock(m_diskMutex);
    if (cacheDir != m_diskDir)
    {
      m_disk = cacheDir.empty()
                 ? nullptr
                 : std::make_unique<df::indoor::VenueDiskCache>(cacheDir, kMaxVenuesOnDisk, kMaxDiskBytes);
      m_diskDir = cacheDir;
    }
    std::string cachedJson, cachedEtag;
    bool const cached = m_disk != nullptr && m_disk->Get(info.m_id, cachedJson, cachedEtag);

    auto parse = [&info](std::string const & json, std::string const & etag) -> VenuePtr
    {
      auto venue = std::make_shared<df::indoor::Venue>();
      if (!df::indoor::ParseVenue(json, *venue) || venue->m_info.m_id != info.m_id)
        return nullptr;
      venue->m_info.m_etag = etag;
      return venue;
    };

    if (cached && !info.m_etag.empty() && cachedEtag == info.m_etag)
      if (auto venue = parse(cachedJson, cachedEtag))
        return venue;

    auto const result = fetcher(url, deviceToken, cached ? cachedEtag : std::string());
    if (result.m_httpCode == 304 && cached)
    {
      m_disk->Touch(info.m_id);
      return parse(cachedJson, cachedEtag);
    }
    if (result.m_httpCode == 200)
    {
      auto venue = parse(result.m_body, result.m_etag);
      if (venue != nullptr && m_disk != nullptr)
        m_disk->Put(info.m_id, result.m_body, result.m_etag);
      if (venue == nullptr && m_disk != nullptr)
        m_disk->Remove(info.m_id);
      return venue;
    }
    LOG(LINFO, ("Indoor: venue not available, HTTP", result.m_httpCode));
    // Without an answer the cached copy is better than nothing.
    if (cached && result.m_httpCode < 0)
      return parse(cachedJson, cachedEtag);
    return nullptr;
  }

  void OnVenue(uint64_t serverGen, std::string const & id, VenuePtr const & venue)
  {
    m_loading.erase(id);
    if (serverGen != m_serverGen)
      return;
    if (venue == nullptr)
    {
      m_failedVenues[id] = Clock::now();
      return;
    }
    m_failedVenues.erase(id);
    m_venues[id] = venue;
    std::erase(m_venueLru, id);
    m_venueLru.push_front(id);
    while (m_venueLru.size() > kMaxVenuesInMemory)
    {
      // Never drop the shown venue.
      auto const & last = m_venueLru.back();
      if (m_shownVenue != nullptr && last == m_shownVenue->m_info.m_id)
        break;
      m_venues.erase(last);
      m_venueLru.pop_back();
    }
    Evaluate();
  }

  void ResetData()
  {
    ++m_serverGen;
    ++m_listToken;
    m_lists.Clear();
    m_pendingList.reset();
    m_venues.clear();
    m_venueLru.clear();
    m_loading.clear();
    m_failedVenues.clear();
    m_currentId.clear();
  }
};

IndoorManager::IndoorManager() : m_impl(std::make_shared<Impl>())
{
  SetBackends(DefaultFetcher(), [](std::chrono::milliseconds delay, std::function<void()> && task)
  {
    if (delay.count() > 0)
      GetPlatform().RunDelayedTask(Platform::Thread::Network, delay, std::move(task));
    else
      GetPlatform().RunTask(Platform::Thread::Network, std::move(task));
  }, [](std::function<void()> && task) { GetPlatform().RunTask(Platform::Thread::Gui, std::move(task)); });
}

IndoorManager::~IndoorManager()
{
  Teardown();
}

void IndoorManager::SetBackends(Fetcher fetcher, DelayedRunner networkRunner, Runner guiRunner)
{
  m_impl->m_fetcher = std::move(fetcher);
  m_impl->m_network = std::move(networkRunner);
  m_impl->m_gui = std::move(guiRunner);
}

void IndoorManager::SetDrapeEngine(ref_ptr<df::DrapeEngine> engine)
{
  m_impl->m_drapeEngine.Set(engine);
  // A new engine starts empty: show the current level again.
  if (auto const venue = m_impl->m_shownVenue)
    m_impl->m_drapeEngine.SafeCall(&df::DrapeEngine::SetIndoorLevel,
                                   df::IndoorLevelParams{venue, m_impl->m_shownLevel});
}

void IndoorManager::SetServer(bool enabled, std::string const & url, std::string const & token,
                              std::string const & cacheDir)
{
  auto const server = TrimServer(url);
  auto & impl = *m_impl;
  if (impl.m_enabled == enabled && impl.m_url == server && impl.m_token == token && impl.m_cacheDir == cacheDir)
    return;
  bool const serverChanged = impl.m_url != server || impl.m_token != token;
  impl.m_enabled = enabled;
  impl.m_url = server;
  impl.m_token = token;
  impl.m_cacheDir = cacheDir;
  if (serverChanged || !enabled)
    impl.ResetData();
  // Neither the address nor the token is logged.
  LOG(LINFO, ("Indoor maps", impl.IsActive() ? "on" : "off"));
  impl.Evaluate();
}

bool IndoorManager::IsEnabled() const
{
  return m_impl->IsActive();
}

void IndoorManager::SetStateListener(StateListener listener)
{
  m_impl->m_listener = std::move(listener);
}

void IndoorManager::UpdateViewport(ScreenBase const & screen)
{
  m_impl->m_screen = screen;
  m_impl->m_hasScreen = true;
  m_impl->Evaluate();
}

void IndoorManager::SetLevel(double level)
{
  auto & impl = *m_impl;
  if (impl.m_shownVenue == nullptr || !impl.m_shownVenue->HasLevel(level))
    return;
  impl.m_chosenLevels[impl.m_shownVenue->m_info.m_id] = level;
  impl.Show(impl.m_shownVenue, level);
}

IndoorManager::State const & IndoorManager::GetState() const
{
  return m_impl->m_state;
}

std::optional<IndoorManager::FeatureInfo> IndoorManager::HitTest(m2::PointD const & mercator, double pointRadius,
                                                                 bool withAreas) const
{
  auto const & impl = *m_impl;
  if (impl.m_shownVenue == nullptr || !impl.m_state.m_visible || !impl.m_hasScreen ||
      df::GetDrawTileScale(impl.m_screen) < df::indoor::kMinDrawZoom)
    return {};
  auto const & venue = *impl.m_shownVenue;
  auto const hit = df::indoor::HitTest(venue, impl.m_shownLevel, mercator, pointRadius, withAreas);
  if (!hit)
    return {};
  auto const & f = venue.m_features[hit->m_featureIndex];
  FeatureInfo info;
  info.m_name = f.m_name;
  info.m_ref = f.m_ref;
  info.m_kind = std::string(df::indoor::ToString(f.m_kind));
  info.m_levelLabel = venue.LevelLabel(impl.m_shownLevel);
  info.m_venueName = venue.m_info.m_name;
  info.m_osmId = f.m_id;
  info.m_tags = f.m_tags;
  info.m_point =
      f.m_type == df::indoor::GeomType::Point ? f.m_point : df::indoor::LabelPoint(f).value_or(f.m_rect.Center());
  return info;
}

void IndoorManager::Teardown()
{
  auto & impl = *m_impl;
  impl.ResetData();
  impl.m_enabled = false;
  impl.Hide();
  impl.m_drapeEngine.Set(nullptr);
}

// static
std::string IndoorManager::VenueListUrl(std::string const & server, LatLonBox const & box)
{
  return TrimServer(server) + "/v1/venues?bbox=" + df::indoor::ToQueryString(box);
}

// static
std::string IndoorManager::VenueUrl(std::string const & server, std::string const & id)
{
  return TrimServer(server) + "/v1/venues/" + id;
}

// static
IndoorManager::Fetcher IndoorManager::DefaultFetcher()
{
  return [](std::string const & url, std::string const & token, std::string const & etag)
  {
    FetchResult result;
    platform::HttpClient request(url);
    request.SetTimeout(15.0);
    request.SetRawHeader("Authorization", "Bearer " + token);
    request.SetRawHeader("Accept", "application/json");
    if (!etag.empty())
      request.SetRawHeader("If-None-Match", etag);
    request.LoadHeaders(true);
    if (!request.RunHttpRequest())
      return result;
    result.m_httpCode = request.ErrorCode();
    if (result.m_httpCode == 200)
      result.m_body = request.ServerResponse();
    for (auto const & [key, value] : request.GetHeaders())
    {
      if (strings::EqualNoCase(key, "etag"))
      {
        result.m_etag = value;
        break;
      }
    }
    return result;
  };
}
