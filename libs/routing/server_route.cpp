#include "routing/server_route.hpp"

#include "platform/http_client.hpp"

#include "base/cancellable.hpp"
#include "base/logging.hpp"
#include "base/string_utils.hpp"
#include "base/timer.hpp"

#include <cmath>
#include <cstdio>

#include "glaze/json.hpp"

namespace routing
{
// Not in an anonymous namespace: glaze's reflection needs a type with linkage (clang).
namespace server_route_json
{
struct ResponseDto
{
  std::string geometry;
  int precision = 6;
  double distance_m = 0.0;
  double duration_s = 0.0;
  std::string data_timestamp;
};
}  // namespace server_route_json

namespace
{
// Reads one value of an encoded polyline at |pos|.
bool DecodeValue(std::string_view s, size_t & pos, int64_t & value)
{
  int64_t result = 0;
  int shift = 0;
  while (true)
  {
    if (pos >= s.size() || shift > 60)
      return false;
    int const b = static_cast<unsigned char>(s[pos++]) - 63;
    if (b < 0 || b > 63)
      return false;
    result |= static_cast<int64_t>(b & 0x1f) << shift;
    shift += 5;
    if (b < 0x20)
      break;
  }
  value = (result & 1) ? ~(result >> 1) : (result >> 1);
  return true;
}
}  // namespace

bool DecodePolyline(std::string_view encoded, int precision, std::vector<ms::LatLon> & points)
{
  points.clear();
  if (precision < 1 || precision > 7)
    return false;
  double const factor = std::pow(10.0, precision);
  int64_t lat = 0, lon = 0;
  size_t pos = 0;
  while (pos < encoded.size())
  {
    int64_t dLat, dLon;
    if (!DecodeValue(encoded, pos, dLat) || !DecodeValue(encoded, pos, dLon))
      return false;
    lat += dLat;
    lon += dLon;
    ms::LatLon const p(lat / factor, lon / factor);
    if (std::abs(p.m_lat) > 90.0 || std::abs(p.m_lon) > 180.0)
      return false;
    points.push_back(p);
  }
  return true;
}

void HttpServerRouteSource::SetServer(std::string const & url, std::string const & token)
{
  std::string u = url;
  strings::Trim(u);
  std::string t = token;
  strings::Trim(t);
  std::lock_guard lock(m_mutex);
  m_url = u.empty() ? std::string() : MakeRequestUrl(u);
  m_token = std::move(t);
}

void HttpServerRouteSource::SetTimeout(double timeoutSec)
{
  std::lock_guard lock(m_mutex);
  m_timeoutSec = timeoutSec;
}

bool HttpServerRouteSource::IsEnabled() const
{
  std::lock_guard lock(m_mutex);
  return !m_url.empty() && !m_token.empty();
}

std::string HttpServerRouteSource::MakeRequestUrl(std::string const & baseUrl)
{
  std::string url = baseUrl;
  while (!url.empty() && url.back() == '/')
    url.pop_back();
  if (!url.ends_with("/v1/route"))
    url += "/v1/route";
  return url;
}

std::string HttpServerRouteSource::MakeRequestBody(std::vector<ms::LatLon> const & points)
{
  std::string body = "{\"points\":[";
  char buf[64];
  for (size_t i = 0; i < points.size(); ++i)
  {
    // 1e-7 degrees = 1 cm, like OSM.
    std::snprintf(buf, sizeof(buf), "%s[%.7f,%.7f]", i == 0 ? "" : ",", points[i].m_lat, points[i].m_lon);
    body += buf;
  }
  body += "]}";
  return body;
}

std::optional<ServerRoute> HttpServerRouteSource::ParseResponse(std::string const & json)
{
  server_route_json::ResponseDto dto;
  if (auto const error = glz::read<glz::opts{.error_on_unknown_keys = false}>(dto, json))
  {
    LOG(LWARNING, ("Routing server: invalid response"));
    return std::nullopt;
  }
  ServerRoute route;
  if (!DecodePolyline(dto.geometry, dto.precision, route.m_polyline) || route.m_polyline.size() < 2)
  {
    LOG(LWARNING, ("Routing server: invalid geometry"));
    return std::nullopt;
  }
  if (!(dto.distance_m > 0.0) || !(dto.duration_s > 0.0) || !std::isfinite(dto.distance_m) ||
      !std::isfinite(dto.duration_s))
  {
    LOG(LWARNING, ("Routing server: invalid distance or duration"));
    return std::nullopt;
  }
  route.m_distanceM = dto.distance_m;
  route.m_durationSec = dto.duration_s;
  route.m_dataTimestamp = std::move(dto.data_timestamp);
  return route;
}

std::optional<ServerRoute> HttpServerRouteSource::GetRoute(std::vector<ms::LatLon> const & points,
                                                           base::Cancellable const & cancellable)
{
  std::string url, token;
  double timeoutSec;
  {
    std::lock_guard lock(m_mutex);
    url = m_url;
    token = m_token;
    timeoutSec = m_timeoutSec;
  }
  if (url.empty() || token.empty() || points.size() < 2 || cancellable.IsCancelled())
    return std::nullopt;

  base::Timer timer;
  platform::HttpClient request(url);
  request.SetTimeout(timeoutSec);
  request.SetRawHeader("Authorization", "Bearer " + token);
  request.SetRawHeader("Accept", "application/json");
  request.SetBodyData(MakeRequestBody(points), "application/json", "POST");
  if (!request.RunHttpRequest())
  {
    LOG(LWARNING, ("Routing server not reachable after", timer.ElapsedMilliseconds(), "ms"));
    return std::nullopt;
  }
  if (request.ErrorCode() != 200)
  {
    LOG(LWARNING, ("Routing server answered HTTP", request.ErrorCode(), "after", timer.ElapsedMilliseconds(), "ms"));
    return std::nullopt;
  }
  auto route = ParseResponse(request.ServerResponse());
  if (route)
  {
    LOG(LINFO, ("Routing server route:", route->m_polyline.size(), "points,", route->m_distanceM, "m,",
                route->m_durationSec, "s, data", route->m_dataTimestamp, "in", timer.ElapsedMilliseconds(), "ms"));
  }
  return route;
}
}  // namespace routing
