package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Dates that can be chosen for a public transport query, in the device time zone. Pure Java, so it can be unit
 * tested; used by the time dialog and the connections presenter.
 * <p>
 * No day before today can be chosen, and at most {@link #MAX_DAYS_AHEAD} days ahead. Transitous (MOTIS) answers any
 * date, but only has timetables as far as the feeds reach: often a few weeks or months (Berlin in October 2026: until
 * February 2027). Beyond that the answer is simply empty, so an empty result for a query more than
 * {@link #TIMETABLE_HINT_DAYS} days ahead gets a hint (see {@link #isFarAhead}).
 */
public final class TransitDateRange
{
  /** The last day that can be chosen, counted from today. */
  public static final int MAX_DAYS_AHEAD = 365;
  /** An empty result for a query further ahead than this probably means the timetables are not published yet. */
  public static final int TIMETABLE_HINT_DAYS = 14;

  /** The quick choices of the dialog. */
  public enum Day
  {
    TODAY,
    TOMORROW,
    /** Any other date, chosen in the calendar. */
    OTHER
  }

  @NonNull
  private final ZoneId mZone;
  @NonNull
  private final Instant mNow;
  @NonNull
  private final LocalDate mToday;

  public TransitDateRange(@NonNull Instant now, @NonNull ZoneId zone)
  {
    mZone = zone;
    mNow = now;
    mToday = now.atZone(zone).toLocalDate();
  }

  @NonNull
  public ZoneId getZone()
  {
    return mZone;
  }

  @NonNull
  public LocalDate getToday()
  {
    return mToday;
  }

  /** The first day that can be chosen: today. */
  @NonNull
  public LocalDate getMinDate()
  {
    return mToday;
  }

  /** The last day that can be chosen. */
  @NonNull
  public LocalDate getMaxDate()
  {
    return mToday.plusDays(MAX_DAYS_AHEAD);
  }

  /** The date limited to the days that can be chosen. */
  @NonNull
  public LocalDate clamp(@NonNull LocalDate date)
  {
    if (date.isBefore(getMinDate()))
      return getMinDate();
    if (date.isAfter(getMaxDate()))
      return getMaxDate();
    return date;
  }

  /** The date shown for a query time when the dialog opens (today for "now"). */
  @NonNull
  public LocalDate initialDate(@Nullable Instant time)
  {
    return time == null ? mToday : clamp(time.atZone(mZone).toLocalDate());
  }

  /** The time of day shown for a query time when the dialog opens (the current time for "now"). */
  @NonNull
  public LocalTime initialTime(@Nullable Instant time)
  {
    final Instant t = time != null ? time : mNow;
    return t.atZone(mZone).toLocalTime().truncatedTo(ChronoUnit.MINUTES);
  }

  @NonNull
  public Day dayOf(@NonNull LocalDate date)
  {
    if (date.equals(mToday))
      return Day.TODAY;
    if (date.equals(mToday.plusDays(1)))
      return Day.TOMORROW;
    return Day.OTHER;
  }

  /** The date of a quick choice; null for {@link Day#OTHER}. */
  @Nullable
  public LocalDate dateOf(@NonNull Day day)
  {
    switch (day)
    {
    case TODAY: return mToday;
    case TOMORROW: return mToday.plusDays(1);
    default: return null;
    }
  }

  /**
   * The query time for a date and time of day in the device zone. The date is limited to the days that can be chosen.
   * A time skipped by a daylight saving change (e.g. 02:30 in spring) is moved forward by the gap.
   */
  @NonNull
  public Instant toInstant(@NonNull LocalDate date, int hour, int minute)
  {
    return LocalDateTime.of(clamp(date), LocalTime.of(hour, minute)).atZone(mZone).toInstant();
  }

  /** True if the query time is on a day before today; such a query is sent for now. */
  public boolean isPastDay(@Nullable Instant time)
  {
    return time != null && time.atZone(mZone).toLocalDate().isBefore(mToday);
  }

  /** True if the query time is so far ahead that the timetables may not be published yet. */
  public static boolean isFarAhead(@Nullable Instant time, @NonNull Instant now)
  {
    return time != null && time.isAfter(now.plus(TIMETABLE_HINT_DAYS, ChronoUnit.DAYS));
  }

  /** A date as the UTC midnight in milliseconds, as the Material date picker expects it. */
  public static long toPickerMillis(@NonNull LocalDate date)
  {
    return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
  }

  /** The date of a selection of the Material date picker (UTC midnight in milliseconds). */
  @NonNull
  public static LocalDate fromPickerMillis(long millis)
  {
    return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate();
  }
}
