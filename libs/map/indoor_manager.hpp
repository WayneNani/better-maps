#pragma once

// better-maps: indoor maps of large venues from the own indoor server (services/indoor-server).
//
// While the map shows zoom >= 15 and the server is set up, the venues around the view are asked from
// GET /v1/venues?bbox= (snapped and cached, debounced); from zoom 16 the venue in view is loaded
// (GET /v1/venues/{id}, memory and disk cache with ETag) and one of its levels is handed to the drape engine. The UI
// gets the venue's levels for a level switcher and can pick a level. All failures silently show the normal map.
//
// All public methods are called on the GUI thread; requests run on the network thread. Neither the server address
// nor the token nor any coordinate is logged.

#include "drape_frontend/drape_engine_safe_ptr.hpp"
#include "drape_frontend/indoor_cache.hpp"
#include "drape_frontend/indoor_data.hpp"

#include "geometry/point2d.hpp"
#include "geometry/screenbase.hpp"

#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

class IndoorManager
{
public:
  // What the UI shows: the level switcher and the attribution while an indoor map is drawn.
  struct State
  {
    bool operator==(State const & other) const = default;

    bool m_visible = false;
    std::string m_venueId;
    std::string m_venueName;
    std::string m_attribution;
    std::vector<df::indoor::LevelInfo> m_levels;
    double m_level = 0.0;
  };
  using StateListener = std::function<void(State const &)>;

  // A tapped room or POI.
  struct FeatureInfo
  {
    std::string m_name;
    std::string m_ref;
    std::string m_kind;  // df::indoor::ToString(Kind)
    std::string m_levelLabel;
    std::string m_venueName;
    std::string m_osmId;
    std::vector<std::pair<std::string, std::string>> m_tags;
    m2::PointD m_point;
  };

  struct FetchResult
  {
    int m_httpCode = -1;  // -1: no answer
    std::string m_body;
    std::string m_etag;
  };
  // GET |url| with the Bearer |token| and If-None-Match |etag| (if not empty). Runs on the network thread.
  using Fetcher =
      std::function<FetchResult(std::string const & url, std::string const & token, std::string const & etag)>;
  // Runs a task on the network thread after a delay / on the GUI thread (replaceable for tests).
  using DelayedRunner = std::function<void(std::chrono::milliseconds delay, std::function<void()> && task)>;
  using Runner = std::function<void(std::function<void()> && task)>;

  IndoorManager();
  ~IndoorManager();

  // For tests: synchronous fetcher and runners.
  void SetBackends(Fetcher fetcher, DelayedRunner networkRunner, Runner guiRunner);

  void SetDrapeEngine(ref_ptr<df::DrapeEngine> engine);
  // |cacheDir|: directory for the venue documents (empty: no disk cache).
  void SetServer(bool enabled, std::string const & url, std::string const & token, std::string const & cacheDir);
  bool IsEnabled() const;
  void SetStateListener(StateListener listener);

  void UpdateViewport(ScreenBase const & screen);
  // Shows |level| of the current venue (ignored if the venue has no such level).
  void SetLevel(double level);
  State const & GetState() const;

  // The indoor feature at |mercator| if an indoor map is shown: a POI within |pointRadius| (mercator), else (if
  // |withAreas|) a room/shop/... containing the point.
  std::optional<FeatureInfo> HitTest(m2::PointD const & mercator, double pointRadius, bool withAreas) const;

  void Teardown();

  static std::string VenueListUrl(std::string const & server, df::indoor::LatLonBox const & box);
  static std::string VenueUrl(std::string const & server, std::string const & id);
  static Fetcher DefaultFetcher();

private:
  struct Impl;
  std::shared_ptr<Impl> m_impl;
};
