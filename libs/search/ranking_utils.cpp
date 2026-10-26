#include "search/ranking_utils.hpp"

#include "search/token_slice.hpp"
#include "search/utils.hpp"

#include "base/dfa_helpers.hpp"

#include "indexer/classificator.hpp"
#include "indexer/feature_data.hpp"
#include "indexer/search_string_utils.hpp"

#include "base/assert.hpp"

#include <algorithm>
#include <array>
#include <sstream>
#include <string_view>

namespace search
{
using namespace std;
using namespace strings;

// CategoriesInfo ----------------------------------------------------------------------------------
CategoriesInfo::CategoriesInfo(feature::TypesHolder const & holder, TokenSlice const & tokens, Locales const & locales,
                               CategoriesHolder const & categories)
{
  struct TokenInfo
  {
    bool m_isCategoryToken = false;
    bool m_inFeatureTypes = false;
  };

  QuerySlice slice(tokens);
  vector<TokenInfo> infos(slice.Size());

  ForEachCategoryType(slice, locales, categories, [&](size_t i, uint32_t t)
  {
    ASSERT_LESS(i, infos.size(), ());
    auto & info = infos[i];

    info.m_isCategoryToken = true;
    if (holder.HasWithSubclass(t))
      info.m_inFeatureTypes = true;
  });

  for (size_t i = 0; i < slice.Size(); ++i)
    if (infos[i].m_inFeatureTypes)
      m_matchedLength += slice.Get(i).size();

  // Note that m_inFeatureTypes implies m_isCategoryToken.

  m_pureCategories = all_of(infos.begin(), infos.end(), [](TokenInfo const & info)
  {
    ASSERT(!info.m_inFeatureTypes || info.m_isCategoryToken, ());
    return info.m_inFeatureTypes;
  });

  m_falseCategories = all_of(infos.begin(), infos.end(),
                             [](TokenInfo const & info) { return !info.m_inFeatureTypes && info.m_isCategoryToken; });
}

// ErrorsMade --------------------------------------------------------------------------------------
string DebugPrint(ErrorsMade const & errorsMade)
{
  if (errorsMade.IsValid())
    return std::to_string(errorsMade.m_errorsMade);
  else
    return "Invalid";
}

namespace impl
{
ErrorsMade GetErrorsMade(QueryParams::Token const & token, strings::UniString const & text, LevenshteinDFA const & dfa)
{
  if (token.AnyOfSynonyms([&text](strings::UniString const & s) { return text == s; }))
    return ErrorsMade(0);

  auto it = dfa.Begin();
  strings::DFAMove(it, token.GetOriginal().begin(), token.GetOriginal().end());
  if (it.Accepts())
    return ErrorsMade(it.ErrorsMade());

  return {};
}

ErrorsMade GetPrefixErrorsMade(QueryParams::Token const & token, strings::UniString const & text,
                               LevenshteinDFA const & dfa)
{
  if (token.AnyOfSynonyms([&text](strings::UniString const & s) { return StartsWith(text, s); }))
    return ErrorsMade(0);

  auto it = dfa.Begin();
  strings::DFAMove(it, token.GetOriginal().begin(), token.GetOriginal().end());
  if (!it.Rejects())
    return ErrorsMade(it.PrefixErrorsMade());

  return {};
}
}  // namespace impl

TokensVector::TokensVector(string_view name)
{
  m_tokens = NormalizeAndTokenizeString(std::move(name));

  Init();
}

string DebugPrint(NameScore const & score)
{
  switch (score)
  {
  case NameScore::ZERO: return "Zero";
  case NameScore::SUBSTRING: return "Substring";
  case NameScore::PREFIX: return "Prefix";
  case NameScore::FIRST_MATCH: return "First Match";
  case NameScore::FULL_PREFIX: return "Full Prefix";
  case NameScore::FULL_MATCH: return "Full Match";
  case NameScore::COUNT: return "Count";
  }
  return "Unknown";
}

string DebugPrint(NameScores const & scores)
{
  ostringstream os;
  os << boolalpha << "NameScores "
     << "{ m_nameScore: " << DebugPrint(scores.m_nameScore) << ", m_matchedLength: " << scores.m_matchedLength
     << ", m_errorsMade: " << DebugPrint(scores.m_errorsMade) << ", m_isAltOrOldName: "
     << " }";
  return os.str();
}
string DebugPrint(StationPart part)
{
  switch (part)
  {
  case StationPart::None: return "None";
  case StationPart::Part: return "Part";
  case StationPart::Building: return "Building";
  case StationPart::Subway: return "Subway";
  case StationPart::LightRail: return "LightRail";
  case StationPart::Halt: return "Halt";
  case StationPart::Station: return "Station";
  }
  UNREACHABLE();
}

StationPart GetStationPart(feature::TypesHolder const & types)
{
  // public_transport=platform is not here: it is mostly a bus or a tram platform, not a part of a station.
  static auto const kTypes = []()
  {
    Classificator const & c = classif();
    return std::array<std::pair<uint32_t, StationPart>, 9>{{
        {c.GetTypeByPath({"railway", "subway_entrance"}), StationPart::Part},
        {c.GetTypeByPath({"railway", "platform"}), StationPart::Part},
        {c.GetTypeByPath({"building", "train_station"}), StationPart::Building},
        {c.GetTypeByPath({"railway", "station", "subway"}), StationPart::Subway},
        {c.GetTypeByPath({"railway", "station", "funicular"}), StationPart::Subway},
        {c.GetTypeByPath({"railway", "station", "monorail"}), StationPart::Subway},
        {c.GetTypeByPath({"railway", "station", "light_rail"}), StationPart::LightRail},
        {c.GetTypeByPath({"railway", "halt"}), StationPart::Halt},
        {c.GetTypeByPath({"railway", "station"}), StationPart::Station},
    }};
  }();

  auto result = StationPart::None;
  for (uint32_t type : types)
  {
    // The most specific matching type: railway-station-subway-berlin is a subway station, not a railway station.
    for (uint8_t level = std::min<uint8_t>(ftype::GetLevel(type), 3); level >= 2; --level)
    {
      uint32_t const truncated = ftype::Trunc(type, level);
      auto const it =
          std::find_if(kTypes.begin(), kTypes.end(), [truncated](auto const & p) { return p.first == truncated; });
      if (it != kTypes.end())
      {
        result = std::max(result, it->second);
        break;
      }
    }
  }
  return result;
}

bool AreSimilarStationNames(std::string_view lhs, std::string_view rhs)
{
  auto const tokenize = [](std::string_view name)
  {
    static UniString const kHbf = MakeUniString("hbf");
    static UniString const kHauptbahnhof = MakeUniString("hauptbahnhof");
    static UniString const kBf = MakeUniString("bf");
    static UniString const kBhf = MakeUniString("bhf");
    static UniString const kBahnhof = MakeUniString("bahnhof");

    auto tokens = NormalizeAndTokenizeString(name);
    for (auto & t : tokens)
    {
      if (t == kHbf)
        t = kHauptbahnhof;
      else if (t == kBf || t == kBhf)
        t = kBahnhof;
    }
    std::sort(tokens.begin(), tokens.end());
    tokens.erase(std::unique(tokens.begin(), tokens.end()), tokens.end());
    return tokens;
  };

  auto const l = tokenize(lhs);
  auto const r = tokenize(rhs);
  if (l.empty() || r.empty())
    return false;
  return std::includes(l.begin(), l.end(), r.begin(), r.end()) || std::includes(r.begin(), r.end(), l.begin(), l.end());
}
}  // namespace search
