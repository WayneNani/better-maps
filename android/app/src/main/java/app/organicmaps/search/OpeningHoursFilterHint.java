package app.organicmaps.search;

import android.content.res.Resources;
import androidx.annotation.NonNull;
import app.organicmaps.R;

/**
 * The hint below the filter chips when the "Open now" filter hides places without (known) opening hours, e.g.
 * "12 places without opening hours hidden · Show". A tap on it turns "Also without opening hours" on.
 */
public final class OpeningHoursFilterHint
{
  /**
   * More hidden places are not counted exactly in the hint: the search shows only some of them after the tap anyway,
   * and a general query ("Cafe") counts hundreds of places of the whole region.
   */
  public static final int MAX_COUNT = 100;

  private OpeningHoursFilterHint() {}

  /**
   * @param hiddenWithoutOpeningHours the number of hidden places reported by the search, see
   *                                  {@link app.organicmaps.sdk.search.SearchListener#onResultsEnd(long, int)}.
   */
  public static boolean isShown(int hiddenWithoutOpeningHours)
  {
    return hiddenWithoutOpeningHours > 0;
  }

  /**
   * The message of the hint, without its action, e.g. "12 places without opening hours hidden", or "More than 100
   * places without opening hours hidden".
   */
  @NonNull
  public static String getMessage(@NonNull Resources resources, int hiddenWithoutOpeningHours)
  {
    if (hiddenWithoutOpeningHours > MAX_COUNT)
      return resources.getString(R.string.search_filter_hidden_without_opening_hours_many, MAX_COUNT);
    return resources.getQuantityString(R.plurals.search_filter_hidden_without_opening_hours, hiddenWithoutOpeningHours,
                                       hiddenWithoutOpeningHours);
  }

  /** The whole hint for accessibility, e.g. "12 places without opening hours hidden · Show". */
  @NonNull
  public static String getContentDescription(@NonNull Resources resources, int hiddenWithoutOpeningHours)
  {
    return getMessage(resources, hiddenWithoutOpeningHours) + " · "
  + resources.getString(R.string.search_filter_show_hidden);
  }
}
