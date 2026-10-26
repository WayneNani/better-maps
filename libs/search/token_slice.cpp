#include "search/token_slice.hpp"

#include "search/token_range.hpp"

#include <sstream>

namespace search
{
using namespace std;

namespace
{
template <typename Slice>
string SliceToString(string const & name, Slice const & slice)
{
  ostringstream os;
  os << name << " [";
  for (size_t i = 0; i < slice.Size(); ++i)
  {
    os << DebugPrint(slice.Get(i));
    if (i + 1 != slice.Size())
      os << ", ";
  }
  os << "]";
  return os.str();
}
}  // namespace

// TokenSlice --------------------------------------------------------------------------------------
TokenSlice::TokenSlice(QueryParams const & params, TokenRange const & range)
  : m_params(params)
  , m_offset(range.Begin())
  , m_size(range.Size())
{
  ASSERT(range.IsValid(), (range));
}

bool TokenSlice::IsPrefix(size_t i) const
{
  ASSERT_LESS(i, Size(), ());
  return m_params.IsPrefixToken(m_offset + i);
}

// TokenSliceNoCategories --------------------------------------------------------------------------
TokenSliceNoCategories::TokenSliceNoCategories(QueryParams const & params, TokenRange const & range) : m_params(params)
{
  m_indexes.reserve(range.Size());
  for (size_t i : range)
    if (!m_params.IsCategorySynonym(i))
      m_indexes.push_back(i);
}

// TokenSliceOfIndexes -----------------------------------------------------------------------------
TokenSliceOfIndexes::TokenSliceOfIndexes(QueryParams const & params, vector<size_t> indexes, bool skipCategories)
  : m_params(params)
  , m_indexes(std::move(indexes))
{
  if (skipCategories)
    std::erase_if(m_indexes, [&params](size_t i) { return params.IsCategorySynonym(i); });
}

string DebugPrint(TokenSlice const & slice)
{
  return SliceToString("TokenSlice", slice);
}

string DebugPrint(TokenSliceNoCategories const & slice)
{
  return SliceToString("TokenSliceNoCategories", slice);
}

string DebugPrint(TokenSliceOfIndexes const & slice)
{
  return SliceToString("TokenSliceOfIndexes", slice);
}
}  // namespace search
