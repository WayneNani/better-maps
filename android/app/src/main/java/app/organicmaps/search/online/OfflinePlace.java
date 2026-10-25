package app.organicmaps.search.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * An offline search result, reduced to what is needed to recognize the same place among the online results.
 * <p>
 * The MWM files of CoMaps don't contain OSM ids, so offline results usually have none and duplicates are found by
 * name and distance; an OSM reference is used when available.
 * <p>
 * {@link #getMatch()} tells how well the result answers the query, judged by the parts the offline search highlighted:
 * for "Marienplatz München" searched in Berlin, the Marienplatz in Berlin or a "Münchener Straße" match only a part
 * of the query and are no answer (see {@link OnlineSearchPolicy}).
 */
public final class OfflinePlace
{
  public enum Match
  {
    /** At least {@link #FULL_MATCH_SHARE} of the query is highlighted in name and address. */
    FULL,
    /** Only a part of the query is highlighted, e.g. one of two words. */
    PARTIAL,
    /**
     * Nothing highlighted: found by category or synonym ("Frühstück" → cafés) or by a fuzzy match ("Münchner Straße"
     * for "München"). Which one can only be told from the whole list, see {@link #countAnswers}.
     */
    UNHIGHLIGHTED
  }

  @NonNull
  private final String mName;
  private final double mLat;
  private final double mLon;
  @Nullable
  private final String mOsmRef;
  @NonNull
  private final Match mMatch;

  /** Share of the query's letters and digits that must be highlighted in a result to answer the whole query. */
  static final double FULL_MATCH_SHARE = 0.75;

  public OfflinePlace(@NonNull String name, double lat, double lon)
  {
    this(name, lat, lon, null, Match.FULL);
  }

  /**
   * @param osmRef OSM reference like "W259055080" (type letter and id), or null.
   * @param match how well the result matches the query, see {@link #match(String, int[], int[])}.
   */
  public OfflinePlace(@NonNull String name, double lat, double lon, @Nullable String osmRef, @NonNull Match match)
  {
    mName = name;
    mLat = lat;
    mLon = lon;
    mOsmRef = osmRef;
    mMatch = match;
  }

  /**
   * Judges an offline result by its highlighted parts (pairs of start and length in its name and address): FULL if at
   * least {@link #FULL_MATCH_SHARE} of the query's letters and digits are highlighted.
   */
  @NonNull
  public static Match match(@Nullable String query, @Nullable int[] nameRanges, @Nullable int[] addressRanges)
  {
    final int highlighted = highlightedLength(nameRanges) + highlightedLength(addressRanges);
    if (highlighted == 0)
      return Match.UNHIGHLIGHTED;
    int queryLength = 0;
    if (query != null)
      for (int i = 0; i < query.length(); ++i)
        if (Character.isLetterOrDigit(query.charAt(i)))
          ++queryLength;
    return highlighted >= FULL_MATCH_SHARE * queryLength ? Match.FULL : Match.PARTIAL;
  }

  /**
   * Number of results that answer the query: the FULL matches, and the unhighlighted ones only if no result has a
   * highlight at all (a category search like "Frühstück"). Next to highlighted results, unhighlighted ones are fuzzy
   * matches and don't count.
   */
  public static int countAnswers(@NonNull Iterable<OfflinePlace> places)
  {
    int full = 0;
    int unhighlighted = 0;
    int partial = 0;
    for (OfflinePlace place : places)
    {
      switch (place.getMatch())
      {
      case FULL -> ++full;
      case PARTIAL -> ++partial;
      case UNHIGHLIGHTED -> ++unhighlighted;
      }
    }
    return full + (full == 0 && partial == 0 ? unhighlighted : 0);
  }

  private static int highlightedLength(@Nullable int[] ranges)
  {
    if (ranges == null)
      return 0;
    int length = 0;
    for (int i = 1; i < ranges.length; i += 2)
      length += Math.max(0, ranges[i]);
    return length;
  }

  @NonNull
  public String getName()
  {
    return mName;
  }

  public double getLat()
  {
    return mLat;
  }

  public double getLon()
  {
    return mLon;
  }

  @Nullable
  public String getOsmRef()
  {
    return mOsmRef;
  }

  @NonNull
  public Match getMatch()
  {
    return mMatch;
  }

  @NonNull
  @Override
  public String toString()
  {
    return mName + " " + mLat + "," + mLon + " " + mMatch;
  }
}
