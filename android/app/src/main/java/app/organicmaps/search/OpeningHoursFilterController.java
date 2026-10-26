package app.organicmaps.search;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.R;
import app.organicmaps.sdk.search.OpeningHoursFilter;
import app.organicmaps.sdk.search.QueryFilter;
import app.organicmaps.util.UiUtils;
import com.google.android.material.chip.Chip;

/**
 * The "Open now" filter of the search results (layout search_opening_hours_filter.xml): the chips "Open now" and
 * "Also without opening hours", and the hint "12 places without opening hours hidden · Show".
 * <p>
 * Without the native search, so it can be tested on its own; the search screen feeds it the query and the number of
 * hidden places.
 */
public class OpeningHoursFilterController
{
  public interface Listener
  {
    /** The filter has changed, the search should run again with {@link #getFilter()}. */
    void onFilterChanged();

    /**
     * The "Open now" chip of a filter recognized in the query was unchecked.
     *
     * @param query the query without the filter words ("Café " for "Café offen"), it starts a new search.
     */
    void onQueryFilterRemoved(@NonNull String query);
  }

  public interface QuerySource
  {
    /** The current text of the search field. */
    @NonNull
    String getQuery();
  }

  @NonNull
  private final OpeningHoursFilterState mState;
  @NonNull
  private final QuerySource mQuerySource;
  @NonNull
  private final Listener mListener;
  @NonNull
  private final Chip mOpenNowChip;
  @NonNull
  private final Chip mIncludeUnknownChip;
  @NonNull
  private final View mHint;
  @NonNull
  private final TextView mHintText;

  // Opening hours filter recognized in the query, e.g. "Café offen", it's shown as the active "Open now" chip.
  @Nullable
  private QueryFilter mQueryFilter;
  // Chips are being updated programmatically, ignore their listeners.
  private boolean mUpdatingChips;

  /**
   * @param root a view containing the views of search_opening_hours_filter.xml.
   */
  public OpeningHoursFilterController(@NonNull View root, @NonNull OpeningHoursFilterState state,
                                      @NonNull QuerySource querySource, @NonNull Listener listener)
  {
    mState = state;
    mQuerySource = querySource;
    mListener = listener;
    mOpenNowChip = root.findViewById(R.id.filter_open_now);
    mIncludeUnknownChip = root.findViewById(R.id.filter_include_unknown_opening_hours);
    mHint = root.findViewById(R.id.opening_hours_filter_hint);
    mHintText = root.findViewById(R.id.opening_hours_filter_hint_text);
    updateChips();

    mOpenNowChip.setOnCheckedChangeListener((chip, isChecked) -> {
      if (mUpdatingChips)
        return;
      if (!isChecked && mQueryFilter != null)
      {
        // The filter comes from the query: remove the filter words, it starts a new search.
        mState.setOpenNow(false, mQuerySource.getQuery());
        mListener.onQueryFilterRemoved(mQueryFilter.getQuery());
        return;
      }
      mState.setOpenNow(isChecked, mQuerySource.getQuery());
      updateChips();
      mListener.onFilterChanged();
    });
    mIncludeUnknownChip.setOnCheckedChangeListener((chip, isChecked) -> {
      if (mUpdatingChips)
        return;
      mState.setIncludeUnknown(isChecked, mQuerySource.getQuery());
      mListener.onFilterChanged();
    });
    // The same as the "Also without opening hours" chip.
    mHint.setOnClickListener(v -> {
      mState.setIncludeUnknown(true, mQuerySource.getQuery());
      updateChips();
      mListener.onFilterChanged();
    });
  }

  /**
   * The text of the search field has changed.
   *
   * @param queryFilter the filter recognized in the new query, null if none.
   */
  public void onQueryChanged(@Nullable String query, @Nullable QueryFilter queryFilter)
  {
    mState.onQueryChanged(query);
    mQueryFilter = queryFilter;
    updateChips();
  }

  @NonNull
  public OpeningHoursFilter getFilter()
  {
    return mState.getFilter(mQueryFilter);
  }

  /**
   * Shows or hides the hint.
   *
   * @param count the number of places hidden by the "Open now" filter because of unknown opening hours.
   */
  public void setHiddenWithoutOpeningHours(int count)
  {
    final boolean show = OpeningHoursFilterHint.isShown(count);
    if (show)
    {
      mHintText.setText(OpeningHoursFilterHint.getMessage(mHint.getResources(), count));
      mHint.setContentDescription(OpeningHoursFilterHint.getContentDescription(mHint.getResources(), count));
    }
    UiUtils.showIf(show, mHint);
  }

  private void updateChips()
  {
    final boolean checked = mState.isOpenNowChipChecked(mQueryFilter);
    mUpdatingChips = true;
    mOpenNowChip.setChecked(checked);
    mIncludeUnknownChip.setChecked(mState.isIncludeUnknown());
    mOpenNowChip.setText(getOpenNowChipText(mOpenNowChip.getContext()));
    UiUtils.showIf(checked, mIncludeUnknownChip);
    mUpdatingChips = false;
  }

  @NonNull
  private String getOpenNowChipText(@NonNull Context context)
  {
    if (mQueryFilter == null || !mQueryFilter.hasOpeningHoursTime())
      return context.getString(R.string.open_now);

    // "Apotheke Sonntag" -> "Open Sun 12:00".
    final String time = DateUtils.formatDateTime(
        context, mQueryFilter.getOpeningHoursTimeMillis(),
        DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY | DateUtils.FORMAT_SHOW_TIME);
    return context.getString(R.string.search_filter_open_at, time);
  }
}
