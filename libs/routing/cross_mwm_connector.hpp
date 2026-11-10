#pragma once

#include "routing/base/small_list.hpp"
#include "routing/cross_mwm_ids.hpp"
#include "routing/route_weight.hpp"
#include "routing/segment.hpp"

#include "routing_common/num_mwm_id.hpp"

#include "coding/map_uint32_to_val.hpp"
#include "coding/sparse_vector.hpp"

#include "base/assert.hpp"
#include "base/checked_cast.hpp"

#include <algorithm>
#include <limits>
#include <string>
#include <utility>
#include <vector>

#include "3party/ankerl/unordered_dense.h"

namespace routing
{
namespace connector
{
double constexpr kNoRoute = 0.0;

/// @todo Can we make cross-mwm Weight in minutes and store it as uint16_t?
/// Will get 2x less memory without quality loss (minutes is fine granularity for cross-mwm routing).
using Weight = uint32_t;
Weight constexpr kNoRouteStored = 0;

enum class WeightsLoadState
{
  Unknown,
  NotExists,
  ReadyToLoad,
  Loaded
};

std::string DebugPrint(WeightsLoadState state);
}  // namespace connector

/// @param CrossMwmId Encoded OSM feature (way) ID that should be equal and unique in all MWMs.
template <typename CrossMwmId>
class CrossMwmConnector final
{
public:
  /// Should initialize with some valid mwm id here not to conflict with @see JointSegment::IsFake().
  explicit CrossMwmConnector(NumMwmId mwmId = kGeneratorMwmId) : m_mwmId(mwmId) {}

  template <class FnT>
  void ForEachTransitSegmentId(uint32_t featureId, FnT && fn) const
  {
    auto it = std::lower_bound(m_transitions.begin(), m_transitions.end(), Key{featureId, 0}, LessKT());
    while (it != m_transitions.end() && it->first.m_featureId == featureId)
    {
      if (fn(it->first.m_segmentIdx))
        break;
      ++it;
    }
  }

  bool IsTransition(Segment const & segment, bool isOutgoing) const
  {
    Key const key(segment.GetFeatureId(), segment.GetSegmentIdx());
    auto const it = std::lower_bound(m_transitions.begin(), m_transitions.end(), key, LessKT());
    if (it == m_transitions.end() || !(it->first == key))
      return false;

    auto const & transition = it->second;
    if (transition.m_oneWay && !segment.IsForward())
      return false;

    // Note. If |isOutgoing| == true |segment| should be an exit transition segment
    // (|isEnter| == false) to be a transition segment.
    // Otherwise |segment| should be an enter transition segment (|isEnter| == true)
    // to be a transition segment. If not, |segment| is not a transition segment.
    // Please see documentation on CrossMwmGraph::IsTransition() method for details.
    bool const isEnter = (segment.IsForward() == transition.m_forwardIsEnter);
    return isEnter != isOutgoing;
  }

  CrossMwmId const & GetCrossMwmId(Segment const & segment) const { return GetTransition(segment).m_crossMwmId; }

  /// @return {} if there is no transition for such cross mwm id.
  std::optional<Segment> GetTransition(CrossMwmId const & crossMwmId, uint32_t segmentIdx, bool isEnter) const
  {
    auto const fIt = m_crossMwmIdToFeatureId.find(crossMwmId);
    if (fIt == m_crossMwmIdToFeatureId.cend())
      return {};

    uint32_t const featureId = fIt->second;

    Transition const * transition = GetTransition(featureId, segmentIdx);
    if (transition == nullptr)
    {
      /// @todo By VNG: Workaround until cross-mwm transitions generator investigation.
      /// https://github.com/organicmaps/organicmaps/issues/1736
      /// Actually, the fix is valid, because transition features can have segment = 1 when leaving MWM
      /// and segment = 2 when entering MWM due to *not precise* packed MWM borders.
      if (isEnter)
        transition = GetTransition(featureId, ++segmentIdx);
      else if (segmentIdx > 0)
        transition = GetTransition(featureId, --segmentIdx);

      if (transition == nullptr)
        return {};
    }

    ASSERT_EQUAL(transition->m_crossMwmId, crossMwmId, ("fId:", featureId, ", segId:", segmentIdx));
    bool const isForward = transition->m_forwardIsEnter == isEnter;
    if (transition->m_oneWay && !isForward)
      return {};

    return Segment(m_mwmId, featureId, segmentIdx, isForward);
  }

  using EdgeListT = SmallList<SegmentEdge>;

  template <class FnT>
  void ForEachEnter(FnT && fn) const
  {
    for (auto const & [key, transit] : m_transitions)
    {
      if (transit.m_forwardIsEnter)
        fn(transit.m_enterIdx, Segment(m_mwmId, key.m_featureId, key.m_segmentIdx, true));

      if (!transit.m_oneWay && !transit.m_forwardIsEnter)
        fn(transit.m_enterIdx, Segment(m_mwmId, key.m_featureId, key.m_segmentIdx, false));
    }
  }

  template <class FnT>
  void ForEachExit(FnT && fn) const
  {
    for (auto const & [key, transit] : m_transitions)
    {
      if (!transit.m_forwardIsEnter)
        fn(transit.m_exitIdx, Segment(m_mwmId, key.m_featureId, key.m_segmentIdx, true));

      if (!transit.m_oneWay && transit.m_forwardIsEnter)
        fn(transit.m_exitIdx, Segment(m_mwmId, key.m_featureId, key.m_segmentIdx, false));
    }
  }

  void GetOutgoingEdgeList(Segment const & segment, EdgeListT & edges) const
  {
    auto const enterIdx = GetTransition(segment).m_enterIdx;
    if (m_weights.m_version >= 2 && m_weights.m_v2 && m_exitsCount > 0 && PrepareExitsOrder())
    {
      // Only a few percent of the weights of a row are stored: visit them only, but add the edges in the order
      // of ForEachExit() like below, the order matters for A* (equal weights).
      auto & row = m_rowScratch;
      row.clear();
      uint32_t const begin = GetWeightIndex(enterIdx, 0);
      m_weights.m_v2->ForEachInRange(begin, begin + m_exitsCount, [&](uint32_t id, WeightT weight)
      {
        uint32_t const position = m_exitPositions[id - begin];
        if (weight != connector::kNoRouteStored && position != kNoPosition)
          row.emplace_back(position, weight);
      });
      std::sort(row.begin(), row.end());
      for (auto const & [position, weight] : row)
        edges.emplace_back(m_exits[position], RouteWeight::FromCrossMwmWeight(weight));
      return;
    }

    ForEachExit([enterIdx, this, &edges](uint32_t exitIdx, Segment const & s)
    { AddEdge(s, enterIdx, exitIdx, edges); });
  }

  void GetIngoingEdgeList(Segment const & segment, EdgeListT & edges) const
  {
    auto const exitIdx = GetTransition(segment).m_exitIdx;
    if (m_weights.m_version >= 2 && m_weights.m_v2 && m_exitsCount > 0 && PrepareColumns())
    {
      // The weights of a column are far apart in the matrix (rows of exits): visit only the stored ones, in the
      // order of ForEachEnter() like below.
      for (uint32_t i = m_columnOffsets[exitIdx]; i < m_columnOffsets[exitIdx + 1]; ++i)
      {
        uint32_t const position = m_columnEnterPositions[i];
        AddEdge(m_enters[position], m_enterIndices[position], exitIdx, edges);
      }
      return;
    }

    ForEachEnter([exitIdx, this, &edges](uint32_t enterIdx, Segment const & s)
    { AddEdge(s, enterIdx, exitIdx, edges); });
  }

  RouteWeight GetWeightSure(Segment const & from, Segment const & to) const
  {
    auto const weight = GetWeight(GetTransition(from).m_enterIdx, GetTransition(to).m_exitIdx);
    ASSERT(weight != connector::kNoRouteStored, ());
    return RouteWeight::FromCrossMwmWeight(weight);
  }

  uint32_t GetNumEnters() const { return m_entersCount; }
  uint32_t GetNumExits() const { return m_exitsCount; }

  bool HasWeights() const { return !m_weights.Empty(); }
  bool IsEmpty() const { return m_entersCount == 0 && m_exitsCount == 0; }

  bool WeightsWereLoaded() const
  {
    switch (m_weights.m_loadState)
    {
    case connector::WeightsLoadState::Unknown:
    case connector::WeightsLoadState::ReadyToLoad: return false;
    case connector::WeightsLoadState::NotExists:
    case connector::WeightsLoadState::Loaded: return true;
    }
    UNREACHABLE();
  }

  uint32_t GetWeightIndex(uint32_t enterIdx, uint32_t exitIdx) const
  {
    ASSERT_LESS(enterIdx, m_entersCount, ());
    ASSERT_LESS(exitIdx, m_exitsCount, ());
    return base::asserted_cast<uint32_t>(size_t(enterIdx) * m_exitsCount + exitIdx);
  }

  using WeightT = connector::Weight;
  WeightT GetWeight(uint32_t enterIdx, uint32_t exitIdx) const
  {
    WeightT weight;
    return (m_weights.Get(GetWeightIndex(enterIdx, exitIdx), weight) ? weight : connector::kNoRouteStored);
  }

  size_t GetMemorySize() const
  {
    return (m_transitions.capacity() * sizeof(KeyTransitionT) +
            m_crossMwmIdToFeatureId.size() * sizeof(typename MwmID2FeatureIDMapT::value_type) +
            m_weights.GetMemorySize());
  }

private:
  template <class T>
  friend class CrossMwmConnectorBuilder;

  struct Key
  {
    Key(uint32_t featureId, uint32_t segmentIdx) : m_featureId(featureId), m_segmentIdx(segmentIdx) {}

    bool operator==(Key const & key) const
    {
      return (m_featureId == key.m_featureId && m_segmentIdx == key.m_segmentIdx);
    }

    bool operator<(Key const & key) const
    {
      if (m_featureId == key.m_featureId)
        return m_segmentIdx < key.m_segmentIdx;
      return m_featureId < key.m_featureId;
    }

    uint32_t m_featureId = 0;
    uint32_t m_segmentIdx = 0;
  };

  struct Transition
  {
    Transition(uint32_t enterIdx, uint32_t exitIdx, CrossMwmId crossMwmId, bool oneWay, bool forwardIsEnter)
      : m_enterIdx(enterIdx)
      , m_exitIdx(exitIdx)
      , m_crossMwmId(crossMwmId)
      , m_oneWay(oneWay)
      , m_forwardIsEnter(forwardIsEnter)
    {}

    uint32_t m_enterIdx;
    uint32_t m_exitIdx;
    CrossMwmId m_crossMwmId;

    // false - Transition represents both forward and backward segments with same featureId, segmentIdx.
    bool m_oneWay : 1;
    // true - forward segment is enter to mwm, enter means: m_backPoint is outside mwm borders, m_frontPoint is inside.
    bool m_forwardIsEnter : 1;
  };

  /// Fills |m_exits| (in the order of ForEachExit()) and |m_exitPositions| once.
  /// \returns false if an exit index is used twice (then the rows are read like before).
  bool PrepareExitsOrder() const
  {
    if (m_exitsOrderState == ExitsOrderState::Unknown)
    {
      m_exitPositions.assign(m_exitsCount, kNoPosition);
      m_exits.clear();
      m_exitsOrderState = ExitsOrderState::Ready;
      ForEachExit([this](uint32_t exitIdx, Segment const & s)
      {
        if (exitIdx >= m_exitsCount || m_exitPositions[exitIdx] != kNoPosition)
        {
          m_exitsOrderState = ExitsOrderState::NotUnique;
          return;
        }
        m_exitPositions[exitIdx] = static_cast<uint32_t>(m_exits.size());
        m_exits.push_back(s);
      });
    }
    return m_exitsOrderState == ExitsOrderState::Ready;
  }

  /// Fills |m_enters| and |m_enterIndices| (in the order of ForEachEnter()) and for each exit the positions there of
  /// the enters with a stored weight to this exit, once.
  /// \returns false if an enter index is used twice or there are too many enters (then the columns are read like
  /// before).
  bool PrepareColumns() const
  {
    if (m_columnsState != ColumnsState::Unknown)
      return m_columnsState == ColumnsState::Ready;

    m_columnsState = ColumnsState::NotSupported;
    std::vector<bool> used(m_entersCount, false);
    bool unique = true;
    ForEachEnter([&](uint32_t enterIdx, Segment const & s)
    {
      if (enterIdx >= m_entersCount || used[enterIdx])
      {
        unique = false;
        return;
      }
      used[enterIdx] = true;
      m_enters.push_back(s);
      m_enterIndices.push_back(enterIdx);
    });
    if (!unique || m_enters.size() > std::numeric_limits<uint16_t>::max())
    {
      m_enters = {};
      m_enterIndices = {};
      return false;
    }

    auto const forEachStored = [this](auto && fn)
    {
      for (size_t position = 0; position < m_enterIndices.size(); ++position)
      {
        uint32_t const begin = GetWeightIndex(m_enterIndices[position], 0);
        m_weights.m_v2->ForEachIdInRange(begin, begin + m_exitsCount,
                                         [&](uint32_t id) { fn(static_cast<uint16_t>(position), id - begin); });
      }
    };

    m_columnOffsets.assign(m_exitsCount + 1, 0);
    forEachStored([this](uint16_t, uint32_t exitIdx) { ++m_columnOffsets[exitIdx + 1]; });
    for (uint32_t i = 0; i < m_exitsCount; ++i)
      m_columnOffsets[i + 1] += m_columnOffsets[i];

    m_columnEnterPositions.resize(m_columnOffsets.back());
    std::vector<uint32_t> next(m_columnOffsets.begin(), m_columnOffsets.end() - 1);
    forEachStored([&](uint16_t position, uint32_t exitIdx) { m_columnEnterPositions[next[exitIdx]++] = position; });

    m_columnsState = ColumnsState::Ready;
    return true;
  }

  void AddEdge(Segment const & segment, uint32_t enterIdx, uint32_t exitIdx, EdgeListT & edges) const
  {
    auto const weight = GetWeight(enterIdx, exitIdx);
    if (weight != connector::kNoRouteStored)
      edges.emplace_back(segment, RouteWeight::FromCrossMwmWeight(weight));
  }

  Transition const * GetTransition(uint32_t featureId, uint32_t segmentIdx) const
  {
    Key key(featureId, segmentIdx);
    auto const it = std::lower_bound(m_transitions.begin(), m_transitions.end(), key, LessKT());
    if (it == m_transitions.end() || !(it->first == key))
      return nullptr;
    return &(it->second);
  }

  Transition const & GetTransition(Segment const & segment) const
  {
    Transition const * tr = GetTransition(segment.GetFeatureId(), segment.GetSegmentIdx());
    CHECK(tr, (segment));
    return *tr;
  }

  NumMwmId m_mwmId;
  uint32_t m_entersCount = 0;
  uint32_t m_exitsCount = 0;

  using KeyTransitionT = std::pair<Key, Transition>;
  struct LessKT
  {
    bool operator()(KeyTransitionT const & l, KeyTransitionT const & r) const { return l.first < r.first; }
    bool operator()(KeyTransitionT const & l, Key const & r) const { return l.first < r; }
    bool operator()(Key const & l, KeyTransitionT const & r) const { return l < r.first; }
  };
  std::vector<KeyTransitionT> m_transitions;

  using MwmID2FeatureIDMapT = ankerl::unordered_dense::map<CrossMwmId, uint32_t, connector::HashKey>;
  MwmID2FeatureIDMapT m_crossMwmIdToFeatureId;

  // Exits in the order of ForEachExit() and the position of each exit index there, for GetOutgoingEdgeList().
  static uint32_t constexpr kNoPosition = std::numeric_limits<uint32_t>::max();
  enum class ExitsOrderState : uint8_t
  {
    Unknown,
    Ready,
    NotUnique
  };
  mutable ExitsOrderState m_exitsOrderState = ExitsOrderState::Unknown;
  mutable std::vector<Segment> m_exits;
  mutable std::vector<uint32_t> m_exitPositions;
  mutable std::vector<std::pair<uint32_t, WeightT>> m_rowScratch;

  // Enters in the order of ForEachEnter() and, for each exit, the positions of the enters with a stored weight to it
  // (|m_columnOffsets[exitIdx]| till |m_columnOffsets[exitIdx + 1]|), for GetIngoingEdgeList().
  enum class ColumnsState : uint8_t
  {
    Unknown,
    Ready,
    NotSupported
  };
  mutable ColumnsState m_columnsState = ColumnsState::Unknown;
  mutable std::vector<Segment> m_enters;
  mutable std::vector<uint32_t> m_enterIndices;
  mutable std::vector<uint32_t> m_columnOffsets;
  mutable std::vector<uint16_t> m_columnEnterPositions;

  // Weight is the time required for the route to pass edge, measured in seconds rounded upwards.
  struct Weights
  {
    connector::WeightsLoadState m_loadState = connector::WeightsLoadState::Unknown;
    uint64_t m_offset = 0;
    WeightT m_granularity = 0;
    uint16_t m_version;

    coding::SparseVector<WeightT> m_v1;

    std::unique_ptr<MapUint32ToValue<WeightT>> m_v2;
    std::unique_ptr<Reader> m_reader;

    bool Empty() const
    {
      if (m_version < 2)
        return m_v1.Empty();
      else
        return m_v2 == nullptr;
    }

    bool Get(uint32_t idx, WeightT & weight) const
    {
      if (m_version < 2)
        if (m_v1.Has(idx))
        {
          weight = m_v1.Get(idx);
          return true;
        }
        else
          return false;
      else
        return m_v2->Get(idx, weight);
    }

  } m_weights;
};
}  // namespace routing
