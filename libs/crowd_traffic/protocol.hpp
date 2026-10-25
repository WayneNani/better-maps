#pragma once

#include "crowd_traffic/tiles.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

// Wire format of crowd-traffic reports, version 1 (docs/CROWD_TRAFFIC_PRIVACY.md, sections 4 and
// 13). Port of services/crowd-traffic/internal/protocol and the request encoding of
// internal/client/sender.go; the encodings are checked byte by byte against the Go code.
namespace crowd_traffic
{
int constexpr kProtocolVersion = 1;
// Aggregation window in seconds; windows start at multiples of it (Unix time, UTC).
int64_t constexpr kWindowSeconds = 900;
// Maximum number of reports per bundle (and per client and window).
size_t constexpr kMaxReportsPerBundle = 12;
int constexpr kNumSpeedBuckets = 7;
double constexpr kEpsilon = 3.0;
// Size to which the binary HTTP request is padded.
size_t constexpr kPadTo = 4096;
// Limit of the JSON body accepted by the server.
size_t constexpr kMaxBodyBytes = 8192;
char constexpr kReportPath[] = "/v1/reports";
char constexpr kContentType[] = "application/json";
// Authority of the target resource inside the encapsulated request (informational only).
char constexpr kDefaultAuthority[] = "crowd.bettermaps.invalid";

// Lower bucket boundaries in km/h: bucket i covers [kSpeedBucketEdges[i], kSpeedBucketEdges[i + 1]).
std::array<double, kNumSpeedBuckets> constexpr kSpeedBucketEdges = {0, 10, 20, 35, 55, 80, 110};

// Speed bucket (0 ... 6) of a speed in km/h.
int BucketOf(double kmh);

// Reportable road classes (OSM highway=*, never *_link). None marks everything else: links,
// residential and smaller roads, ferries, stretches that could not be map-matched.
enum class RoadClass : uint8_t
{
  None = 0,
  Motorway,
  Trunk,
  Primary,
  Secondary,
  Tertiary
};

bool IsReportable(RoadClass cls);
// TraFF name, e.g. "MOTORWAY" (empty for RoadClass::None).
char const * RoadClassName(RoadClass cls);

// A crossing point on a tile edge.
struct Edge
{
  Side m_side = Side::None;
  int m_offset = 0;

  bool operator==(Edge const & rhs) const = default;
};

// Segment key: a movement through a tile from one edge to another on a road of a given class.
struct SegmentKey
{
  Tile m_tile;
  Side m_entry = Side::None;
  Side m_exit = Side::None;
  RoadClass m_class = RoadClass::None;

  bool operator==(SegmentKey const & rhs) const = default;
  auto operator<=>(SegmentKey const & rhs) const = default;
};

std::string DebugPrint(SegmentKey const & key);

struct Report
{
  // Start of the window in which the vehicle left the tile, Unix seconds.
  int64_t m_window = 0;
  Tile m_tile;
  Edge m_entry;
  Edge m_exit;
  RoadClass m_class = RoadClass::None;
  // Noisy speed bucket (randomized response).
  int m_speedBucket = 0;

  SegmentKey Key() const { return {m_tile, m_entry.m_side, m_exit.m_side, m_class}; }
  bool operator==(Report const & rhs) const = default;
};

// Start of the window containing the Unix time t (seconds).
int64_t WindowStart(double t, int64_t windowSeconds = kWindowSeconds);

// JSON body of a bundle, identical to Go's json.Marshal of protocol.Bundle.
std::string EncodeBundleJson(std::vector<Report> const & reports);

// IMF-fixdate (RFC 9110), e.g. "Mon, 26 Oct 2026 16:01:30 GMT".
std::string FormatHttpDate(int64_t unixSeconds);

// Binary HTTP request (RFC 9292, known length) for a bundle: POST /v1/reports with Date and
// Content-Type, padded with zeros to kPadTo bytes. Returns an empty vector if the bundle is too
// large (cannot happen with at most kMaxReportsPerBundle reports).
std::vector<uint8_t> EncodeRequest(std::vector<Report> const & reports, int64_t nowUnixSeconds,
                                   std::string const & authority = kDefaultAuthority);

// QUIC variable-length integer (RFC 9000, section 16), appended to out.
void AppendVarint(std::vector<uint8_t> & out, uint64_t v);
}  // namespace crowd_traffic
