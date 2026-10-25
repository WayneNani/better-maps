package app.organicmaps.transit.ui;

import android.content.Context;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TimePicker;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.R;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.ChipGroup;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Choice of departure or arrival time (today and the next two days). */
public final class TransitTimeDialog
{
  public interface Listener
  {
    /** @param time null for "now". */
    void onTimeSelected(@Nullable Instant time, boolean arriveBy);
  }

  private static final int[] DAY_CHIPS = {R.id.transit_day_0, R.id.transit_day_1, R.id.transit_day_2};

  private TransitTimeDialog() {}

  public static void show(@NonNull Context context, @Nullable Instant time, boolean arriveBy,
                          @NonNull Listener listener)
  {
    final ZoneId zone = ZoneId.systemDefault();
    final LocalDate today = LocalDate.now(zone);
    final LocalDateTime selected = LocalDateTime.ofInstant(time != null ? time : Instant.now(), zone);

    final View view = LayoutInflater.from(context).inflate(R.layout.dialog_transit_time, null);
    final MaterialButtonToggleGroup mode = view.findViewById(R.id.transit_time_mode);
    mode.check(arriveBy ? R.id.transit_time_arrival : R.id.transit_time_departure);

    final ChipGroup days = view.findViewById(R.id.transit_time_day);
    final long dayOffset = Math.max(0, Math.min(2, ChronoUnit.DAYS.between(today, selected.toLocalDate())));
    days.check(DAY_CHIPS[(int) dayOffset]);

    final TimePicker picker = view.findViewById(R.id.transit_time_picker);
    picker.setIs24HourView(DateFormat.is24HourFormat(context));
    picker.setHour(selected.getHour());
    picker.setMinute(selected.getMinute());

    new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(R.string.transit_time_title)
        .setView(view)
        .setPositiveButton(
            android.R.string.ok,
            (d, which) -> {
              int day = 0;
              for (int i = 0; i < DAY_CHIPS.length; ++i)
                if (days.getCheckedChipId() == DAY_CHIPS[i])
                  day = i;
              final Instant result =
                  today.plusDays(day).atTime(picker.getHour(), picker.getMinute()).atZone(zone).toInstant();
              listener.onTimeSelected(result, mode.getCheckedButtonId() == R.id.transit_time_arrival);
            })
        .setNeutralButton(R.string.transit_now, (d, which) -> listener.onTimeSelected(null, false))
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }
}
