package app.organicmaps.sdk.search;

import android.content.Context;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.util.Language;
import app.organicmaps.sdk.util.concurrency.UiThread;
import java.nio.charset.StandardCharsets;
import org.chromium.base.ObserverList;

public enum SearchEngine implements SearchListener, MapSearchListener,
                                    BookmarkSearchListener
{
  INSTANCE;

  public static final long ALL_BOOKMARK_CATEGORIES = -1L;

  // Query, which results are shown on the map.
  @Nullable
  private String mQuery;
  // better-maps: whether mQuery is a search category.
  private boolean mQueryIsCategory;

  @Override
  public void onResultsUpdate(@NonNull final SearchResult[] results, final long timestamp)
  {
    UiThread.run(() -> {
      for (SearchListener listener : mListeners)
        listener.onResultsUpdate(results, timestamp);
    });
  }

  @Override
  public void onResultsEnd(final long timestamp)
  {
    UiThread.run(() -> {
      for (SearchListener listener : mListeners)
        listener.onResultsEnd(timestamp);
    });
  }

  @Override
  public void onMapSearchResults(@NonNull final MapSearchListener.Result[] results, final long timestamp,
                                 final boolean isLast)
  {
    UiThread.run(() -> {
      for (MapSearchListener listener : mMapListeners)
        listener.onMapSearchResults(results, timestamp, isLast);
    });
  }

  @Override
  public void onBookmarkSearchResultsUpdate(@Nullable final long[] bookmarkIds, final long timestamp)
  {
    UiThread.run(() -> {
      for (BookmarkSearchListener listener : mBookmarkListeners)
        listener.onBookmarkSearchResultsUpdate(bookmarkIds, timestamp);
    });
  }

  @Override
  public void onBookmarkSearchResultsEnd(@Nullable final long[] bookmarkIds, final long timestamp)
  {
    UiThread.run(() -> {
      for (BookmarkSearchListener listener : mBookmarkListeners)
        listener.onBookmarkSearchResultsEnd(bookmarkIds, timestamp);
    });
  }

  private final ObserverList<SearchListener> mListeners = new ObserverList<>();

  private final ObserverList<MapSearchListener> mMapListeners = new ObserverList<>();

  private final ObserverList<BookmarkSearchListener> mBookmarkListeners = new ObserverList<>();

  public void addListener(SearchListener listener)
  {
    mListeners.addObserver(listener);
  }

  public void removeListener(SearchListener listener)
  {
    mListeners.removeObserver(listener);
  }

  public void addMapListener(MapSearchListener listener)
  {
    mMapListeners.addObserver(listener);
  }

  public void removeMapListener(MapSearchListener listener)
  {
    mMapListeners.removeObserver(listener);
  }

  public void addBookmarkListener(BookmarkSearchListener listener)
  {
    mBookmarkListeners.addObserver(listener);
  }

  public void removeBookmarkListener(BookmarkSearchListener listener)
  {
    mBookmarkListeners.removeObserver(listener);
  }

  /**
   *
   * @param context
   * @param timestamp Search results are filtered according to it after multiple requests.
   * @return whether search was actually started.
   */
  @MainThread
  public boolean search(@NonNull Context context, @NonNull String query, boolean isCategory, long timestamp,
                        boolean hasLocation, double lat, double lon)
  {
    return search(context, query, isCategory, timestamp, hasLocation, lat, lon, OpeningHoursFilter.OFF);
  }

  /**
   * @param openingHoursFilter filters POI results by their opening hours.
   */
  @MainThread
  public boolean search(@NonNull Context context, @NonNull String query, boolean isCategory, long timestamp,
                        boolean hasLocation, double lat, double lon, @NonNull OpeningHoursFilter openingHoursFilter)
  {
    return nativeRunSearch(query.getBytes(StandardCharsets.UTF_8), isCategory, Language.getKeyboardLocale(context),
                           timestamp, hasLocation, lat, lon, openingHoursFilter.toNative());
  }

  /**
   * @param openingHoursFilter filters POI results by their opening hours, also for the following
   *                           viewport searches on map moves.
   */
  @MainThread
  public void searchInteractive(@NonNull String query, boolean isCategory, @NonNull String locale, long timestamp,
                                boolean isMapAndTable, boolean hasLocation, double lat, double lon,
                                @NonNull OpeningHoursFilter openingHoursFilter)
  {
    nativeRunInteractiveSearch(query.getBytes(StandardCharsets.UTF_8), isCategory, locale, timestamp, isMapAndTable,
                               hasLocation, lat, lon, openingHoursFilter.toNative());
  }

  @MainThread
  public void searchInteractive(@NonNull String query, boolean isCategory, @NonNull String locale, long timestamp,
                                boolean isMapAndTable, boolean hasLocation, double lat, double lon)
  {
    searchInteractive(query, isCategory, locale, timestamp, isMapAndTable, hasLocation, lat, lon,
                      OpeningHoursFilter.OFF);
  }

  @MainThread
  public void searchInteractive(@NonNull String query, boolean isCategory, @NonNull String locale, long timestamp,
                                boolean isMapAndTable, @NonNull OpeningHoursFilter openingHoursFilter)
  {
    searchInteractive(query, isCategory, locale, timestamp, isMapAndTable, false, 0, 0, openingHoursFilter);
  }

  @MainThread
  public void searchInteractive(@NonNull String query, boolean isCategory, @NonNull String locale, long timestamp,
                                boolean isMapAndTable)
  {
    searchInteractive(query, isCategory, locale, timestamp, isMapAndTable, OpeningHoursFilter.OFF);
  }

  @MainThread
  public void searchInteractive(@NonNull Context context, @NonNull String query, boolean isCategory, long timestamp,
                                boolean isMapAndTable, @NonNull OpeningHoursFilter openingHoursFilter)
  {
    searchInteractive(query, isCategory, Language.getKeyboardLocale(context), timestamp, isMapAndTable,
                      openingHoursFilter);
  }

  @MainThread
  public void searchInteractive(@NonNull Context context, @NonNull String query, boolean isCategory, long timestamp,
                                boolean isMapAndTable)
  {
    searchInteractive(context, query, isCategory, timestamp, isMapAndTable, OpeningHoursFilter.OFF);
  }

  @MainThread
  public static void searchMaps(@NonNull Context context, @NonNull String query, long timestamp)
  {
    nativeRunSearchMaps(query.getBytes(StandardCharsets.UTF_8), Language.getKeyboardLocale(context), timestamp);
  }

  @MainThread
  public boolean searchInBookmarks(@NonNull String query, long categoryId, long timestamp)
  {
    return nativeRunSearchInBookmarks(query.getBytes(StandardCharsets.UTF_8), categoryId, timestamp);
  }

  @MainThread
  public boolean searchInBookmarks(@NonNull String query, long timestamp)
  {
    return nativeRunSearchInBookmarks(query.getBytes(StandardCharsets.UTF_8), ALL_BOOKMARK_CATEGORIES, timestamp);
  }

  public void setQuery(@Nullable String query)
  {
    setQuery(query, false);
  }

  /**
   * @param isCategory true if the query is a search category (categorial request): the search screen opened again
   *                   from the map searches it as a category, too.
   */
  public void setQuery(@Nullable String query, boolean isCategory)
  {
    mQuery = query;
    mQueryIsCategory = isCategory && query != null && !query.isEmpty();
  }

  /** True if {@link #getQuery()} is a search category. */
  public boolean isQueryCategory()
  {
    return mQueryIsCategory;
  }

  @Nullable
  public String getQuery()
  {
    return mQuery;
  }

  @MainThread
  public void cancel()
  {
    cancelApiCall();
    cancelAllSearches();
  }

  @MainThread
  private static void cancelApiCall()
  {
    Framework.nativeClearApiPoints();
  }

  @MainThread
  public void cancelInteractiveSearch()
  {
    setQuery("");
    nativeCancelInteractiveSearch();
  }

  @MainThread
  private void cancelAllSearches()
  {
    setQuery("");
    nativeCancelAllSearches();
  }

  @MainThread
  public void showResult(int index)
  {
    setQuery("");
    nativeShowResult(index);
  }

  @MainThread
  public void updateViewportWithLastResults()
  {
    nativeUpdateViewportWithLastResults();
  }

  public void initialize()
  {
    nativeInit();
  }

  private native void nativeInit();

  /**
   * Recognizes opening hours filter words in the query, like the search does, e.g. "Café offen".
   * @return null if the query has no filter words.
   */
  @Nullable
  public static QueryFilter detectQueryFilter(@NonNull String query, @NonNull String locale)
  {
    return nativeDetectQueryFilter(query.getBytes(StandardCharsets.UTF_8), locale);
  }

  @Nullable
  private static native QueryFilter nativeDetectQueryFilter(byte[] bytes, String locale);

  /**
   * @param bytes utf-8 formatted bytes of query.
   * @param openingHoursFilter native value of {@link OpeningHoursFilter}.
   */
  private static native boolean nativeRunSearch(byte[] bytes, boolean isCategory, String language, long timestamp,
                                                boolean hasLocation, double lat, double lon, int openingHoursFilter);

  /**
   * @param bytes utf-8 formatted query bytes
   * @param openingHoursFilter native value of {@link OpeningHoursFilter}.
   */
  private static native void nativeRunInteractiveSearch(byte[] bytes, boolean isCategory, String language,
                                                        long timestamp, boolean isMapAndTable, boolean hasLocation,
                                                        double lat, double lon, int openingHoursFilter);

  /**
   * @param bytes utf-8 formatted query bytes
   */
  private static native void nativeRunSearchMaps(byte[] bytes, String language, long timestamp);

  private static native boolean nativeRunSearchInBookmarks(byte[] bytes, long categoryId, long timestamp);

  private static native void nativeShowResult(int index);

  private static native void nativeCancelInteractiveSearch();

  private static native void nativeCancelEverywhereSearch();

  private static native void nativeCancelAllSearches();

  private static native void nativeUpdateViewportWithLastResults();
}
