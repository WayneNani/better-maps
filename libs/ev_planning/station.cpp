#include "ev_planning/station.hpp"

#include "indexer/mwm_set.hpp"

#include "base/assert.hpp"
#include "base/string_utils.hpp"

#include <algorithm>
#include <array>
#include <utility>

namespace ev
{
namespace
{
struct KnownProvider
{
  std::string_view m_key;
  std::string_view m_name;
  // Lowercase parts of OSM names that mean this provider.
  std::array<std::string_view, 4> m_patterns;
};

// Large German and European charging providers. Order matters: the first match wins.
std::array<KnownProvider, 24> constexpr kKnownProviders = {{
    {"ionity", "Ionity", {"ionity"}},
    {"enbw", "EnBW", {"enbw"}},
    {"tesla", "Tesla", {"tesla", "supercharger"}},
    {"aral", "Aral pulse", {"aral"}},
    {"ewe_go", "EWE Go", {"ewe go", "ewe-go", "ewego"}},
    {"fastned", "Fastned", {"fastned"}},
    {"allego", "Allego", {"allego"}},
    {"eon", "E.ON", {"e.on", "eon drive", "e-on"}},
    {"shell", "Shell Recharge", {"shell", "newmotion"}},
    {"totalenergies", "TotalEnergies", {"totalenergies", "total energies"}},
    {"mer", "Mer", {"mer germany", "mer deutschland", "mer "}},
    {"pfalzwerke", "Pfalzwerke", {"pfalzwerke"}},
    {"maingau", "Maingau", {"maingau"}},
    {"vattenfall", "Vattenfall", {"vattenfall", "incharge"}},
    {"lidl", "Lidl", {"lidl"}},
    {"kaufland", "Kaufland", {"kaufland"}},
    {"aldi", "Aldi", {"aldi"}},
    {"ikea", "IKEA", {"ikea"}},
    {"mcdonalds", "McDonald's", {"mcdonald"}},
    {"electra", "Electra", {"electra"}},
    {"atlante", "Atlante", {"atlante"}},
    {"ladenetz", "ladenetz.de", {"ladenetz"}},
    {"ecotap", "Ecotap", {"ecotap"}},
    {"autobahn_tank_rast", "Tank & Rast", {"tank & rast", "tank&rast", "tank und rast"}},
}};

// Legal forms and generic words dropped from unknown provider names.
std::array<std::string_view, 12> constexpr kNoiseWords = {"gmbh", "ag",  "se",   "kg",  "co.",  "co",
                                                          "&",    "mbh", "e.v.", "ltd", "inc.", "inc"};
}  // namespace

std::string DebugPrint(AmenityType type)
{
  return std::string(ToString(type));
}

std::string_view ToString(AmenityType type)
{
  switch (type)
  {
  case AmenityType::Playground: return "playground";
  case AmenityType::Toilets: return "toilets";
  case AmenityType::Food: return "food";
  case AmenityType::Shopping: return "shopping";
  }
  UNREACHABLE();
}

std::optional<AmenityType> AmenityTypeFromString(std::string_view s)
{
  for (auto const type : {AmenityType::Playground, AmenityType::Toilets, AmenityType::Food, AmenityType::Shopping})
    if (ToString(type) == s)
      return type;
  return {};
}

std::string DebugPrint(Amenity const & amenity)
{
  return "Amenity [ " + DebugPrint(amenity.m_type) + " " + amenity.m_kind + " \"" + amenity.m_name + "\" " +
         strings::to_string(static_cast<int>(amenity.m_distanceM)) + " m ]";
}

Provider NormalizeProvider(std::string_view name)
{
  std::string lower(name);
  strings::Trim(lower);
  if (lower.empty())
    return {};
  std::string const original = lower;
  strings::AsciiToLower(lower);
  // " " around the name, so that a pattern like "mer " also matches at the end.
  std::string const padded = lower + " ";

  for (auto const & known : kKnownProviders)
  {
    for (auto const & pattern : known.m_patterns)
      if (!pattern.empty() && padded.find(pattern) != std::string::npos)
        return {std::string(known.m_key), std::string(known.m_name)};
  }

  // Unknown provider: lowercase words without legal forms, e.g. "Stadtwerke Bonn GmbH" -> "stadtwerke bonn".
  std::string key;
  std::string displayName;
  auto const originalWords = strings::Tokenize(original, " ,");
  for (auto const & word : originalWords)
  {
    std::string lowerWord(word);
    strings::AsciiToLower(lowerWord);
    if (std::find(kNoiseWords.begin(), kNoiseWords.end(), lowerWord) != kNoiseWords.end())
      continue;
    if (!key.empty())
    {
      key += ' ';
      displayName += ' ';
    }
    key += lowerWord;
    displayName += word;
  }
  if (key.empty())
    return {lower, original};
  return {key, displayName};
}

std::string ChargingStation::GetKey() const
{
  std::string map;
  if (auto const info = m_featureId.m_mwmId.GetInfo())
    map = info->GetCountryName();
  return map + ":" + strings::to_string(m_featureId.m_index);
}

std::string ChargingStation::GetProviderName() const
{
  if (!m_providers.empty())
    return m_providers.front().m_name;
  return m_name;
}

bool ChargingStation::HasProvider(std::string_view key) const
{
  return std::any_of(m_providers.begin(), m_providers.end(), [key](Provider const & p) { return p.m_key == key; });
}

bool IsDcSocket(std::string_view socketType)
{
  return socketType == "type2_combo" || socketType == "chademo" || socketType == "type1_combo" ||
         socketType == "nacs" || socketType == "gb_dc" || socketType == "chaoji" || socketType == "mcs";
}

ChargingPower GetBestChargingPower(VehicleProfile const & profile, ChargeSocketDescriptors const & sockets)
{
  ChargingPower best;
  double bestEffectiveKw = 0.0;
  for (auto const & socket : sockets)
  {
    if (!profile.HasSocket(socket.type))
      continue;
    bool const isDc = IsDcSocket(socket.type);
    bool const isKnown = socket.power > 0.0;
    double const power = isKnown ? socket.power : (isDc ? kAssumedDcPowerKw : kAssumedAcPowerKw);
    double const vehicleMax = isDc ? profile.m_maxDcPowerKw : profile.m_maxAcPowerKw;
    double const effective = std::min(power, vehicleMax);
    if (effective > bestEffectiveKw || (effective == bestEffectiveKw && isKnown && !best.m_isPowerKnown))
    {
      bestEffectiveKw = effective;
      best = {power, isDc, isKnown};
    }
  }
  return best;
}
}  // namespace ev
