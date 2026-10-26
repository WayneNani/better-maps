#include "i18n/get_text_by_id.hpp"

#include "coding/reader.hpp"

#include "platform/platform.hpp"

#include "cppjansson/cppjansson.hpp"

#include "i18n/country_language.hpp"
#include "i18n/localisation.hpp"

#include "base/assert.hpp"
#include "base/exception.hpp"
#include "base/file_name_utils.hpp"
#include "base/logging.hpp"

#include <algorithm>
#include <map>
#include <mutex>
#include <optional>
#include <utility>

namespace platform
{
using std::string;
using namespace localisation;

namespace
{
string const kDefaultLanguage = "default";
string const kEnglishLanguage = "en";

string GetTextSourceString(platform::TextSource textSource)
{
  switch (textSource)
  {
  case platform::TextSource::TtsSound: return string("sound-strings");
  case platform::TextSource::Countries: return string("countries-strings");
  }
  ASSERT(false, ());
  return string();
}
}  // namespace

bool GetJsonBuffer(platform::TextSource textSource, string const & localeName, string & jsonBuffer)
{
  string const pathToJson = base::JoinPath(GetTextSourceString(textSource), localeName + ".json", "localize.json");

  try
  {
    jsonBuffer.clear();
    GetPlatform().GetReader(pathToJson)->ReadAsString(jsonBuffer);
  }
  catch (RootException const & ex)
  {
    LOG(LWARNING, ("Can't open", localeName, "localization file:", pathToJson, ex.what()));
    return false;  // No json file for localeName
  }
  return true;
}

TGetTextByIdPtr GetTextById::Create(string const & jsonBuffer, string const & localeName)
{
  TGetTextByIdPtr result(new GetTextById(jsonBuffer, localeName));
  if (!result->IsValid())
  {
    ASSERT(false, ("Can't create a GetTextById instance from a json file. localeName=", localeName));
    return nullptr;
  }
  return result;
}

namespace
{
// Returns the strings of |localeName| or nullptr if they are invalid, std::nullopt if there is no json file.
// The parsed files are kept: the names of regions are translated for every search result, and reading and parsing
// a json file (from the apk on Android) for every name was slower than the whole search ranking.
std::optional<std::shared_ptr<GetTextById const>> GetCachedTextById(TextSource textSource, string const & localeName)
{
  static std::mutex mutex;
  static std::map<std::pair<TextSource, string>, std::optional<std::shared_ptr<GetTextById const>>> cache;

  std::lock_guard lock(mutex);
  auto const [it, inserted] = cache.try_emplace({textSource, localeName});
  if (inserted)
  {
    string jsonBuffer;
    if (GetJsonBuffer(textSource, localeName, jsonBuffer))
      it->second = GetTextById::Create(jsonBuffer, localeName);
  }
  return it->second;
}
}  // namespace

std::shared_ptr<GetTextById const> GetTextByIdFactoryForRegion(TextSource textSource, string const regionId)
{
  for (LanguageIndex const languageIndex : PrioritizedMapLanguageIndexes(CountryLanguage::Instance().GetLocalLanguageIndexes(regionId)))
  {
    LanguageCode const languageCode = ConvertLanguageIndexToLanguageCode(languageIndex);
    if (languageIndex == kDefaultNameIndex)
    {
      for (auto const & localLanguageCode : CountryLanguage::Instance().GetLocalLanguageCodes(regionId))
        if (auto textById = GetCachedTextById(textSource, localLanguageCode))
          return *textById;
    }
    else if (auto textById = GetCachedTextById(textSource, languageCode))
    {
      return *textById;
    }
  }

  ASSERT(false, ("Can't find translation for region. (Region ID:", regionId, ")"));
  return nullptr;
}

TGetTextByIdPtr GetTextByIdFactory(TextSource textSource, string const & localeName)
{
  string jsonBuffer;
  if (GetJsonBuffer(textSource, localeName, jsonBuffer))
    return GetTextById::Create(jsonBuffer, localeName);

  if (GetJsonBuffer(textSource, kEnglishLanguage, jsonBuffer))
    return GetTextById::Create(jsonBuffer, kEnglishLanguage);

  ASSERT(false, ("Can't find translate for default language. (Lang:", localeName, ")"));
  return nullptr;
}

TGetTextByIdPtr ForTestingGetTextByIdFactory(string const & jsonBuffer, string const & localeName)
{
  return GetTextById::Create(jsonBuffer, localeName);
}

GetTextById::GetTextById(string const & jsonBuffer, string const & localeName) : m_locale(localeName)
{
  if (jsonBuffer.empty())
  {
    ASSERT(false, ("No json files found."));
    return;
  }

  base::Json root(jsonBuffer.c_str());
  if (root.get() == nullptr)
  {
    ASSERT(false, ("Cannot parse the json file."));
    return;
  }

  char const * key = nullptr;
  json_t * value = nullptr;
  json_object_foreach(root.get(), key, value)
  {
    ASSERT(key, ());
    ASSERT(value, ());
    char const * const valueStr = json_string_value(value);
    ASSERT(valueStr, ());
    m_localeTexts[key] = valueStr;
  }
  ASSERT_EQUAL(m_localeTexts.size(), json_object_size(root.get()), ());
}

string GetTextById::operator()(string const & textId) const
{
  auto const textIt = m_localeTexts.find(textId);
  if (textIt == m_localeTexts.end())
    return string();
  return textIt->second;
}

TTranslations GetTextById::GetAllSortedTranslations() const
{
  TTranslations all;
  all.reserve(m_localeTexts.size());
  for (auto const & tr : m_localeTexts)
    all.emplace_back(tr.first, tr.second);
  using TValue = TTranslations::value_type;
  sort(all.begin(), all.end(), [](TValue const & v1, TValue const & v2) { return v1.second < v2.second; });
  return all;
}
}  // namespace platform
