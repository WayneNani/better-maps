package app.organicmaps.transit.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.ColorInt;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import app.organicmaps.R;
import app.organicmaps.transit.AlternativesItem;
import app.organicmaps.transit.ChangeItem;
import app.organicmaps.transit.LegAlternatives;
import app.organicmaps.transit.TransitConnectionsPresenter;
import app.organicmaps.transit.TransitError;
import app.organicmaps.util.ThemeUtils;
import app.organicmaps.util.UiUtils;
import java.util.List;

/** Texts, colors and chips of the other departures of a ride, shared by the detail view, the list and the grid. */
final class AlternativesViews
{
  private AlternativesViews() {}

  /** "14 min buffer", "4 min, tight", "Connection lost" or "arr. 09:39". */
  @NonNull
  static String status(@NonNull Context context, @NonNull AlternativesItem.Chip chip)
  {
    if (chip.isPending())
      return context.getString(R.string.transit_alt_searching);
    switch (chip.getStatus())
    {
    case COMFORTABLE: return context.getString(R.string.transit_alt_buffer, (int) chip.getBufferMinutes());
    case TIGHT: return context.getString(R.string.transit_alt_tight, (int) Math.max(0, chip.getBufferMinutes()));
    case MISSED: return context.getString(R.string.transit_alt_missed);
    default: return context.getString(R.string.transit_alt_arrival, chip.getArrival());
    }
  }

  @ColorInt
  static int statusColor(@NonNull Context context, @NonNull AlternativesItem.Chip chip)
  {
    switch (chip.getStatus())
    {
    case COMFORTABLE: return ContextCompat.getColor(context, R.color.transit_alt_ok);
    case TIGHT:
    case MISSED: return ContextCompat.getColor(context, R.color.transit_alt_tight);
    default: return ThemeUtils.getColor(context, android.R.attr.textColorSecondary);
    }
  }

  /** E.g. "16 at 09:09 from Olof-Palme-Allee, 14 min buffer". */
  @NonNull
  static String description(@NonNull Context context, @NonNull AlternativesItem.Chip chip,
                            @Nullable String nextLine)
  {
    final String line = chip.getBadge().getText();
    String text;
    if (chip.missesNext() && nextLine != null)
      text = context.getString(R.string.transit_alt_chip_missed, line, chip.getDeparture(), chip.getBoarding(),
                               nextLine);
    else
      text = context.getString(R.string.transit_alt_chip, line, chip.getDeparture(), chip.getBoarding(),
                               status(context, chip));
    if (chip.isSelected())
      text += ", " + context.getString(R.string.transit_alt_selected);
    return text;
  }

  /** "first ride", "ride 2", "last ride"; capitalized for titles. */
  @NonNull
  static String rideName(@NonNull Context context, @NonNull LegAlternatives.Position position, int number,
                         boolean title)
  {
    switch (position)
    {
    case FIRST:
    case ONLY: return context.getString(title ? R.string.transit_ride_first_title : R.string.transit_ride_first);
    case LAST: return context.getString(title ? R.string.transit_ride_last_title : R.string.transit_ride_last);
    default:
      return context.getString(title ? R.string.transit_ride_number_title : R.string.transit_ride_number, number);
    }
  }

  /** "Changed: other first ride, same connection". */
  @NonNull
  static String changed(@NonNull Context context, @NonNull ChangeItem change)
  {
    final String ride = rideName(context, change.getPosition(), change.getRideNumber(), false);
    if (change.isReplanned())
      return context.getString(R.string.transit_changed_replanned, ride);
    if (change.getPosition().hasNext())
      return context.getString(R.string.transit_changed_same, ride);
    return context.getString(R.string.transit_changed, ride);
  }

  /** "ICE 1022 no longer reachable · rest searched again, arrival 10:30 (+24 min)". */
  @NonNull
  static String banner(@NonNull Context context, @NonNull ChangeItem change)
  {
    final String missed = change.getMissedLine() != null ? change.getMissedLine() : "";
    if (change.getDelayMinutes() == 0)
      return context.getString(R.string.transit_alt_banner, missed, change.getArrival());
    return context.getString(R.string.transit_alt_banner_delay, missed, change.getArrival(),
                             context.getString(R.string.transit_alt_delay, (int) change.getDelayMinutes()));
  }

  /** "Other departures to the ICE 1022" or "Earlier and later departures". */
  @NonNull
  static String title(@NonNull Context context, @NonNull AlternativesItem alternatives)
  {
    if (alternatives.getNextLine() != null)
      return context.getString(R.string.transit_alt_title_next, alternatives.getNextLine());
    return context.getString(R.string.transit_alt_title_around);
  }

  /** Loading, error or "nothing found" below the chips; null if there is nothing to say. */
  @Nullable
  static String message(@NonNull Context context, @NonNull AlternativesItem alternatives)
  {
    if (alternatives.isLoading())
      return context.getString(R.string.transit_alt_loading);
    if (alternatives.getError() != null)
      return context.getString(alternatives.getError() == TransitError.NO_RESULTS
                                   ? R.string.transit_alt_none
                                   : TransitViews.errorText(alternatives.getError()));
    if (alternatives.isReplanning())
      return context.getString(R.string.transit_alt_replanning);
    if (alternatives.getRestError() != null)
      return context.getString(alternatives.getRestError() == TransitError.NO_RESULTS
                                   ? R.string.transit_alt_rest_none
                                   : TransitViews.errorText(alternatives.getRestError()));
    if (alternatives.isEmpty())
      return context.getString(R.string.transit_alt_none);
    return null;
  }

  /**
   * Fills a row with one view per departure ({@code layout} with alt_time, alt_badge and alt_status); a tap uses it.
   *
   * @param compact the list: the status is only shown for departures that miss the next ride.
   */
  static void bindChips(@NonNull ViewGroup container, @NonNull AlternativesItem alternatives, @LayoutRes int layout,
                        boolean compact, @NonNull TransitConnectionsPresenter presenter)
  {
    final Context context = container.getContext();
    final List<AlternativesItem.Chip> chips = alternatives.getChips();
    final LayoutInflater inflater = LayoutInflater.from(context);
    while (container.getChildCount() > chips.size())
      container.removeViewAt(container.getChildCount() - 1);
    while (container.getChildCount() < chips.size())
      container.addView(inflater.inflate(layout, container, false));
    for (int i = 0; i < chips.size(); ++i)
    {
      final AlternativesItem.Chip chip = chips.get(i);
      final View view = container.getChildAt(i);
      final TextView time = view.findViewById(R.id.alt_time);
      final TextView badge = view.findViewById(R.id.alt_badge);
      final TextView status = view.findViewById(R.id.alt_status);
      time.setText(chip.getDeparture());
      time.setTextColor(chip.missesNext() ? ThemeUtils.getColor(context, android.R.attr.textColorSecondary)
                                          : ThemeUtils.getColor(context, android.R.attr.textColorPrimary));
      TransitViews.bindBadge(badge, chip.getBadge());
      badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      final boolean showStatus = !compact || chip.missesNext() || chip.isPending();
      UiUtils.showIf(showStatus, status);
      if (compact)
        UiUtils.showIf(!showStatus, badge);
      status.setText(status(context, chip));
      status.setTextColor(statusColor(context, chip));
      view.setBackgroundResource(chip.isSelected()       ? R.drawable.bg_transit_alt_chip_selected
                                 : chip.missesNext() ? R.drawable.bg_transit_alt_chip_missed
                                                     : R.drawable.bg_transit_alt_chip);
      view.setSelected(chip.isSelected());
      view.setContentDescription(description(context, chip, alternatives.getNextLine()));
      final int index = i;
      view.setOnClickListener(v -> presenter.chooseAlternative(index));
    }
  }
}
