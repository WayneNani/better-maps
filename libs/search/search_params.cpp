#include "search/search_params.hpp"

#include <sstream>

using namespace std;

namespace search
{
bool SearchParams::IsEqualCommon(SearchParams const & rhs) const
{
  return m_query == rhs.m_query && m_inputLocale == rhs.m_inputLocale &&
         static_cast<bool>(m_position) == static_cast<bool>(rhs.m_position) && m_mode == rhs.m_mode &&
         m_openingHoursFilter == rhs.m_openingHoursFilter && m_openingHoursTime == rhs.m_openingHoursTime;
}

string DebugPrint(SearchParams const & params)
{
  ostringstream os;
  os << "SearchParams [";
  os << "query: " << params.m_query << ", ";
  os << "locale: " << params.m_inputLocale << ", ";
  os << "mode: " << DebugPrint(params.m_mode) << ", ";
  os << "openingHoursFilter: " << DebugPrint(params.m_openingHoursFilter);
  os << "]";
  return os.str();
}
}  // namespace search
