package app.organicmaps.sdk.search;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

/**
 * Native search will return results via this interface.
 */
public interface SearchListener
{
  /**
   * @param results   Search results.
   * @param timestamp Timestamp of search request.
   */
  // Called by JNI.
  @Keep
  @SuppressWarnings("unused")
  default void onResultsUpdate(@NonNull SearchResult[] results, long timestamp)
  {}

  /**
   * @param timestamp Timestamp of search request.
   */
  default void onResultsEnd(long timestamp) {}

  /**
   * The search has ended.
   *
   * @param timestamp                 Timestamp of search request.
   * @param hiddenWithoutOpeningHours The number of places which match the query, but are hidden by
   *                                  {@link OpeningHoursFilter#OPEN_NOW} because their opening hours are unknown.
   *                                  {@link OpeningHoursFilter#OPEN_NOW_OR_UNKNOWN} shows them.
   */
  // Called by JNI.
  @Keep
  @SuppressWarnings("unused")
  default void onResultsEnd(long timestamp, int hiddenWithoutOpeningHours)
  {
    onResultsEnd(timestamp);
  }
}
