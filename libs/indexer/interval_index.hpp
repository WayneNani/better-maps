#pragma once
#include "coding/byte_stream.hpp"
#include "coding/endianness.hpp"
#include "coding/reader.hpp"
#include "coding/varint.hpp"

#include "base/assert.hpp"
#include "base/buffer_vector.hpp"

#include <algorithm>
#include <cstdint>
#include <utility>

class IntervalIndexBase
{
public:
#pragma pack(push, 1)
  struct Header
  {
    uint8_t m_Version;
    uint8_t m_Levels;
    uint8_t m_BitsPerLevel;
    uint8_t m_LeafBytes;
  };
#pragma pack(pop)
  static_assert(sizeof(Header) == 4, "");

  static inline uint32_t BitmapSize(uint32_t bitsPerLevel)
  {
    ASSERT_GREATER(bitsPerLevel, 3, ());
    return 1 << (bitsPerLevel - 3);
  }

  enum
  {
    kVersion = 1
  };
};

template <class ReaderT, typename Value>
class IntervalIndex : public IntervalIndexBase
{
  typedef IntervalIndexBase base_t;

public:
  explicit IntervalIndex(ReaderT const & reader) : m_Reader(reader)
  {
    ReaderSource<ReaderT> src(reader);
    src.Read(&m_Header, sizeof(Header));
    CHECK_EQUAL(m_Header.m_Version, static_cast<uint8_t>(kVersion), ());
    if (m_Header.m_Levels != 0)
      for (int i = 0; i <= m_Header.m_Levels + 1; ++i)
        m_LevelOffsets.push_back(ReadPrimitiveFromSource<uint32_t>(src));
  }

  uint64_t KeyEnd() const { return 1ULL << (m_Header.m_Levels * m_Header.m_BitsPerLevel + m_Header.m_LeafBytes * 8); }

  template <typename F>
  void ForEach(F const & f, uint64_t beg, uint64_t end) const
  {
    if (m_Header.m_Levels != 0 && beg != end)
    {
      // ASSERT_LESS_OR_EQUAL(beg, KeyEnd(), (end));
      // ASSERT_LESS_OR_EQUAL(end, KeyEnd(), (beg));
      if (beg > KeyEnd())
        beg = KeyEnd();
      if (end > KeyEnd())
        end = KeyEnd();
      --end;  // end is inclusive in ForEachImpl().
      ForEachNode(f, beg, end, m_Header.m_Levels, 0,
                  m_LevelOffsets[m_Header.m_Levels + 1] - m_LevelOffsets[m_Header.m_Levels], 0 /* started keyBase */);
    }
  }

  // Calls f(key, value) for each key in any of |intervals| ([beg, end) pairs, sorted and not overlapping), in the
  // order of keys. Gives the same as ForEach() for each interval, but reads the nodes shared by the intervals once.
  template <typename F, typename Intervals>
  void ForEachInIntervals(F const & f, Intervals const & intervals) const
  {
    if (m_Header.m_Levels == 0)
      return;

    // Inclusive ranges of keys, clamped like in ForEach().
    buffer_vector<std::pair<uint64_t, uint64_t>, 128> ranges;
    uint64_t const keyEnd = KeyEnd();
    for (auto const & interval : intervals)
    {
      uint64_t const beg = std::min(static_cast<uint64_t>(interval.first), keyEnd);
      uint64_t const end = std::min(static_cast<uint64_t>(interval.second), keyEnd);
      if (beg >= end)
        continue;
      ASSERT(ranges.empty() || ranges.back().second < beg, ("Intervals must be sorted and not overlap"));
      ranges.emplace_back(beg, end - 1);
    }
    if (ranges.empty())
      return;

    ForEachNodeInRanges(f, ranges.data(), ranges.data() + ranges.size(), m_Header.m_Levels, 0,
                        m_LevelOffsets[m_Header.m_Levels + 1] - m_LevelOffsets[m_Header.m_Levels], 0 /* keyBase */);
  }

private:
  using Range = std::pair<uint64_t, uint64_t>;

  // All ranges in [rangesBeg, rangesEnd) intersect the node, keys are absolute (keyBase included).
  template <typename F>
  void ForEachNodeInRanges(F const & f, Range const * rangesBeg, Range const * rangesEnd, int level, uint32_t offset,
                           uint32_t size, uint64_t keyBase) const
  {
    ASSERT(size > 0, ());
    ASSERT(rangesBeg < rangesEnd, ());
    offset += m_LevelOffsets[level];

    buffer_vector<uint8_t, 1024> data;
    data.resize(size);
    m_Reader.Read(offset, &data[0], size);
    ArrayByteSource src(&data[0]);
    void const * pEnd = &data[0] + size;

    uint64_t const lastKey = (rangesEnd - 1)->second;
    Range const * range = rangesBeg;

    if (level == 0)
    {
      Value value = 0;
      while (src.Ptr() < pEnd)
      {
        uint32_t key = 0;
        src.Read(&key, m_Header.m_LeafBytes);
        uint64_t const absKey = keyBase + SwapIfBigEndianMacroBased(key);
        if (absKey > lastKey)
          break;
        value += ReadVarInt<int64_t>(src);
        while (range->second < absKey)
          ++range;
        if (absKey >= range->first)
          f(absKey, value);
      }
      return;
    }

    uint8_t const skipBits = (m_Header.m_LeafBytes << 3) + (level - 1) * m_Header.m_BitsPerLevel;
    uint64_t const childKeys = uint64_t{1} << skipBits;
    // The last child which may intersect the ranges.
    uint32_t const lastChild = static_cast<uint32_t>(
        (std::min(lastKey, keyBase + (childKeys << m_Header.m_BitsPerLevel) - 1) - keyBase) >> skipBits);

    auto const forChild = [&](uint32_t i, uint32_t childOffset, uint32_t childSize)
    {
      uint64_t const childBeg = keyBase + (uint64_t{i} << skipBits);
      uint64_t const childLast = childBeg + childKeys - 1;
      while (range != rangesEnd && range->second < childBeg)
        ++range;
      Range const * childRangesEnd = range;
      while (childRangesEnd != rangesEnd && childRangesEnd->first <= childLast)
        ++childRangesEnd;
      if (range != childRangesEnd)
        ForEachNodeInRanges(f, range, childRangesEnd, level - 1, childOffset, childSize, childBeg);
    };

    uint32_t const offsetAndFlag = ReadVarUint<uint32_t>(src);
    uint32_t childOffset = offsetAndFlag >> 1;
    if (offsetAndFlag & 1)
    {
      // Reading bitmap.
      uint8_t const * pBitmap = static_cast<uint8_t const *>(src.Ptr());
      src.Advance(BitmapSize(m_Header.m_BitsPerLevel));
      for (uint32_t i = 0; i <= lastChild; ++i)
      {
        if (bits::GetBit(pBitmap, i))
        {
          uint32_t const childSize = ReadVarUint<uint32_t>(src);
          forChild(i, childOffset, childSize);
          childOffset += childSize;
        }
      }
    }
    else
    {
      while (src.Ptr() < pEnd)
      {
        uint8_t const i = src.ReadByte();
        if (i > lastChild)
          break;
        uint32_t const childSize = ReadVarUint<uint32_t>(src);
        forChild(i, childOffset, childSize);
        childOffset += childSize;
      }
    }
  }

  template <typename F>
  void ForEachLeaf(F const & f, uint64_t const beg, uint64_t const end, uint32_t const offset, uint32_t const size,
                   uint64_t keyBase /* discarded part of object key value in the parent nodes*/) const
  {
    buffer_vector<uint8_t, 1024> data;
    data.resize(size);

    m_Reader.Read(offset, &data[0], size);
    ArrayByteSource src(&data[0]);

    void const * pEnd = &data[0] + size;
    Value value = 0;
    while (src.Ptr() < pEnd)
    {
      uint32_t key = 0;
      src.Read(&key, m_Header.m_LeafBytes);
      key = SwapIfBigEndianMacroBased(key);
      if (key > end)
        break;
      value += ReadVarInt<int64_t>(src);
      if (key >= beg)
        f(keyBase + key, value);
    }
  }

  template <typename F>
  void ForEachNode(F const & f, uint64_t beg, uint64_t end, int level, uint32_t offset, uint32_t size,
                   uint64_t keyBase /* discarded part of object key value in the parent nodes */) const
  {
    ASSERT(size > 0, ());
    offset += m_LevelOffsets[level];

    if (level == 0)
    {
      ForEachLeaf(f, beg, end, offset, size, keyBase);
      return;
    }

    uint8_t const skipBits = (m_Header.m_LeafBytes << 3) + (level - 1) * m_Header.m_BitsPerLevel;
    ASSERT_LESS_OR_EQUAL(beg, end, (skipBits));

    uint64_t const levelBytesFF = (1ULL << skipBits) - 1;
    uint32_t const beg0 = static_cast<uint32_t>(beg >> skipBits);
    uint32_t const end0 = static_cast<uint32_t>(end >> skipBits);
    ASSERT_LESS(end0, (1U << m_Header.m_BitsPerLevel), (beg, end, skipBits));

    buffer_vector<uint8_t, 576> data;
    data.resize(size);

    m_Reader.Read(offset, &data[0], size);
    ArrayByteSource src(&data[0]);

    uint32_t const offsetAndFlag = ReadVarUint<uint32_t>(src);
    uint32_t childOffset = offsetAndFlag >> 1;
    if (offsetAndFlag & 1)
    {
      // Reading bitmap.
      uint8_t const * pBitmap = static_cast<uint8_t const *>(src.Ptr());
      src.Advance(BitmapSize(m_Header.m_BitsPerLevel));
      for (uint32_t i = 0; i <= end0; ++i)
      {
        if (bits::GetBit(pBitmap, i))
        {
          uint32_t childSize = ReadVarUint<uint32_t>(src);
          if (i >= beg0)
          {
            uint64_t const beg1 = (i == beg0) ? (beg & levelBytesFF) : 0;
            uint64_t const end1 = (i == end0) ? (end & levelBytesFF) : levelBytesFF;
            ForEachNode(f, beg1, end1, level - 1, childOffset, childSize, keyBase + (uint64_t{i} << skipBits));
          }
          childOffset += childSize;
        }
      }
      ASSERT(end0 != (static_cast<uint32_t>(1) << m_Header.m_BitsPerLevel) - 1 || src.Ptr() == data.data() + size,
             (beg, end, beg0, end0, offset, size, src.Ptr(), data.data()));
    }
    else
    {
      void const * pEnd = &data[0] + size;
      while (src.Ptr() < pEnd)
      {
        uint8_t const i = src.ReadByte();
        if (i > end0)
          break;
        uint32_t childSize = ReadVarUint<uint32_t>(src);
        if (i >= beg0)
        {
          uint64_t const beg1 = (i == beg0) ? (beg & levelBytesFF) : 0;
          uint64_t const end1 = (i == end0) ? (end & levelBytesFF) : levelBytesFF;
          ForEachNode(f, beg1, end1, level - 1, childOffset, childSize, keyBase + (uint64_t{i} << skipBits));
        }
        childOffset += childSize;
      }
    }
  }

  ReaderT m_Reader;
  Header m_Header;
  buffer_vector<uint32_t, 7> m_LevelOffsets;
};
