#pragma once

#include "geometry/latlon.hpp"

#include <cstdint>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace base
{
class Cancellable;
}

namespace routing
{
// A route of a routing server (services/route-server: OSRM behind an authenticating gateway). The router uses its
// geometry only to choose the way across the mwms; the route itself is always built locally.
struct ServerRoute
{
  std::vector<ms::LatLon> m_polyline;
  double m_distanceM = 0.0;
  double m_durationSec = 0.0;
  // Timestamp of the OSM data of the server, may be empty.
  std::string m_dataTimestamp;
};

// Where the router gets server routes from. Implementations must be thread-safe: the routers of the app and of the
// charging planner use one source on their own threads, and the GUI thread changes its settings.
class ServerRouteSource
{
public:
  virtual ~ServerRouteSource() = default;

  // False if no server is configured: the router doesn't ask and routes exactly as without a source.
  virtual bool IsEnabled() const = 0;

  // The car route through |points| (start, intermediate points, finish). nullopt on any failure: not configured,
  // unreachable, timeout, HTTP error, bad response. Blocks for at most about the timeout of the source.
  virtual std::optional<ServerRoute> GetRoute(std::vector<ms::LatLon> const & points,
                                              base::Cancellable const & cancellable) = 0;
};

// Decodes an encoded polyline (Google polyline algorithm) with |precision| decimal digits (5 or 6).
// \returns false for malformed input or coordinates out of range.
bool DecodePolyline(std::string_view encoded, int precision, std::vector<ms::LatLon> & points);

// The routing server of services/route-server over HTTPS (platform::HttpClient): POST <url>/v1/route with a Bearer
// token. Never logs the token or coordinates.
class HttpServerRouteSource : public ServerRouteSource
{
public:
  // A route request gives up after about this long (on Android the connect and read timeouts each).
  static double constexpr kDefaultTimeoutSec = 2.0;

  HttpServerRouteSource() = default;

  // |url|: base URL of the server (e.g. https://route.example.org), "/v1/route" is appended unless it is there.
  // An empty |url| or |token| disables the source.
  void SetServer(std::string const & url, std::string const & token);
  void SetTimeout(double timeoutSec);

  // ServerRouteSource overrides:
  bool IsEnabled() const override;
  std::optional<ServerRoute> GetRoute(std::vector<ms::LatLon> const & points,
                                      base::Cancellable const & cancellable) override;

  // Exposed for tests.
  static std::string MakeRequestUrl(std::string const & baseUrl);
  static std::string MakeRequestBody(std::vector<ms::LatLon> const & points);
  static std::optional<ServerRoute> ParseResponse(std::string const & json);

private:
  mutable std::mutex m_mutex;
  std::string m_url;
  std::string m_token;
  double m_timeoutSec = kDefaultTimeoutSec;
};
}  // namespace routing
