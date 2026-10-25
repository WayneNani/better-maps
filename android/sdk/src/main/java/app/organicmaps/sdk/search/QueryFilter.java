package app.organicmaps.sdk.search;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

/**
 * Opening hours filter recognized in a search query, e.g. "Café offen" or "Apotheke Sonntag".
 * The search core applies it automatically, the UI uses it to show the active filter.
 * See search::DetectQueryFilters in libs/search/query_filters.hpp.
 */
public final class QueryFilter
{
  @NonNull
  private final OpeningHoursFilter mOpeningHoursFilter;
  private final long mOpeningHoursTimeSec;
  @NonNull
  private final String mQuery;

  /**
   * @param openingHoursFilter native value of {@link OpeningHoursFilter}.
   * @param openingHoursTimeSec moment (seconds since epoch) named in the query, or 0 for now.
   * @param query the query without the filter words.
   */
  // Called from JNI.
  @Keep
  public QueryFilter(int openingHoursFilter, long openingHoursTimeSec, @NonNull String query)
  {
    mOpeningHoursFilter = OpeningHoursFilter.fromNative(openingHoursFilter);
    mOpeningHoursTimeSec = openingHoursTimeSec;
    mQuery = query;
  }

  @NonNull
  public OpeningHoursFilter getOpeningHoursFilter()
  {
    return mOpeningHoursFilter;
  }

  /**
   * @return true if the query names a day or a time ("Sonntag", "um 20 Uhr"), see {@link #getOpeningHoursTimeMillis()}.
   */
  public boolean hasOpeningHoursTime()
  {
    return mOpeningHoursTimeSec != 0;
  }

  public long getOpeningHoursTimeMillis()
  {
    return mOpeningHoursTimeSec * 1000;
  }

  /**
   * @return the query without the filter words, to remove the filter from the query.
   */
  @NonNull
  public String getQuery()
  {
    return mQuery;
  }
}
