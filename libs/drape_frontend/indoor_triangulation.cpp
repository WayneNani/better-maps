#include "drape_frontend/indoor_triangulation.hpp"

#include <algorithm>
#include <deque>
#include <limits>

namespace df::indoor
{
namespace
{
struct Node
{
  size_t m_i = 0;
  double m_x = 0;
  double m_y = 0;
  Node * m_prev = nullptr;
  Node * m_next = nullptr;
  bool m_steiner = false;
};

class Earcut
{
public:
  explicit Earcut(std::vector<m2::PointD> const & points) : m_points(points) {}

  std::vector<size_t> Run(std::vector<std::pair<size_t, size_t>> const & rings)
  {
    if (rings.empty())
      return {};
    Node * outer = LinkedList(rings[0].first, rings[0].second, true /* clockwise */);
    if (outer == nullptr || outer->m_next == outer->m_prev)
      return {};
    if (rings.size() > 1)
      outer = EliminateHoles(rings, outer);
    EarcutLinked(outer, 0);
    return std::move(m_triangles);
  }

private:
  static double Area(Node const * p, Node const * q, Node const * r)
  {
    return (q->m_y - p->m_y) * (r->m_x - q->m_x) - (q->m_x - p->m_x) * (r->m_y - q->m_y);
  }

  static bool Equals(Node const * a, Node const * b) { return a->m_x == b->m_x && a->m_y == b->m_y; }

  static int Sign(double v) { return (v > 0) - (v < 0); }

  static bool OnSegment(Node const * p, Node const * q, Node const * r)
  {
    return q->m_x <= std::max(p->m_x, r->m_x) && q->m_x >= std::min(p->m_x, r->m_x) &&
           q->m_y <= std::max(p->m_y, r->m_y) && q->m_y >= std::min(p->m_y, r->m_y);
  }

  static bool Intersects(Node const * p1, Node const * q1, Node const * p2, Node const * q2)
  {
    int const o1 = Sign(Area(p1, q1, p2));
    int const o2 = Sign(Area(p1, q1, q2));
    int const o3 = Sign(Area(p2, q2, p1));
    int const o4 = Sign(Area(p2, q2, q1));
    if (o1 != o2 && o3 != o4)
      return true;
    if (o1 == 0 && OnSegment(p1, p2, q1))
      return true;
    if (o2 == 0 && OnSegment(p1, q2, q1))
      return true;
    if (o3 == 0 && OnSegment(p2, p1, q2))
      return true;
    if (o4 == 0 && OnSegment(p2, q1, q2))
      return true;
    return false;
  }

  static bool PointInTriangle(double ax, double ay, double bx, double by, double cx, double cy, double px, double py)
  {
    return (cx - px) * (ay - py) >= (ax - px) * (cy - py) && (ax - px) * (by - py) >= (bx - px) * (ay - py) &&
           (bx - px) * (cy - py) >= (cx - px) * (by - py);
  }

  static bool LocallyInside(Node const * a, Node const * b)
  {
    return Area(a->m_prev, a, a->m_next) < 0 ? Area(a, b, a->m_next) >= 0 && Area(a, a->m_prev, b) >= 0
                                             : Area(a, b, a->m_prev) < 0 || Area(a, a->m_next, b) < 0;
  }

  static bool MiddleInside(Node const * a, Node const * b)
  {
    Node const * p = a;
    bool inside = false;
    double const px = (a->m_x + b->m_x) / 2, py = (a->m_y + b->m_y) / 2;
    do
    {
      if (((p->m_y > py) != (p->m_next->m_y > py)) && p->m_next->m_y != p->m_y &&
          (px < (p->m_next->m_x - p->m_x) * (py - p->m_y) / (p->m_next->m_y - p->m_y) + p->m_x))
        inside = !inside;
      p = p->m_next;
    }
    while (p != a);
    return inside;
  }

  static bool IntersectsPolygon(Node const * a, Node const * b)
  {
    Node const * p = a;
    do
    {
      if (p->m_i != a->m_i && p->m_next->m_i != a->m_i && p->m_i != b->m_i && p->m_next->m_i != b->m_i &&
          Intersects(p, p->m_next, a, b))
        return true;
      p = p->m_next;
    }
    while (p != a);
    return false;
  }

  static bool IsValidDiagonal(Node const * a, Node const * b)
  {
    return a->m_next->m_i != b->m_i && a->m_prev->m_i != b->m_i && !IntersectsPolygon(a, b) &&
           ((LocallyInside(a, b) && LocallyInside(b, a) && MiddleInside(a, b) &&
             (Area(a->m_prev, a, b->m_prev) != 0 || Area(a, b->m_prev, b) != 0)) ||
            (Equals(a, b) && Area(a->m_prev, a, a->m_next) > 0 && Area(b->m_prev, b, b->m_next) > 0));
  }

  static void RemoveNode(Node * p)
  {
    p->m_next->m_prev = p->m_prev;
    p->m_prev->m_next = p->m_next;
  }

  Node * CreateNode(size_t i, double x, double y)
  {
    auto & n = m_nodes.emplace_back();
    n.m_i = i;
    n.m_x = x;
    n.m_y = y;
    return &n;
  }

  Node * InsertNode(size_t i, Node * last)
  {
    Node * p = CreateNode(i, m_points[i].x, m_points[i].y);
    if (last == nullptr)
    {
      p->m_prev = p;
      p->m_next = p;
    }
    else
    {
      p->m_next = last->m_next;
      p->m_prev = last;
      last->m_next->m_prev = p;
      last->m_next = p;
    }
    return p;
  }

  double SignedArea(size_t start, size_t end) const
  {
    double sum = 0;
    for (size_t i = start, j = end - 1; i < end; j = i++)
      sum += (m_points[j].x - m_points[i].x) * (m_points[i].y + m_points[j].y);
    return sum;
  }

  Node * LinkedList(size_t start, size_t end, bool clockwise)
  {
    if (end - start < 3)
      return nullptr;
    Node * last = nullptr;
    if (clockwise == (SignedArea(start, end) > 0))
      for (size_t i = start; i < end; ++i)
        last = InsertNode(i, last);
    else
      for (size_t i = end; i-- > start;)
        last = InsertNode(i, last);
    if (last != nullptr && Equals(last, last->m_next))
    {
      RemoveNode(last);
      last = last->m_next;
    }
    return last;
  }

  static Node * FilterPoints(Node * start, Node * end = nullptr)
  {
    if (start == nullptr)
      return start;
    if (end == nullptr)
      end = start;
    Node * p = start;
    bool again;
    do
    {
      again = false;
      if (!p->m_steiner && (Equals(p, p->m_next) || Area(p->m_prev, p, p->m_next) == 0))
      {
        RemoveNode(p);
        p = end = p->m_prev;
        if (p == p->m_next)
          break;
        again = true;
      }
      else
      {
        p = p->m_next;
      }
    }
    while (again || p != end);
    return end;
  }

  static bool IsEar(Node const * ear)
  {
    Node const * a = ear->m_prev;
    Node const * b = ear;
    Node const * c = ear->m_next;
    if (Area(a, b, c) >= 0)
      return false;  // reflex
    double const x0 = std::min({a->m_x, b->m_x, c->m_x}), x1 = std::max({a->m_x, b->m_x, c->m_x});
    double const y0 = std::min({a->m_y, b->m_y, c->m_y}), y1 = std::max({a->m_y, b->m_y, c->m_y});
    for (Node const * p = c->m_next; p != a; p = p->m_next)
      if (p->m_x >= x0 && p->m_x <= x1 && p->m_y >= y0 && p->m_y <= y1 && !(a->m_x == p->m_x && a->m_y == p->m_y) &&
          PointInTriangle(a->m_x, a->m_y, b->m_x, b->m_y, c->m_x, c->m_y, p->m_x, p->m_y) &&
          Area(p->m_prev, p, p->m_next) >= 0)
        return false;
    return true;
  }

  void Emit(Node const * a, Node const * b, Node const * c)
  {
    m_triangles.push_back(a->m_i);
    m_triangles.push_back(b->m_i);
    m_triangles.push_back(c->m_i);
  }

  Node * CureLocalIntersections(Node * start)
  {
    Node * p = start;
    do
    {
      Node * a = p->m_prev;
      Node * b = p->m_next->m_next;
      if (!Equals(a, b) && Intersects(a, p, p->m_next, b) && LocallyInside(a, b) && LocallyInside(b, a))
      {
        Emit(a, p, b);
        RemoveNode(p);
        RemoveNode(p->m_next);
        p = start = b;
      }
      p = p->m_next;
    }
    while (p != start);
    return FilterPoints(p);
  }

  Node * SplitPolygon(Node * a, Node * b)
  {
    Node * a2 = CreateNode(a->m_i, a->m_x, a->m_y);
    Node * b2 = CreateNode(b->m_i, b->m_x, b->m_y);
    Node * an = a->m_next;
    Node * bp = b->m_prev;
    a->m_next = b;
    b->m_prev = a;
    a2->m_next = an;
    an->m_prev = a2;
    b2->m_next = a2;
    a2->m_prev = b2;
    bp->m_next = b2;
    b2->m_prev = bp;
    return b2;
  }

  void SplitEarcut(Node * start, int depth)
  {
    Node * a = start;
    do
    {
      Node * b = a->m_next->m_next;
      while (b != a->m_prev)
      {
        if (a->m_i != b->m_i && IsValidDiagonal(a, b))
        {
          Node * c = SplitPolygon(a, b);
          a = FilterPoints(a, a->m_next);
          c = FilterPoints(c, c->m_next);
          EarcutLinked(a, 0, depth + 1);
          EarcutLinked(c, 0, depth + 1);
          return;
        }
        b = b->m_next;
      }
      a = a->m_next;
    }
    while (a != start);
  }

  void EarcutLinked(Node * ear, int pass, int depth = 0)
  {
    // Guards against pathological recursion on broken input.
    if (ear == nullptr || depth > 64)
      return;
    Node * stop = ear;
    while (ear->m_prev != ear->m_next)
    {
      Node * prev = ear->m_prev;
      Node * next = ear->m_next;
      if (IsEar(ear))
      {
        Emit(prev, ear, next);
        RemoveNode(ear);
        ear = next->m_next;
        stop = next->m_next;
        continue;
      }
      ear = next;
      if (ear == stop)
      {
        if (pass == 0)
          EarcutLinked(FilterPoints(ear), 1, depth);
        else if (pass == 1)
          EarcutLinked(CureLocalIntersections(FilterPoints(ear)), 2, depth);
        else
          SplitEarcut(ear, depth);
        break;
      }
    }
  }

  static Node * GetLeftmost(Node * start)
  {
    Node * p = start;
    Node * leftmost = start;
    do
    {
      if (p->m_x < leftmost->m_x || (p->m_x == leftmost->m_x && p->m_y < leftmost->m_y))
        leftmost = p;
      p = p->m_next;
    }
    while (p != start);
    return leftmost;
  }

  static bool SectorContainsSector(Node const * m, Node const * p)
  {
    return Area(m->m_prev, m, p->m_prev) < 0 && Area(p->m_next, m, m->m_next) < 0;
  }

  static Node * FindHoleBridge(Node * hole, Node * outer)
  {
    Node * p = outer;
    double const hx = hole->m_x, hy = hole->m_y;
    double qx = -std::numeric_limits<double>::infinity();
    Node * m = nullptr;
    do
    {
      if (hy <= p->m_y && hy >= p->m_next->m_y && p->m_next->m_y != p->m_y)
      {
        double const x = p->m_x + (hy - p->m_y) * (p->m_next->m_x - p->m_x) / (p->m_next->m_y - p->m_y);
        if (x <= hx && x > qx)
        {
          qx = x;
          m = p->m_x < p->m_next->m_x ? p : p->m_next;
          if (x == hx)
            return m;
        }
      }
      p = p->m_next;
    }
    while (p != outer);
    if (m == nullptr)
      return nullptr;

    Node * stop = m;
    double const mx = m->m_x, my = m->m_y;
    double tanMin = std::numeric_limits<double>::infinity();
    p = m;
    do
    {
      if (hx >= p->m_x && p->m_x >= mx && hx != p->m_x &&
          PointInTriangle(hy < my ? hx : qx, hy, mx, my, hy < my ? qx : hx, hy, p->m_x, p->m_y))
      {
        double const tan = std::abs(hy - p->m_y) / (hx - p->m_x);
        if (LocallyInside(p, hole) &&
            (tan < tanMin || (tan == tanMin && (p->m_x > m->m_x || (p->m_x == m->m_x && SectorContainsSector(m, p))))))
        {
          m = p;
          tanMin = tan;
        }
      }
      p = p->m_next;
    }
    while (p != stop);
    return m;
  }

  Node * EliminateHoles(std::vector<std::pair<size_t, size_t>> const & rings, Node * outer)
  {
    std::vector<Node *> queue;
    for (size_t r = 1; r < rings.size(); ++r)
    {
      Node * list = LinkedList(rings[r].first, rings[r].second, false /* clockwise */);
      if (list == nullptr)
        continue;
      if (list == list->m_next)
        list->m_steiner = true;
      queue.push_back(GetLeftmost(list));
    }
    std::sort(queue.begin(), queue.end(),
              [](Node const * a, Node const * b) { return a->m_x != b->m_x ? a->m_x < b->m_x : a->m_y < b->m_y; });
    for (Node * hole : queue)
    {
      Node * bridge = FindHoleBridge(hole, outer);
      if (bridge == nullptr)
        continue;
      Node * bridgeReverse = SplitPolygon(bridge, hole);
      FilterPoints(bridgeReverse, bridgeReverse->m_next);
      outer = FilterPoints(bridge, bridge->m_next);
    }
    return outer;
  }

  std::vector<m2::PointD> const & m_points;
  std::deque<Node> m_nodes;
  std::vector<size_t> m_triangles;
};
}  // namespace

std::vector<m2::PointD> Triangulate(std::vector<std::vector<m2::PointD>> const & polygon)
{
  if (polygon.empty() || polygon.front().size() < 3)
    return {};

  // Local coordinates (scaled, relative to the first point): exact comparisons of equal input points stay exact and
  // the products in the orientation tests do not lose precision.
  m2::PointD const origin = polygon.front().front();
  double constexpr kScale = 1e6;
  std::vector<m2::PointD> points;
  std::vector<std::pair<size_t, size_t>> rings;
  for (size_t r = 0; r < polygon.size(); ++r)
  {
    auto const & ring = polygon[r];
    if (ring.size() < 3)
    {
      if (r == 0)
        return {};
      continue;
    }
    size_t const start = points.size();
    for (auto const & p : ring)
      points.emplace_back((p.x - origin.x) * kScale, (p.y - origin.y) * kScale);
    // Closed rings are accepted.
    if (points.back() == points[start])
      points.pop_back();
    if (points.size() - start < 3)
    {
      if (r == 0)
        return {};
      points.resize(start);
      continue;
    }
    rings.emplace_back(start, points.size());
  }

  auto const indices = Earcut(points).Run(rings);
  std::vector<m2::PointD> result;
  result.reserve(indices.size());
  for (size_t i : indices)
    result.emplace_back(points[i].x / kScale + origin.x, points[i].y / kScale + origin.y);
  return result;
}
}  // namespace df::indoor
