#include "search/query_filters.hpp"

#include "search/utils.hpp"

#include "indexer/categories_holder.hpp"
#include "indexer/search_string_utils.hpp"
#include "indexer/string_slice.hpp"

#include "base/string_utils.hpp"

#include <array>
#include <cctype>
#include <sstream>
#include <utility>
#include <vector>

#include "3party/ankerl/unordered_dense.h"

namespace search
{
using namespace std;

namespace
{
int constexpr kNoonHour = 12;

enum class WordKind
{
  // "offen", "geöffnet": opening hours filter in any query.
  Open,
  // "open", "offene", "geöffnete": opening hours filter for categorial queries only.
  OpenAmbiguous,
  // "jetzt", "now": only together with an open word ("jetzt offen", "open now").
  Now,
  // "heute", "morgen", "Sonntag", "sonntags".
  Day,
  // "am" ("am Sonntag"), "on" ("on sunday"): skipped before a day.
  DayPreposition,
  // "um" ("um 20 Uhr"), "bis", "at": skipped before a time.
  TimePreposition,
  // "uhr", "h": after an hour.
  TimeSuffix,
  // "am", "pm": after an hour.
  Meridiem,
};

struct WordInfo
{
  WordKind m_kind;
  // For days: day of week as in std::tm (0 is Sunday), or days from today if |m_relativeDay|.
  int m_day = 0;
  bool m_relativeDay = false;
};

string Normalize(string_view word)
{
  auto s = strings::ToUtf8(NormalizeAndSimplifyString(word));
  // Strip punctuation around the word, but keep time separators inside ("20:30", "20.30").
  auto const isPunct = [](char c) { return string_view(",;.:!?()\"'").find(c) != string_view::npos; };
  while (!s.empty() && isPunct(s.back()))
    s.pop_back();
  size_t start = 0;
  while (start < s.size() && isPunct(s[start]))
    ++start;
  return s.substr(start);
}

class Vocabulary
{
public:
  static Vocabulary const & Instance()
  {
    static Vocabulary const vocabulary;
    return vocabulary;
  }

  WordInfo const * Find(string const & normalizedWord) const
  {
    auto const it = m_words.find(normalizedWord);
    return it == m_words.end() ? nullptr : &it->second;
  }

private:
  Vocabulary()
  {
    for (char const * w : {"offen", "geöffnet", "geoeffnet"})
      Add(w, {WordKind::Open});
    for (char const * w : {"open", "opened", "offene", "offener", "offenes", "offenen", "geöffnete", "geöffneter",
                           "geöffnetes", "geöffneten", "geoeffnete", "geoeffneter", "geoeffnetes", "geoeffneten"})
    {
      Add(w, {WordKind::OpenAmbiguous});
    }
    for (char const * w : {"jetzt", "gerade", "noch", "now", "currently", "still"})
      Add(w, {WordKind::Now});

    Add("heute", {WordKind::Day, 0, true});
    Add("today", {WordKind::Day, 0, true});
    Add("morgen", {WordKind::Day, 1, true});
    Add("tomorrow", {WordKind::Day, 1, true});
    Add("übermorgen", {WordKind::Day, 2, true});
    Add("uebermorgen", {WordKind::Day, 2, true});

    array<char const *, 7> constexpr kGermanDays = {"sonntag",    "montag",  "dienstag", "mittwoch",
                                                    "donnerstag", "freitag", "samstag"};
    array<char const *, 7> constexpr kEnglishDays = {"sunday",   "monday", "tuesday", "wednesday",
                                                     "thursday", "friday", "saturday"};
    for (int day = 0; day < 7; ++day)
    {
      for (string const w : {kGermanDays[day], kEnglishDays[day]})
      {
        Add(w, {WordKind::Day, day});
        // "sonntags", "sundays".
        Add(w + "s", {WordKind::Day, day});
      }
    }
    Add("sonnabend", {WordKind::Day, 6});
    Add("sonnabends", {WordKind::Day, 6});

    for (char const * w : {"am", "on"})
      Add(w, {WordKind::DayPreposition});
    for (char const * w : {"um", "ab", "bis", "gegen", "at", "from", "until", "till"})
      Add(w, {WordKind::TimePreposition});
    for (char const * w : {"uhr", "h"})
      Add(w, {WordKind::TimeSuffix});
    // "am" is a day preposition in German, it's checked as a meridiem after an hour only.
    Add("pm", {WordKind::Meridiem});
  }

  void Add(string_view word, WordInfo info) { m_words.emplace(Normalize(word), info); }

  ankerl::unordered_dense::map<string, WordInfo> m_words;
};

struct Word
{
  string m_original;
  string m_normalized;
  bool m_isFilter = false;
};

struct Time
{
  int m_hour = 0;
  int m_minute = 0;
};

// Parses "20", "20:30", "20.30", "8pm", "20uhr", "20h". |hasMarker| is set if the time is unambiguous by itself
// (has minutes or a suffix), |isPm|/|isAm| if it has such a suffix.
optional<Time> ParseTimeWord(string const & s, bool & hasMarker, bool & isAm, bool & isPm)
{
  hasMarker = isAm = isPm = false;
  size_t i = 0;
  auto const readNumber = [&s, &i](size_t maxDigits) -> optional<int>
  {
    size_t const start = i;
    while (i < s.size() && i - start < maxDigits && isdigit(static_cast<unsigned char>(s[i])))
      ++i;
    if (i == start)
      return {};
    return stoi(s.substr(start, i - start));
  };

  auto const hour = readNumber(2);
  if (!hour)
    return {};

  Time time{*hour, 0};
  if (i < s.size() && (s[i] == ':' || s[i] == '.'))
  {
    ++i;
    size_t const minuteStart = i;
    auto const minute = readNumber(2);
    if (!minute || i - minuteStart != 2)
      return {};
    time.m_minute = *minute;
    hasMarker = true;
  }

  string_view const suffix(s.data() + i, s.size() - i);
  if (suffix == "uhr" || suffix == "h")
    hasMarker = true;
  else if (suffix == "am")
    hasMarker = isAm = true;
  else if (suffix == "pm")
    hasMarker = isPm = true;
  else if (!suffix.empty())
    return {};

  if (time.m_hour > 24 || time.m_minute > 59)
    return {};
  return time;
}

bool ApplyMeridiem(Time & time, bool isAm, bool isPm)
{
  if (!isAm && !isPm)
    return true;
  if (time.m_hour < 1 || time.m_hour > 12)
    return false;
  if (isPm && time.m_hour < 12)
    time.m_hour += 12;
  if (isAm && time.m_hour == 12)
    time.m_hour = 0;
  return true;
}

class Parser
{
public:
  explicit Parser(vector<Word> & words) : m_words(words) {}

  void Parse()
  {
    size_t i = 0;
    while (i < m_words.size())
    {
      size_t consumed = ParseOpen(i);
      if (consumed == 0)
        consumed = ParseDay(i);
      if (consumed == 0)
        consumed = ParseTime(i);

      if (consumed == 0)
      {
        ++i;
        continue;
      }

      for (size_t j = i; j < i + consumed; ++j)
        m_words[j].m_isFilter = true;
      i += consumed;
    }
  }

  bool HasFilter() const { return m_hasFilter; }
  // True if all filter words are unambiguous, so they filter non-categorial queries too.
  bool IsUnambiguous() const { return !m_isAmbiguous; }

  optional<int> const & GetDay() const { return m_day; }
  bool IsRelativeDay() const { return m_relativeDay; }
  optional<Time> const & GetTime() const { return m_time; }

private:
  WordInfo const * Info(size_t i) const
  {
    return i < m_words.size() ? Vocabulary::Instance().Find(m_words[i].m_normalized) : nullptr;
  }

  bool Is(size_t i, WordKind kind) const
  {
    auto const * info = Info(i);
    return info && info->m_kind == kind;
  }

  size_t ParseOpen(size_t i)
  {
    // "offen", "jetzt offen", "geöffnet jetzt", "open now".
    size_t consumed = 0;
    bool hasNow = false;
    if (Is(i, WordKind::Now))
    {
      hasNow = true;
      ++consumed;
    }

    bool const isOpen = Is(i + consumed, WordKind::Open);
    bool const isOpenAmbiguous = Is(i + consumed, WordKind::OpenAmbiguous);
    if (isOpen || isOpenAmbiguous)
    {
      ++consumed;
      if (!hasNow && Is(i + consumed, WordKind::Now))
      {
        hasNow = true;
        ++consumed;
      }
      // "offen", "jetzt offen", "open now", but not "open" alone.
      if (!(isOpen || hasNow))
        m_isAmbiguous = true;
      m_hasFilter = true;
      return consumed;
    }

    // "jetzt" alone is not a filter, it may be a part of a name.
    return 0;
  }

  size_t ParseDay(size_t i)
  {
    size_t const start = Is(i, WordKind::DayPreposition) ? i + 1 : i;
    auto const * info = Info(start);
    if (!info || info->m_kind != WordKind::Day)
      return 0;

    m_day = info->m_day;
    m_relativeDay = info->m_relativeDay;
    m_isAmbiguous = true;
    m_hasFilter = true;
    return start + 1 - i;
  }

  size_t ParseTime(size_t i)
  {
    bool const hasPreposition = Is(i, WordKind::TimePreposition);
    size_t pos = hasPreposition ? i + 1 : i;
    if (pos >= m_words.size())
      return 0;

    bool hasMarker, isAm, isPm;
    auto time = ParseTimeWord(m_words[pos].m_normalized, hasMarker, isAm, isPm);
    if (!time)
      return 0;
    ++pos;

    bool const hasMeridiem = isAm || isPm;
    if (Is(pos, WordKind::TimeSuffix))
    {
      // "20 Uhr", "20:30 Uhr".
      hasMarker = true;
      ++pos;
    }
    else if (!hasMeridiem && Is(pos, WordKind::Meridiem))
    {
      // "8 pm".
      hasMarker = isPm = true;
      ++pos;
    }
    else if (!hasMeridiem && pos < m_words.size() && m_words[pos].m_normalized == "am" && !Is(pos + 1, WordKind::Day))
    {
      // "8 am", but not "8 am Sonntag".
      hasMarker = isAm = true;
      ++pos;
    }

    // A bare number may be a house number: "Torstraße 20".
    if (!hasMarker && !hasPreposition)
      return 0;
    if (!ApplyMeridiem(*time, isAm, isPm))
      return 0;

    m_time = time;
    m_isAmbiguous = true;
    m_hasFilter = true;
    return pos - i;
  }

  vector<Word> & m_words;
  bool m_hasFilter = false;
  bool m_isAmbiguous = false;
  optional<int> m_day;
  bool m_relativeDay = false;
  optional<Time> m_time;
};

optional<time_t> GetOpeningHoursTime(Parser const & parser, time_t now)
{
  auto const & day = parser.GetDay();
  auto const & time = parser.GetTime();
  if (!day && !time)
    return {};

  tm local;
  if (!localtime_r(&now, &local))
    return {};

  int dayOffset = 0;
  if (day)
    dayOffset = parser.IsRelativeDay() ? *day : (*day - local.tm_wday + 7) % 7;

  // Today without a time is now.
  if (dayOffset == 0 && !time)
    return {};

  tm result = local;
  result.tm_mday += dayOffset;
  result.tm_sec = 0;
  result.tm_isdst = -1;
  if (time)
  {
    // 24:00 is the end of the day.
    result.tm_hour = time->m_hour == 24 ? 23 : time->m_hour;
    result.tm_min = time->m_hour == 24 ? 59 : time->m_minute;
  }
  else
  {
    result.tm_hour = kNoonHour;
    result.tm_min = 0;
  }

  time_t moment = mktime(&result);
  if (moment == -1)
    return {};

  // A time without a day means the next such moment: "Restaurant 8 Uhr" late in the evening is for tomorrow.
  if (!day && moment < now)
  {
    result.tm_mday += 1;
    result.tm_isdst = -1;
    moment = mktime(&result);
  }
  return moment;
}
}  // namespace

string DebugPrint(QueryFilters const & filters)
{
  ostringstream os;
  os << "QueryFilters { m_query: \"" << filters.m_query
     << "\", m_openingHoursFilter: " << DebugPrint(filters.m_openingHoursFilter) << ", m_openingHoursTime: ";
  if (filters.m_openingHoursTime)
    os << *filters.m_openingHoursTime;
  else
    os << "now";
  os << " }";
  return os.str();
}

optional<QueryFilters> DetectQueryFilters(string const & query, time_t now, IsCategorialQueryFn const & isCategorial)
{
  vector<Word> words;
  strings::Tokenize(query, " \t", [&words](string_view w) { words.push_back({string(w), Normalize(w)}); });
  if (words.size() < 2)
    return {};

  Parser parser(words);
  parser.Parse();
  if (!parser.HasFilter())
    return {};

  QueryFilters filters;
  for (auto const & w : words)
  {
    if (w.m_isFilter)
      continue;
    if (!filters.m_query.empty())
      filters.m_query += ' ';
    filters.m_query += w.m_original;
  }

  if (filters.m_query.empty())
    return {};

  if (!parser.IsUnambiguous() && !isCategorial(filters.m_query))
    return {};

  // The last word of the rest is a complete word if the query has more words after it.
  if (words.back().m_isFilter || strings::IsASCIISpace(query.back()))
    filters.m_query += ' ';

  filters.m_openingHoursFilter = OpeningHoursFilter::OpenNow;
  filters.m_openingHoursTime = GetOpeningHoursTime(parser, now);
  return filters;
}

optional<QueryFilters> DetectQueryFilters(string const & query, time_t now, CategoriesHolder const & categories,
                                          Locales const & locales)
{
  return DetectQueryFilters(query, now, [&](string const & rest)
  {
    auto const tokens = NormalizeAndTokenizeString(rest);
    bool found = false;
    ForEachCategoryTypeFuzzy(StringSlice(tokens), locales, categories, [&found](size_t, uint32_t) { found = true; });
    return found;
  });
}
}  // namespace search
