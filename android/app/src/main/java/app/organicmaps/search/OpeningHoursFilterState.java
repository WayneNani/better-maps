package app.organicmaps.search;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.search.OpeningHoursFilter;
import app.organicmaps.sdk.search.QueryFilter;
import java.util.Locale;

/**
 * State of the "Open now" search filter chips.
 * <p>
 * The filter belongs to the search it was set for, it is not taken over silently into a new, independent search
 * (a place without opening hours like a recycling container would be hidden without notice):
 * <ul>
 *   <li>Changing the query to another one ({@link #onQueryChanged}) resets it. Typing on or deleting characters of
 *   the query is the same search ("Café" -> "Café Müller", "Caf"), an empty field or other words are a new search
 *   ("Wertstoffcontainer", another category).</li>
 *   <li>A newly opened search screen ({@link #onSearchOpened}) keeps it only for the same query, e.g. when the search
 *   is opened again from the results on the map.</li>
 * </ul>
 * {@link #SESSION} keeps it while the app process is alive, so a recreated search screen (rotation, back from the
 * map) keeps the filter of its query.
 * <p>
 * A filter recognized in the query ("Café offen") is not stored here, it is part of the query.
 */
public class OpeningHoursFilterState
{
  @NonNull
  public static final OpeningHoursFilterState SESSION = new OpeningHoursFilterState();

  private boolean mOpenNow;
  private boolean mIncludeUnknown;
  // The normalized query the chips were changed for, null if they are not set.
  @Nullable
  private String mQuery;

  public boolean isOpenNow()
  {
    return mOpenNow;
  }

  /**
   * @param query the query of the search the chip is changed for.
   */
  public void setOpenNow(boolean openNow, @NonNull String query)
  {
    mOpenNow = openNow;
    mQuery = normalize(query);
  }

  /**
   * @return true if places without (known) opening hours should be shown too. Makes sense only if
   * {@link #isOpenNow()} is true (or the filter comes from the query), but is kept independently to restore it when
   * the filter is enabled again for the same search.
   */
  public boolean isIncludeUnknown()
  {
    return mIncludeUnknown;
  }

  /**
   * @param query the query of the search the chip is changed for.
   */
  public void setIncludeUnknown(boolean includeUnknown, @NonNull String query)
  {
    mIncludeUnknown = includeUnknown;
    mQuery = normalize(query);
  }

  /** The text of the search field has changed: another search starts without the filter. */
  public void onQueryChanged(@Nullable String query)
  {
    if (mQuery != null && !isSameSearch(mQuery, normalize(query)))
      reset();
  }

  /**
   * A search screen is opened (not recreated).
   *
   * @param query its initial query, null or empty for a new search.
   */
  public void onSearchOpened(@Nullable String query)
  {
    if (mQuery != null && !mQuery.equals(normalize(query)))
      reset();
  }

  private void reset()
  {
    mOpenNow = false;
    mIncludeUnknown = false;
    mQuery = null;
  }

  @NonNull
  public OpeningHoursFilter getFilter()
  {
    return OpeningHoursFilter.of(mOpenNow, mIncludeUnknown);
  }

  /**
   * @param queryFilter filter recognized in the query ("Café offen"), it turns the "Open now" chip on.
   */
  public boolean isOpenNowChipChecked(@Nullable QueryFilter queryFilter)
  {
    return mOpenNow || queryFilter != null;
  }

  /**
   * @param queryFilter filter recognized in the query ("Café offen"). The search core applies it anyway,
   *                    the explicit filter is needed for the "Also without opening hours" chip.
   */
  @NonNull
  public OpeningHoursFilter getFilter(@Nullable QueryFilter queryFilter)
  {
    return OpeningHoursFilter.of(isOpenNowChipChecked(queryFilter), mIncludeUnknown);
  }

  @NonNull
  private static String normalize(@Nullable String query)
  {
    if (query == null)
      return "";
    return query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  /**
   * @param filtered the normalized query the filter was set for.
   * @param query    the normalized new query.
   * @return true if one query continues the other one: typing on or deleting characters at the end.
   */
  private static boolean isSameSearch(@NonNull String filtered, @NonNull String query)
  {
    if (query.isEmpty())
      return false;
    return query.startsWith(filtered) || filtered.startsWith(query);
  }
}
