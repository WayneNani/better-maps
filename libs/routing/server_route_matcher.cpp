#include "routing/server_route_matcher.hpp"

#include "geometry/mercator.hpp"
#include "geometry/parametrized_segment.hpp"

#include "base/assert.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <optional>

namespace routing
{
namespace
{
// The projection of the ends of a transition looks this far beyond the search window: a transition segment can be
// long (a straight motorway section).
double constexpr kProjectionMarginM = 3000.0;

struct Border
{
  size_t m_segIdx = 0;  // The polyline crosses the border between vertex m_segIdx and m_segIdx + 1.
  NumMwmId m_from = kFakeNumMwmId;
  NumMwmId m_to = kFakeNumMwmId;
};

class PolylineMatcher
{
public:
  PolylineMatcher(std::vector<m2::PointD> const & polyline, ServerRouteMatcher::Graph & graph)
    : m_polyline(polyline)
    , m_graph(graph)
  {
    m_cum.resize(m_polyline.size(), 0.0);
    for (size_t i = 1; i < m_polyline.size(); ++i)
      m_cum[i] = m_cum[i - 1] + mercator::DistanceOnEarth(m_polyline[i - 1], m_polyline[i]);
    m_mwms.resize(m_polyline.size());
  }

  NumMwmId RawMwmAt(size_t i)
  {
    if (!m_mwms[i])
      m_mwms[i] = m_graph.GetMwmId(m_polyline[i]);
    return *m_mwms[i];
  }

  // The mwm of vertex i; in a gap between the (simplified) borders the one of the closest vertex before (after for
  // the first ones). kUnknownMwmId if the whole polyline is in gaps.
  NumMwmId MwmAt(size_t i)
  {
    for (size_t k = i + 1; k-- > 0;)
      if (RawMwmAt(k) != ServerRouteMatcher::kUnknownMwmId)
        return RawMwmAt(k);
    for (size_t k = i + 1; k < m_polyline.size(); ++k)
      if (RawMwmAt(k) != ServerRouteMatcher::kUnknownMwmId)
        return RawMwmAt(k);
    return ServerRouteMatcher::kUnknownMwmId;
  }

  // Finds the borders along the polyline. False if a point is outside of the maps.
  bool FindBorders(std::vector<Border> & borders)
  {
    size_t const n = m_polyline.size();
    if (MwmAt(0) == kFakeNumMwmId || MwmAt(0) == ServerRouteMatcher::kUnknownMwmId)
      return false;
    size_t last = 0;
    while (last + 1 < n)
    {
      size_t next = last + 1;
      while (next + 1 < n && m_cum[next] - m_cum[last] < ServerRouteMatcher::kSampleM)
        ++next;
      if (MwmAt(next) == kFakeNumMwmId)
        return false;
      if (MwmAt(next) != MwmAt(last))
      {
        for (size_t k = last + 1; k <= next; ++k)
        {
          if (MwmAt(k) == kFakeNumMwmId)
            return false;
          if (MwmAt(k) != MwmAt(k - 1))
            borders.push_back({k - 1, MwmAt(k - 1), MwmAt(k)});
        }
      }
      last = next;
    }
    return true;
  }

  double BorderPosition(Border const & b) const { return (m_cum[b.m_segIdx] + m_cum[b.m_segIdx + 1]) / 2.0; }

  std::optional<ServerRouteMatcher::Crossing> MatchBorder(Border const & border)
  {
    double const pos = BorderPosition(border);
    double const kWindow = ServerRouteMatcher::kSearchWindowM;
    double const kMaxDist = ServerRouteMatcher::kMaxDistanceM;

    std::vector<m2::RectD> rects;
    ForEachSegmentIn(pos - kWindow, pos + kWindow, [&](size_t s)
    {
      m2::RectD rect = mercator::RectByCenterXYAndSizeInMeters(m_polyline[s], kMaxDist);
      rect.Add(mercator::RectByCenterXYAndSizeInMeters(m_polyline[s + 1], kMaxDist));
      rects.push_back(rect);
    });

    double const projBeg = pos - kWindow - kProjectionMarginM;
    double const projEnd = pos + kWindow + kProjectionMarginM;

    std::optional<ServerRouteMatcher::Crossing> best;
    double bestScore = std::numeric_limits<double>::max();
    std::vector<Segment> twins;
    m_graph.ForEachExit(border.m_from, rects, [&](Segment const & exit)
    {
      m2::PointD const back = m_graph.GetPoint(exit, false /* front */);
      m2::PointD const front = m_graph.GetPoint(exit, true /* front */);
      auto const [backDist, backPos] = Project(back, projBeg, projEnd);
      if (backDist > kMaxDist)
        return;
      auto const [frontDist, frontPos] = Project(front, projBeg, projEnd);
      if (frontDist > kMaxDist)
        return;
      // The direction of travel: the segment must go forward along the polyline.
      double const along = frontPos - backPos;
      double const length = mercator::DistanceOnEarth(back, front);
      if (along <= 0.0 || along < 0.5 * length - 1.0)
        return;

      twins.clear();
      m_graph.GetTwins(exit, twins);
      auto const it = std::find_if(twins.begin(), twins.end(),
                                   [&](Segment const & t) { return t.GetMwmId() == border.m_to; });
      if (it == twins.end())
        return;

      double const score = backDist + frontDist + 1e-3 * std::abs((backPos + frontPos) / 2.0 - pos);
      if (score < bestScore)
      {
        bestScore = score;
        best = ServerRouteMatcher::Crossing{exit, *it, (backPos + frontPos) / 2.0};
      }
    });
    return best;
  }

private:
  // Calls fn(s) for the polyline segments s (vertex s to s + 1) that overlap [from, to] (meters along).
  template <class Fn>
  void ForEachSegmentIn(double from, double to, Fn && fn) const
  {
    auto it = std::lower_bound(m_cum.begin(), m_cum.end(), from);
    size_t s = it == m_cum.begin() ? 0 : static_cast<size_t>(it - m_cum.begin()) - 1;
    for (; s + 1 < m_polyline.size() && m_cum[s] <= to; ++s)
      fn(s);
  }

  // Distance to the polyline between |from| and |to| (meters along) and the position of the closest point.
  std::pair<double, double> Project(m2::PointD const & p, double from, double to) const
  {
    double bestDist = std::numeric_limits<double>::max();
    double bestPos = 0.0;
    ForEachSegmentIn(from, to, [&](size_t s)
    {
      m2::ParametrizedSegment<m2::PointD> const seg(m_polyline[s], m_polyline[s + 1]);
      m2::PointD const q = seg.ClosestPointTo(p);
      double const d = mercator::DistanceOnEarth(p, q);
      if (d < bestDist)
      {
        bestDist = d;
        bestPos = m_cum[s] + mercator::DistanceOnEarth(m_polyline[s], q);
      }
    });
    return {bestDist, bestPos};
  }

  std::vector<m2::PointD> const & m_polyline;
  ServerRouteMatcher::Graph & m_graph;
  std::vector<double> m_cum;
  std::vector<std::optional<NumMwmId>> m_mwms;
};
}  // namespace

// static
ServerRouteMatcher::Result ServerRouteMatcher::Match(std::vector<m2::PointD> const & polyline, Graph & graph,
                                                     std::vector<Crossing> & crossings)
{
  crossings.clear();
  if (polyline.size() < 2)
    return Result::TooShort;

  PolylineMatcher matcher(polyline, graph);
  std::vector<Border> borders;
  if (!matcher.FindBorders(borders))
    return Result::OutsideMaps;

  for (size_t i = 0; i < borders.size(); ++i)
  {
    if (auto const c = matcher.MatchBorder(borders[i]))
    {
      crossings.push_back(*c);
      continue;
    }
    // Into a mwm and back again (by the simplified polygons) without a transition on the way: leave both out.
    if (i + 1 < borders.size())
    {
      Border const & back = borders[i + 1];
      if (back.m_from == borders[i].m_to && back.m_to == borders[i].m_from &&
          matcher.BorderPosition(back) - matcher.BorderPosition(borders[i]) < kPhantomExcursionM &&
          !matcher.MatchBorder(back))
      {
        ++i;
        continue;
      }
    }
    crossings.clear();
    return Result::TransitionNotFound;
  }
  return Result::Ok;
}

std::string DebugPrint(ServerRouteMatcher::Result result)
{
  switch (result)
  {
  case ServerRouteMatcher::Result::Ok: return "Ok";
  case ServerRouteMatcher::Result::TooShort: return "TooShort";
  case ServerRouteMatcher::Result::OutsideMaps: return "OutsideMaps";
  case ServerRouteMatcher::Result::TransitionNotFound: return "TransitionNotFound";
  }
  UNREACHABLE();
}
}  // namespace routing
