package app.organicmaps.transit.ui;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TimePicker;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import app.organicmaps.R;
import app.organicmaps.transit.TransitDateRange;
import app.organicmaps.transit.TransitFormatter;
import app.organicmaps.transit.TransitServices;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.CompositeDateValidator;
import com.google.android.material.datepicker.DateValidatorPointBackward;
import com.google.android.material.datepicker.DateValidatorPointForward;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Locale;

/**
 * Choice of departure or arrival time: today, tomorrow or any other date (calendar) from today up to
 * {@link TransitDateRange#MAX_DAYS_AHEAD} days ahead, and the time of day, in the device time zone.
 */
public final class TransitTimeDialog
{
  public interface Listener
  {
    /** @param time null for "now". */
    void onTimeSelected(@Nullable Instant time, boolean arriveBy);
  }

  @VisibleForTesting
  public static final String DATE_PICKER_TAG = "transit_date_picker";

  private TransitTimeDialog() {}

  public static void show(@NonNull Context context, @Nullable Instant time, boolean arriveBy,
                          @NonNull Listener listener)
  {
    final TransitDateRange range = new TransitDateRange(Instant.now(), ZoneId.systemDefault());
    final TransitFormatter formatter = TransitServices.createFormatter(context);
    final LocalTime initialTime = range.initialTime(time);

    final View view = LayoutInflater.from(context).inflate(R.layout.dialog_transit_time, null);
    final MaterialButtonToggleGroup mode = view.findViewById(R.id.transit_time_mode);
    mode.check(arriveBy ? R.id.transit_time_arrival : R.id.transit_time_departure);

    final DaySelection days = new DaySelection(view, range, formatter, range.initialDate(time));
    view.findViewById(R.id.transit_day_0).setOnClickListener(v -> days.select(range.getToday()));
    view.findViewById(R.id.transit_day_1).setOnClickListener(v -> days.select(range.getToday().plusDays(1)));
    view.findViewById(R.id.transit_day_other).setOnClickListener(v -> showDatePicker(context, range, days));

    final TimePicker picker = view.findViewById(R.id.transit_time_picker);
    picker.setIs24HourView(DateFormat.is24HourFormat(context));
    picker.setHour(initialTime.getHour());
    picker.setMinute(initialTime.getMinute());

    new MaterialAlertDialogBuilder(context)
        .setTitle(R.string.transit_time_title)
        .setView(view)
        .setPositiveButton(android.R.string.ok,
                           (d, which) -> {
                             final Instant result = range.toInstant(days.get(), picker.getHour(), picker.getMinute());
                             listener.onTimeSelected(result, mode.getCheckedButtonId() == R.id.transit_time_arrival);
                           })
        .setNeutralButton(R.string.transit_now, (d, which) -> listener.onTimeSelected(null, false))
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  /**
   * Pattern of the text input of the calendar (e.g. "dd.MM.yyyy" in German, "MM/dd/yyyy" in US English): the short
   * numeric date of the locale with a four-digit year.
   */
  @NonNull
  public static String textInputPattern(@NonNull Locale locale)
  {
    return DateFormat.getBestDateTimePattern(locale, "ddMMyyyy");
  }

  private static void showDatePicker(@NonNull Context context, @NonNull TransitDateRange range,
                                     @NonNull DaySelection days)
  {
    final FragmentActivity activity = findFragmentActivity(context);
    if (activity == null)
    {
      showPlatformDatePicker(context, range, days);
      return;
    }
    final FragmentManager fm = activity.getSupportFragmentManager();
    // A picker left from a recreated activity has lost its listeners.
    final Fragment stale = fm.findFragmentByTag(DATE_PICKER_TAG);
    if (stale instanceof MaterialDatePicker)
      ((MaterialDatePicker<?>) stale).dismissAllowingStateLoss();

    final long min = TransitDateRange.toPickerMillis(range.getMinDate());
    final long max = TransitDateRange.toPickerMillis(range.getMaxDate());
    final CalendarConstraints constraints =
        new CalendarConstraints.Builder()
            .setStart(TransitDateRange.toPickerMillis(range.getMinDate().withDayOfMonth(1)))
            .setEnd(max)
            .setOpenAt(TransitDateRange.toPickerMillis(days.get()))
            .setValidator(CompositeDateValidator.allOf(
                Arrays.asList(DateValidatorPointForward.from(min), DateValidatorPointBackward.before(max))))
            .build();
    final Locale locale = context.getResources().getConfiguration().getLocales().get(0);
    final MaterialDatePicker<Long> picker =
        MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.transit_choose_date)
            .setSelection(TransitDateRange.toPickerMillis(days.get()))
            .setCalendarConstraints(constraints)
            .setTextInputFormat(new SimpleDateFormat(textInputPattern(locale), locale))
            .build();
    picker.addOnPositiveButtonClickListener(selection -> {
      if (selection != null)
        days.select(TransitDateRange.fromPickerMillis(selection));
    });
    // Cancelled: the chips show the previous date again.
    picker.addOnDismissListener(d -> days.refresh());
    picker.show(fm, DATE_PICKER_TAG);
  }

  /** Fallback without a fragment manager. */
  private static void showPlatformDatePicker(@NonNull Context context, @NonNull TransitDateRange range,
                                             @NonNull DaySelection days)
  {
    final LocalDate date = days.get();
    final DatePickerDialog dialog =
        new DatePickerDialog(context,
                             (v, year, month, day)
                                 -> days.select(LocalDate.of(year, month + 1, day)),
                             date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth());
    final ZoneId zone = range.getZone();
    dialog.getDatePicker().setMinDate(range.getMinDate().atStartOfDay(zone).toInstant().toEpochMilli());
    dialog.getDatePicker().setMaxDate(range.getMaxDate().atStartOfDay(zone).toInstant().toEpochMilli());
    dialog.setOnDismissListener(d -> days.refresh());
    dialog.show();
  }

  @Nullable
  private static FragmentActivity findFragmentActivity(@NonNull Context context)
  {
    Context c = context;
    while (c instanceof ContextWrapper)
    {
      if (c instanceof FragmentActivity)
        return (FragmentActivity) c;
      c = ((ContextWrapper) c).getBaseContext();
    }
    return null;
  }

  /** The chosen date and its chips: "Today", "Tomorrow", or the other chip with the date (e.g. "Fri, Nov 14"). */
  private static final class DaySelection
  {
    @NonNull
    private final TransitDateRange mRange;
    @NonNull
    private final TransitFormatter mFormatter;
    @NonNull
    private final ChipGroup mGroup;
    @NonNull
    private final Chip mOther;
    @NonNull
    private LocalDate mDate;

    DaySelection(@NonNull View view, @NonNull TransitDateRange range, @NonNull TransitFormatter formatter,
                 @NonNull LocalDate date)
    {
      mRange = range;
      mFormatter = formatter;
      mGroup = view.findViewById(R.id.transit_time_day);
      mOther = view.findViewById(R.id.transit_day_other);
      mDate = date;
      refresh();
    }

    @NonNull
    LocalDate get()
    {
      return mDate;
    }

    void select(@NonNull LocalDate date)
    {
      mDate = mRange.clamp(date);
      refresh();
    }

    void refresh()
    {
      final TransitDateRange.Day day = mRange.dayOf(mDate);
      switch (day)
      {
      case TODAY: mGroup.check(R.id.transit_day_0); break;
      case TOMORROW: mGroup.check(R.id.transit_day_1); break;
      default: mGroup.check(R.id.transit_day_other); break;
      }
      if (day == TransitDateRange.Day.OTHER)
        mOther.setText(mFormatter.formatShortDate(mDate, mRange.getToday()));
      else
        mOther.setText(R.string.transit_other_date);
    }
  }
}
