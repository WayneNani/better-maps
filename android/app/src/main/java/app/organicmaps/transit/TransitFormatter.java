package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.transit.TransitPlace;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Formats public transport times, delays and durations. Pure Java (no Android dependencies), so it can be unit
 * tested; localized fragments come from {@link TransitTexts}.
 * <p>
 * Times are shown in the time zone of the stop. If that differs from the device time zone at that moment, the
 * zone abbreviation is appended (e.g. "14:05 GMT"), so that times in other zones are not misread.
 */
public final class TransitFormatter
{
  /** Placeholder for an unknown time. */
  public static final String NO_TIME = "--:--";

  @NonNull
  private final Locale mLocale;
  @NonNull
  private final ZoneId mDeviceZone;
  @NonNull
  private final TransitTexts mTexts;
  @NonNull
  private final DateTimeFormatter mTimeFormat;
  @NonNull
  private final DateTimeFormatter mZoneFormat;
  @NonNull
  private final DateTimeFormatter mDateFormat;
  @NonNull
  private final DateTimeFormatter mWeekdayFormat;
  // Short weekday and date, e.g. "Fr., 14. Nov.", and with the year for another year.
  @NonNull
  private final DateTimeFormatter mShortDateFormat;
  @NonNull
  private final DateTimeFormatter mShortDateYearFormat;

  public TransitFormatter(@NonNull Locale locale, @NonNull ZoneId deviceZone, boolean is24Hour,
                          @NonNull TransitTexts texts)
  {
    this(locale, deviceZone, is24Hour, texts, defaultShortDatePattern(locale, false),
         defaultShortDatePattern(locale, true));
  }

  /**
   * @param shortDatePattern pattern of a short weekday and date, e.g. "EEE, d. MMM" for German; on Android the best
   *     pattern of the locale for the skeleton "EEEdMMM".
   * @param shortDateYearPattern the same with the year (skeleton "EEEdMMMy").
   */
  public TransitFormatter(@NonNull Locale locale, @NonNull ZoneId deviceZone, boolean is24Hour,
                          @NonNull TransitTexts texts, @NonNull String shortDatePattern,
                          @NonNull String shortDateYearPattern)
  {
    mShortDateFormat = DateTimeFormatter.ofPattern(shortDatePattern, locale);
    mShortDateYearFormat = DateTimeFormatter.ofPattern(shortDateYearPattern, locale);
    mLocale = locale;
    mDeviceZone = deviceZone;
    mTexts = texts;
    mTimeFormat = DateTimeFormatter.ofPattern(is24Hour ? "HH:mm" : "h:mm a", locale);
    mZoneFormat = DateTimeFormatter.ofPattern("z", locale);
    mDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
    mWeekdayFormat = DateTimeFormatter.ofPattern("EEE", locale);
  }

  @NonNull
  public TransitTexts getTexts()
  {
    return mTexts;
  }

  @NonNull
  public ZoneId getDeviceZone()
  {
    return mDeviceZone;
  }

  /** The zone of a stop, or the device zone if unknown or invalid. */
  @NonNull
  public ZoneId zoneOf(@Nullable String timeZone)
  {
    if (timeZone == null || timeZone.isEmpty())
      return mDeviceZone;
    try
    {
      return ZoneId.of(timeZone);
    }
    catch (DateTimeException e)
    {
      return mDeviceZone;
    }
  }

  /** Time of day in the stop's zone, with the zone appended if it differs from the device zone. */
  @NonNull
  public String formatTime(@Nullable Instant time, @Nullable String timeZone)
  {
    if (time == null)
      return NO_TIME;
    final ZoneId zone = zoneOf(timeZone);
    final String text = mTimeFormat.format(time.atZone(zone));
    if (zone.getRules().getOffset(time).equals(mDeviceZone.getRules().getOffset(time)))
      return text;
    return text + " " + mZoneFormat.format(time.atZone(zone));
  }

  /** Time of day in the device zone (e.g. for the selected query time). */
  @NonNull
  public String formatDeviceTime(@NonNull Instant time)
  {
    return mTimeFormat.format(time.atZone(mDeviceZone));
  }

  /** The date in the device zone if it is not the same day as {@code now}, otherwise null. */
  @Nullable
  public String formatDateIfNotToday(@NonNull Instant time, @NonNull Instant now)
  {
    final LocalDate date = time.atZone(mDeviceZone).toLocalDate();
    if (date.equals(now.atZone(mDeviceZone).toLocalDate()))
      return null;
    return mDateFormat.format(date);
  }

  /** Short weekday and date, e.g. "Fr., 14. Nov."; with the year if it is not the year of {@code today}. */
  @NonNull
  public String formatShortDate(@NonNull LocalDate date, @NonNull LocalDate today)
  {
    return (date.getYear() == today.getYear() ? mShortDateFormat : mShortDateYearFormat).format(date);
  }

  /** Weekday and date in the stop's zone, e.g. "Sun, Oct 25, 2026". */
  @NonNull
  public String formatDate(@NonNull Instant time, @Nullable String timeZone)
  {
    final ZonedDateTime date = time.atZone(zoneOf(timeZone));
    return mWeekdayFormat.format(date) + ", " + mDateFormat.format(date.toLocalDate());
  }

  /** Calendar days between two instants in the given zone, e.g. 1 for an arrival after midnight. */
  public long dayOffset(@NonNull Instant from, @NonNull Instant to, @Nullable String timeZone)
  {
    final ZoneId zone = zoneOf(timeZone);
    return ChronoUnit.DAYS.between(from.atZone(zone).toLocalDate(), to.atZone(zone).toLocalDate());
  }

  /** Duration rounded to whole minutes (at least 1 min for positive durations), e.g. "1 h 5 min". */
  @NonNull
  public String formatDuration(long seconds)
  {
    long minutes = Math.round(Math.max(0, seconds) / 60.0);
    if (minutes == 0 && seconds > 0)
      minutes = 1;
    if (minutes < 60)
      return mTexts.minutes(minutes);
    return mTexts.hoursMinutes(minutes / 60, minutes % 60);
  }

  @NonNull
  public String formatTransfers(int transfers)
  {
    return mTexts.transfers(Math.max(0, transfers));
  }

  /**
   * @param delaySeconds delay (negative if early), null if unknown.
   * @param realTime true if realtime data is available; without it, no delay is shown at all.
   */
  @NonNull
  public Delay delay(@Nullable Long delaySeconds, boolean realTime)
  {
    if (!realTime || delaySeconds == null)
      return Delay.UNKNOWN;
    final long minutes = Math.round(delaySeconds / 60.0);
    if (minutes == 0)
      return Delay.ON_TIME;
    return new Delay(minutes > 0 ? Delay.Status.LATE : Delay.Status.EARLY, minutes);
  }

  /** Display name of a place; replaces the MOTIS placeholders for the query start and destination. */
  @NonNull
  public String placeName(@NonNull TransitPlace place)
  {
    final String name = place.getName();
    if (!place.isStop())
    {
      if ("START".equals(name))
        return mTexts.start();
      if ("END".equals(name))
        return mTexts.destination();
    }
    return name;
  }

  /**
   * "Depart now", "Depart 14:05" (today), "Depart Fri, Nov 14, 09:30" (another day) or "Arrive by 14:05", in the
   * device time zone.
   */
  @NonNull
  public String formatQueryTime(@Nullable Instant time, boolean arriveBy, @NonNull Instant now)
  {
    if (time == null)
      return arriveBy ? mTexts.arriveBy(formatDeviceTime(now)) : mTexts.departNow();
    String text = formatDeviceTime(time);
    final LocalDate date = time.atZone(mDeviceZone).toLocalDate();
    final LocalDate today = now.atZone(mDeviceZone).toLocalDate();
    if (!date.equals(today))
      text = formatShortDate(date, today) + ", " + text;
    return arriveBy ? mTexts.arriveBy(text) : mTexts.departAt(text);
  }

  /** Fallback patterns for {@link #formatShortDate} without Android's localized date skeletons (e.g. in tests). */
  @NonNull
  static String defaultShortDatePattern(@NonNull Locale locale, boolean withYear)
  {
    if ("de".equals(locale.getLanguage()))
      return withYear ? "EEE, d. MMM y" : "EEE, d. MMM";
    if ("en".equals(locale.getLanguage()) && !"US".equals(locale.getCountry()) && !locale.getCountry().isEmpty())
      return withYear ? "EEE d MMM y" : "EEE d MMM";
    return withYear ? "EEE, MMM d, y" : "EEE, MMM d";
  }

  @NonNull
  Locale getLocale()
  {
    return mLocale;
  }

  /** A departure or arrival delay for display. */
  public static final class Delay
  {
    public enum Status
    {
      /** No realtime data: show nothing. */
      UNKNOWN,
      /** Realtime data says on time: show the time in green. */
      ON_TIME,
      /** Late: show "+n" in red. */
      LATE,
      /** Early: show "-n" in green. */
      EARLY
    }

    public static final Delay UNKNOWN = new Delay(Status.UNKNOWN, 0);
    public static final Delay ON_TIME = new Delay(Status.ON_TIME, 0);

    @NonNull
    private final Status mStatus;
    private final long mMinutes;

    Delay(@NonNull Status status, long minutes)
    {
      mStatus = status;
      mMinutes = minutes;
    }

    @NonNull
    public Status getStatus()
    {
      return mStatus;
    }

    public long getMinutes()
    {
      return mMinutes;
    }

    public boolean isLate()
    {
      return mStatus == Status.LATE;
    }

    /** "+3", "−1" (with a minus sign), or "" if on time or unknown. */
    @NonNull
    public String getText()
    {
      switch (mStatus)
      {
      case LATE: return "+" + mMinutes;
      case EARLY: return "−" + Math.abs(mMinutes);
      default: return "";
      }
    }

    @Override
    public boolean equals(Object o)
    {
      if (this == o)
        return true;
      if (!(o instanceof Delay))
        return false;
      final Delay that = (Delay) o;
      return mStatus == that.mStatus && mMinutes == that.mMinutes;
    }

    @Override
    public int hashCode()
    {
      return mStatus.hashCode() * 31 + Long.hashCode(mMinutes);
    }

    @NonNull
    @Override
    public String toString()
    {
      return mStatus + (mMinutes != 0 ? " " + mMinutes : "");
    }
  }
}
