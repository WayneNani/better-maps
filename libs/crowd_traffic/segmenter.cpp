#include "crowd_traffic/segmenter.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <utility>

namespace crowd_traffic
{
namespace
{
int ClampOffset(double v)
{
  return std::max(0, std::min(kTileSize - 1, static_cast<int>(std::floor(v))));
}

void AddCrossings(double a, double b, bool vertical, std::vector<GridCrossing> & res)
{
  double const ka = std::floor(a / kTileSize);
  double const kb = std::floor(b / kTileSize);
  if (ka == kb)
    return;
  // Line k * kTileSize is crossed when moving from tile k - 1 to tile k.
  double step = 1.0;
  double first = ka + 1;
  if (kb < ka)
  {
    step = -1.0;
    first = ka;
  }
  for (double k = first;; k += step)
  {
    res.push_back({(k * kTileSize - a) / (b - a), vertical, step > 0});
    if ((step > 0 && k >= kb) || (step < 0 && k <= kb + 1))
      break;
  }
}
}  // namespace

double Traversal::SpeedKmh() const
{
  double const hours = (m_tOut - m_tIn) / 3600.0;
  // No infinity (the code base is compiled with -ffast-math): a huge value is implausible as well.
  if (hours <= 0)
    return std::numeric_limits<double>::max();
  return m_lengthM / 1000.0 / hours;
}

std::vector<GridCrossing> GridCrossings(PixelPoint const & a, PixelPoint const & b)
{
  std::vector<GridCrossing> res;
  AddCrossings(a.m_x, b.m_x, true /* vertical */, res);
  AddCrossings(a.m_y, b.m_y, false /* vertical */, res);
  // Insertion sort: there are only a few crossings per step. Ties: vertical lines first.
  for (size_t i = 1; i < res.size(); ++i)
  {
    for (size_t j = i; j > 0; --j)
    {
      bool const less =
          res[j].m_t < res[j - 1].m_t || (res[j].m_t == res[j - 1].m_t && res[j].m_vertical && !res[j - 1].m_vertical);
      if (!less)
        break;
      std::swap(res[j], res[j - 1]);
    }
  }
  return res;
}

void Segmenter::AddLength(LatLon const & p)
{
  double const d = DistanceM(m_last.m_lat, m_last.m_lon, p.m_lat, p.m_lon);
  m_length += d;
  m_dist += d;
  m_last = p;
}

void Segmenter::MarkGap()
{
  if (m_hasPrev)
    m_prev.m_class = RoadClass::None;
}

void Segmenter::Add(TracePoint const & b, std::vector<Traversal> & out)
{
  if (!m_hasPrev)
  {
    m_hasPrev = true;
    m_prev = b;
    auto const p = Project(b.m_lat, b.m_lon);
    m_cur = {static_cast<int32_t>(std::floor(p.m_x / kTileSize)), static_cast<int32_t>(std::floor(p.m_y / kTileSize))};
    m_class = b.m_class;
    m_last = {b.m_lat, b.m_lon};
    return;
  }

  TracePoint const a = m_prev;
  auto const pa = Project(a.m_lat, a.m_lon);
  auto const pb = Project(b.m_lat, b.m_lon);
  if (a.m_class != m_class)
    m_mixed = true;

  for (auto const & c : GridCrossings(pa, pb))
  {
    double const cx = pa.m_x + c.m_t * (pb.m_x - pa.m_x);
    double const cy = pa.m_y + c.m_t * (pb.m_y - pa.m_y);
    LatLon const ll = Unproject(cx, cy);
    double const tm = a.m_time + c.m_t * (b.m_time - a.m_time);
    AddLength(ll);

    Edge exit;
    double const x0 = static_cast<double>(m_cur.m_x) * kTileSize;
    double const y0 = static_cast<double>(m_cur.m_y) * kTileSize;
    if (c.m_vertical)
      exit = {c.m_forward ? Side::East : Side::West, ClampOffset(cy - y0)};
    else
      exit = {c.m_forward ? Side::South : Side::North, ClampOffset(cx - x0)};

    if (m_entered)
    {
      Traversal t;
      t.m_tile = m_cur;
      t.m_entry = m_entry;
      t.m_exit = exit;
      t.m_class = m_mixed ? RoadClass::None : m_class;
      t.m_tIn = m_tIn;
      t.m_tOut = tm;
      t.m_lengthM = m_length;
      t.m_distIn = m_distIn;
      t.m_distOut = m_dist;
      t.m_in = m_in;
      t.m_out = ll;
      out.push_back(t);
    }
    m_cur = m_cur.Neighbor(exit.m_side);
    m_entered = true;
    m_entry = {Opposite(exit.m_side), exit.m_offset};
    m_tIn = tm;
    m_distIn = m_dist;
    m_in = ll;
    m_length = 0;
    m_class = a.m_class;
    m_mixed = false;
  }
  AddLength({b.m_lat, b.m_lon});
  m_prev = b;
}

std::vector<Traversal> Segment(std::vector<TracePoint> const & points)
{
  Segmenter s;
  std::vector<Traversal> res;
  for (auto const & p : points)
    s.Add(p, res);
  return res;
}
}  // namespace crowd_traffic
