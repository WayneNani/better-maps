package app.organicmaps.search;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.search.OpeningHoursFilter;
import app.organicmaps.sdk.search.QueryFilter;

/**
 * State of the "Open now" search filter chips. {@link #SESSION} keeps it while the app process is alive,
 * so the filter is remembered between searches but is reset on the next app start.
 */
public class OpeningHoursFilterState
{
  @NonNull
  public static final OpeningHoursFilterState SESSION = new OpeningHoursFilterState();

  private boolean mOpenNow;
  private boolean mIncludeUnknown;

  public boolean isOpenNow()
  {
    return mOpenNow;
  }

  public void setOpenNow(boolean openNow)
  {
    mOpenNow = openNow;
  }

  /**
   * @return true if places without (known) opening hours should be shown too. Makes sense only if
   * {@link #isOpenNow()} is true, but is kept independently to restore it when the filter is enabled again.
   */
  public boolean isIncludeUnknown()
  {
    return mIncludeUnknown;
  }

  public void setIncludeUnknown(boolean includeUnknown)
  {
    mIncludeUnknown = includeUnknown;
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
}
