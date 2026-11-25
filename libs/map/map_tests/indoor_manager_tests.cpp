#include "testing/testing.hpp"

#include "map/indoor_manager.hpp"

#include "drape_frontend/visual_params.hpp"

#include "geometry/any_rect2d.hpp"
#include "geometry/mercator.hpp"

#include <filesystem>
#include <functional>
#include <random>
#include <string>
#include <vector>

namespace indoor_manager_tests
{
namespace fs = std::filesystem;

// Google polyline encoding (precision 6, lat first).
void EncodeValue(int64_t v, std::string & out)
{
  uint64_t u = v < 0 ? ~(static_cast<uint64_t>(v) << 1) : (static_cast<uint64_t>(v) << 1);
  while (u >= 0x20)
  {
    out.push_back(static_cast<char>((0x20 | (u & 0x1f)) + 63));
    u >>= 5;
  }
  out.push_back(static_cast<char>(u + 63));
}

std::string Encode(std::vector<std::pair<double, double>> const & latLons)
{
  std::string out;
  int64_t plat = 0, plon = 0;
  for (auto const & [lat, lon] : latLons)
  {
    int64_t const la = std::llround(lat * 1e6), lo = std::llround(lon * 1e6);
    EncodeValue(la - plat, out);
    EncodeValue(lo - plon, out);
    plat = la;
    plon = lo;
  }
  return out;
}

double constexpr kLat = 50.0500, kLon = 8.5700, kD = 0.0010;

std::string ListJson()
{
  return R"({"venues": [{"id": "w1", "name": "Terminal", "kind": "airport", "bbox": [8.57, 50.05, 8.571, 50.051],
    "levels": [{"level": 0, "label": "0"}, {"level": 1, "label": "1"}], "default_level": 0, "etag": "\"e1\""}]})";
}

std::string VenueJson()
{
  std::string const room =
      Encode({{kLat, kLon}, {kLat, kLon + kD / 2}, {kLat + kD / 2, kLon + kD / 2}, {kLat + kD / 2, kLon}});
  std::string const gate = Encode({{kLat + kD * 0.75, kLon + kD * 0.75}});
  return R"({"id": "w1", "name": "Terminal", "kind": "airport", "bbox": [8.57, 50.05, 8.571, 50.051],
    "levels": [{"level": 0, "label": "0"}, {"level": 1, "label": "1"}], "default_level": 0,
    "attribution": "© OpenStreetMap-Mitwirkende", "features": [
      {"id": "w2", "t": "a", "k": "shop", "n": "Kiosk", "lv": [0], "g": [[")" +
         room + R"("]], "tg": {"shop": "kiosk"}},
      {"id": "n3", "t": "p", "k": "gate", "r": "B44", "lv": [1], "g": ")" +
         gate + R"("}
    ]})";
}

class Env
{
public:
  Env()
  {
    std::random_device rd;
    m_dir = fs::temp_directory_path() / ("bm_indoor_mgr_" + std::to_string(rd()));
    m_manager.SetBackends(
        [this](std::string const & url, std::string const & token, std::string const & etag)
    {
      m_requests.push_back(url);
      m_tokens.push_back(token);
      m_etags.push_back(etag);
      IndoorManager::FetchResult r;
      if (m_offline)
        return r;
      if (url.find("/v1/venues?bbox=") != std::string::npos)
      {
        r.m_httpCode = 200;
        r.m_body = ListJson();
      }
      else if (url.ends_with("/v1/venues/w1"))
      {
        if (!etag.empty() && etag == m_venueEtag)
        {
          r.m_httpCode = 304;
          return r;
        }
        r.m_httpCode = m_venueCode;
        r.m_body = VenueJson();
        r.m_etag = m_venueEtag;
      }
      else
      {
        r.m_httpCode = 404;
      }
      return r;
    }, [](std::chrono::milliseconds, std::function<void()> && task) { task(); },
        [](std::function<void()> && task) { task(); });
    m_manager.SetStateListener([this](IndoorManager::State const & s) { m_states.push_back(s); });
  }

  ~Env()
  {
    m_manager.Teardown();
    std::error_code ec;
    fs::remove_all(m_dir, ec);
  }

  // A phone-sized view of |widthMeters| around the venue.
  void View(double widthMeters, double lat = kLat + kD / 4, double lon = kLon + kD / 4)
  {
    ScreenBase screen;
    screen.OnSize(0, 0, 1080, 1920);
    auto const rect = mercator::RectByCenterXYAndSizeInMeters(mercator::FromLatLon(lat, lon), widthMeters / 2);
    screen.SetFromRect(m2::AnyRectD(rect));
    m_zoom = df::GetDrawTileScale(screen);
    m_manager.UpdateViewport(screen);
  }

  IndoorManager m_manager;
  fs::path m_dir;
  std::vector<std::string> m_requests, m_tokens, m_etags;
  std::vector<IndoorManager::State> m_states;
  std::string m_venueEtag = "\"e1\"";
  int m_venueCode = 200;
  bool m_offline = false;
  int m_zoom = 0;
};

UNIT_TEST(IndoorManager_Urls)
{
  df::indoor::LatLonBox const box{8.56, 50.04, 8.58, 50.06};
  TEST_EQUAL(IndoorManager::VenueListUrl("https://indoor.example.org/ ", box),
             "https://indoor.example.org/v1/venues?bbox=8.560000,50.040000,8.580000,50.060000", ());
  TEST_EQUAL(IndoorManager::VenueUrl("http://10.0.2.2:18300//", "w1"), "http://10.0.2.2:18300/v1/venues/w1", ());
}

UNIT_TEST(IndoorManager_ShowsVenueAndLevels)
{
  Env env;
  env.View(250);
  TEST(env.m_requests.empty(), ("off by default"));

  env.m_manager.SetServer(true, "http://10.0.2.2:18300", "token-0123456789abcdefghij", env.m_dir.string());
  TEST_GREATER_OR_EQUAL(env.m_zoom, df::indoor::kMinDrawZoom, ());
  TEST_EQUAL(env.m_requests.size(), 2, (env.m_requests));
  TEST(env.m_requests[0].starts_with("http://10.0.2.2:18300/v1/venues?bbox="), ());
  TEST_EQUAL(env.m_requests[1], "http://10.0.2.2:18300/v1/venues/w1", ());
  TEST_EQUAL(env.m_tokens[1], "token-0123456789abcdefghij", ());

  auto const & state = env.m_manager.GetState();
  TEST(state.m_visible, ());
  TEST_EQUAL(state.m_venueName, "Terminal", ());
  TEST_EQUAL(state.m_levels.size(), 2, ());
  TEST_EQUAL(state.m_level, 0.0, ());
  TEST_EQUAL(state.m_attribution, "© OpenStreetMap-Mitwirkende", ());

  // Small moves need no new requests.
  env.View(260);
  TEST_EQUAL(env.m_requests.size(), 2, ());

  // Tap the shop on level 0; the gate is on level 1 only.
  auto const shopPt = mercator::FromLatLon(kLat + kD / 4, kLon + kD / 4);
  auto hit = env.m_manager.HitTest(shopPt, 1e-6, true);
  TEST(hit, ());
  TEST_EQUAL(hit->m_name, "Kiosk", ());
  TEST_EQUAL(hit->m_kind, "shop", ());
  TEST_EQUAL(hit->m_levelLabel, "0", ());
  auto const gatePt = mercator::FromLatLon(kLat + kD * 0.75, kLon + kD * 0.75);
  TEST(!env.m_manager.HitTest(gatePt, 1e-5, true), ());

  env.m_manager.SetLevel(1);
  TEST_EQUAL(env.m_manager.GetState().m_level, 1.0, ());
  hit = env.m_manager.HitTest(gatePt, 1e-5, true);
  TEST(hit, ());
  TEST_EQUAL(hit->m_ref, "B44", ());
  env.m_manager.SetLevel(7);
  TEST_EQUAL(env.m_manager.GetState().m_level, 1.0, ("unknown level ignored"));

  // Zoomed out: hidden, the chosen level is remembered.
  env.View(5000);
  TEST(!env.m_manager.GetState().m_visible, ());
  TEST(!env.m_manager.HitTest(gatePt, 1e-5, true), ());
  env.View(250);
  TEST(env.m_manager.GetState().m_visible, ());
  TEST_EQUAL(env.m_manager.GetState().m_level, 1.0, ());

  // Switched off: hidden.
  env.m_manager.SetServer(false, "http://10.0.2.2:18300", "token-0123456789abcdefghij", env.m_dir.string());
  TEST(!env.m_manager.GetState().m_visible, ());
  TEST(!env.m_states.back().m_visible, ());
}

UNIT_TEST(IndoorManager_DiskCacheAndEtag)
{
  std::string const token = "token-0123456789abcdefghij";
  fs::path dir;
  {
    Env env;
    dir = env.m_dir;
    env.m_manager.SetServer(true, "http://localhost:18300", token, env.m_dir.string());
    env.View(250);
    TEST(env.m_manager.GetState().m_visible, ());
    // Keep the directory for the next manager.
    env.m_dir = fs::temp_directory_path() / "bm_indoor_mgr_none";
  }
  {
    // Same ETag in the list as on disk: no venue request at all.
    Env env;
    env.m_dir = dir;
    env.m_manager.SetServer(true, "http://localhost:18300", token, dir.string());
    env.View(250);
    TEST(env.m_manager.GetState().m_visible, ());
    TEST_EQUAL(env.m_requests.size(), 1, (env.m_requests));
  }
}

UNIT_TEST(IndoorManager_FailuresShowTheNormalMap)
{
  Env env;
  env.m_offline = true;
  env.m_manager.SetServer(true, "http://localhost:18300", "token-0123456789abcdefghij", env.m_dir.string());
  env.View(250);
  TEST(!env.m_manager.GetState().m_visible, ());
  TEST_EQUAL(env.m_requests.size(), 1, ());
  // The failed list query is not repeated at once.
  env.View(255);
  TEST_EQUAL(env.m_requests.size(), 1, ());

  Env env2;
  env2.m_venueCode = 500;
  env2.m_manager.SetServer(true, "http://localhost:18300", "token-0123456789abcdefghij", env2.m_dir.string());
  env2.View(250);
  TEST(!env2.m_manager.GetState().m_visible, ());
  TEST_EQUAL(env2.m_requests.size(), 2, ());
  // A failed venue is not asked again at once.
  env2.View(255);
  TEST_EQUAL(env2.m_requests.size(), 2, ());

  // Another server: everything starts again.
  env2.m_venueCode = 200;
  env2.m_manager.SetServer(true, "http://localhost:18301", "token-0123456789abcdefghij", env2.m_dir.string());
  TEST(env2.m_manager.GetState().m_visible, ());
}
}  // namespace indoor_manager_tests
