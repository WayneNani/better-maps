#pragma once

// better-maps: indoor maps. Caches of the venue loader and the choice of the venue to show, without threads or
// network (the loader in map/indoor_manager uses them on its threads).

#include "drape_frontend/indoor_data.hpp"

#include "geometry/rect2d.hpp"

#include <chrono>
#include <cstdint>
#include <deque>
#include <optional>
#include <string>
#include <vector>

namespace df::indoor
{
using Clock = std::chrono::steady_clock;

// Grid (degrees) to which venue list queries are snapped, so that small moves ask the same query again.
double constexpr kListGridDeg = 0.02;
// The server refuses larger queries.
double constexpr kMaxListQueryDeg = 0.9;

// The bbox (minLon, minLat, maxLon, maxLat in degrees, snapped outward to kListGridDeg) to ask for the venues of a
// view (mercator): the view plus half its size on each side, at most kMaxListQueryDeg wide and high around the view
// center.
struct LatLonBox
{
  bool operator==(LatLonBox const & other) const = default;
  bool Contains(LatLonBox const & other) const;

  double m_minLon = 0, m_minLat = 0, m_maxLon = 0, m_maxLat = 0;
};
LatLonBox ListQueryBox(m2::RectD const & viewMercator);
LatLonBox ToLatLonBox(m2::RectD const & mercatorRect);
// "minLon,minLat,maxLon,maxLat" with 6 decimals.
std::string ToQueryString(LatLonBox const & box);
inline std::string DebugPrint(LatLonBox const & box)
{
  return ToQueryString(box);
}

// Answers of the venue list queries for some time.
class VenueListCache
{
public:
  static size_t constexpr kMaxEntries = 16;
  static auto constexpr kMaxAge = std::chrono::hours(1);
  // A failed query is not repeated for this time.
  static auto constexpr kFailureBackoff = std::chrono::minutes(2);

  // Whether a fresh answer (or a recent failure) covers |view| (mercator).
  bool Covers(m2::RectD const & view, Clock::time_point now) const;
  void Add(LatLonBox const & box, std::vector<VenueInfo> && venues, Clock::time_point now);
  void AddFailure(LatLonBox const & box, Clock::time_point now);
  // Venues of the fresh answers intersecting |rect| (mercator), each once.
  std::vector<VenueInfo> VenuesIntersecting(m2::RectD const & rect, Clock::time_point now) const;
  void Clear() { m_entries.clear(); }

private:
  struct Entry
  {
    LatLonBox m_box;
    std::vector<VenueInfo> m_venues;
    Clock::time_point m_time;
    bool m_failed = false;
  };
  std::deque<Entry> m_entries;
};

// The venue to show for |view| (mercator) among |venues|: one containing the view center (the current one if it
// does, else the smallest), else the current one if it is still in view, else the one covering most of the view.
// nullopt if none intersects the view.
std::optional<size_t> SelectVenue(std::vector<VenueInfo> const & venues, m2::RectD const & view,
                                  std::string const & currentId);

// Venue documents on disk: <dir>/<id>.json with <id>.etag next to it, at most |maxFiles| documents and |maxBytes| in
// total (the least recently used are deleted). Not thread-safe.
class VenueDiskCache
{
public:
  VenueDiskCache(std::string dir, size_t maxFiles, uint64_t maxBytes);

  bool Get(std::string const & id, std::string & json, std::string & etag);
  void Put(std::string const & id, std::string const & json, std::string const & etag);
  // Marks the document as used (e.g. after the server answered 304).
  void Touch(std::string const & id);
  void Remove(std::string const & id);

  size_t GetCount() const;
  uint64_t GetBytes() const;

private:
  // Use order is kept in the modification times of the .json files.
  void Evict();

  std::string m_dir;
  size_t m_maxFiles;
  uint64_t m_maxBytes;
};
}  // namespace df::indoor
