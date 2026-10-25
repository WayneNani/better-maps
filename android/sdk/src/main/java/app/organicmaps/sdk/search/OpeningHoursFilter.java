package app.organicmaps.sdk.search;

import androidx.annotation.NonNull;

/**
 * Filters POI search results by their opening hours. Other results (streets, addresses, cities, etc.)
 * are never filtered. Must be in sync with search::OpeningHoursFilter in libs/search/opening_hours_filter.hpp.
 */
public enum OpeningHoursFilter
{
  /** Do not filter. */
  OFF(0),
  /** Show only POIs which are open now. */
  OPEN_NOW(1),
  /** Show POIs which are open now or have unknown opening hours. */
  OPEN_NOW_OR_UNKNOWN(2);

  private final int mNativeValue;

  OpeningHoursFilter(int nativeValue)
  {
    mNativeValue = nativeValue;
  }

  public int toNative()
  {
    return mNativeValue;
  }

  @NonNull
  public static OpeningHoursFilter fromNative(int nativeValue)
  {
    for (OpeningHoursFilter filter : values())
      if (filter.mNativeValue == nativeValue)
        return filter;
    throw new IllegalArgumentException("Unknown opening hours filter " + nativeValue);
  }

  /**
   * @param openNow show only POIs which are open now.
   * @param includeUnknown also show POIs with unknown opening hours, ignored if {@code openNow} is false.
   */
  @NonNull
  public static OpeningHoursFilter of(boolean openNow, boolean includeUnknown)
  {
    if (!openNow)
      return OFF;
    return includeUnknown ? OPEN_NOW_OR_UNKNOWN : OPEN_NOW;
  }
}
