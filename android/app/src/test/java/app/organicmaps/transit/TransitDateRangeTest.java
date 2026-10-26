package app.organicmaps.transit;

import static app.organicmaps.sdk.transit.TransitTestData.t;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.Test;

public class TransitDateRangeTest
{
  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  // Monday, 26 October 2026, 09:41:30 in Berlin (CET).
  private static final Instant NOW = t("2026-10-26T08:41:30Z");
  private final TransitDateRange mRange = new TransitDateRange(NOW, BERLIN);

  @Test
  public void fromTodayToOneYearAhead()
  {
    assertEquals(LocalDate.of(2026, 10, 26), mRange.getToday());
    assertEquals(LocalDate.of(2026, 10, 26), mRange.getMinDate());
    assertEquals(LocalDate.of(2027, 10, 26), mRange.getMaxDate());
  }

  @Test
  public void todayIsTheDayInTheDeviceZone()
  {
    // 23:30 UTC is already the next day in Berlin.
    final TransitDateRange lateNight = new TransitDateRange(t("2026-10-25T23:30:00Z"), BERLIN);
    assertEquals(LocalDate.of(2026, 10, 26), lateNight.getToday());
    final TransitDateRange newYork = new TransitDateRange(t("2026-10-25T23:30:00Z"), ZoneId.of("America/New_York"));
    assertEquals(LocalDate.of(2026, 10, 25), newYork.getToday());
  }

  @Test
  public void clampToTheChoosableDays()
  {
    assertEquals(mRange.getMinDate(), mRange.clamp(LocalDate.of(2026, 10, 1)));
    assertEquals(mRange.getMaxDate(), mRange.clamp(LocalDate.of(2030, 1, 1)));
    assertEquals(LocalDate.of(2026, 11, 14), mRange.clamp(LocalDate.of(2026, 11, 14)));
  }

  @Test
  public void initialDateAndTime()
  {
    // Now.
    assertEquals(mRange.getToday(), mRange.initialDate(null));
    assertEquals(LocalTime.of(9, 41), mRange.initialTime(null));
    // A chosen time, shown in the device zone.
    final Instant friday = t("2026-11-13T08:30:00Z");
    assertEquals(LocalDate.of(2026, 11, 13), mRange.initialDate(friday));
    assertEquals(LocalTime.of(9, 30), mRange.initialTime(friday));
    // A time chosen days ago starts at today; one too far ahead at the last day.
    assertEquals(mRange.getToday(), mRange.initialDate(t("2026-10-20T08:30:00Z")));
    assertEquals(mRange.getMaxDate(), mRange.initialDate(t("2028-01-01T08:30:00Z")));
  }

  @Test
  public void quickChoices()
  {
    assertEquals(TransitDateRange.Day.TODAY, mRange.dayOf(LocalDate.of(2026, 10, 26)));
    assertEquals(TransitDateRange.Day.TOMORROW, mRange.dayOf(LocalDate.of(2026, 10, 27)));
    assertEquals(TransitDateRange.Day.OTHER, mRange.dayOf(LocalDate.of(2026, 10, 28)));
    assertEquals(LocalDate.of(2026, 10, 26), mRange.dateOf(TransitDateRange.Day.TODAY));
    assertEquals(LocalDate.of(2026, 10, 27), mRange.dateOf(TransitDateRange.Day.TOMORROW));
    assertNull(mRange.dateOf(TransitDateRange.Day.OTHER));
  }

  @Test
  public void queryTimeInTheDeviceZone()
  {
    // Winter time (CET, +1).
    assertEquals(t("2026-11-13T08:30:00Z"), mRange.toInstant(LocalDate.of(2026, 11, 13), 9, 30));
    // Summer time (CEST, +2).
    assertEquals(t("2027-06-04T07:30:00Z"), mRange.toInstant(LocalDate.of(2027, 6, 4), 9, 30));
    // The day the clocks go back: 02:30 exists twice, the earlier (summer time) one is used.
    final TransitDateRange autumn = new TransitDateRange(t("2027-10-30T08:00:00Z"), BERLIN);
    assertEquals(t("2027-10-31T00:30:00Z"), autumn.toInstant(LocalDate.of(2027, 10, 31), 2, 30));
  }

  @Test
  public void skippedTimeMovesForward()
  {
    // 28 March 2027: 02:00 -> 03:00. 02:30 does not exist and becomes 03:30 CEST.
    assertEquals(t("2027-03-28T01:30:00Z"), mRange.toInstant(LocalDate.of(2027, 3, 28), 2, 30));
  }

  @Test
  public void queryTimeIsClamped()
  {
    assertEquals(t("2026-10-26T06:00:00Z"), mRange.toInstant(LocalDate.of(2026, 10, 1), 7, 0));
    assertEquals(t("2027-10-26T05:00:00Z"), mRange.toInstant(LocalDate.of(2029, 1, 1), 7, 0));
  }

  @Test
  public void pastDay()
  {
    assertFalse(mRange.isPastDay(null));
    // Earlier today is not a past day: connections of this morning can be looked up.
    assertFalse(mRange.isPastDay(t("2026-10-26T05:00:00Z")));
    assertFalse(mRange.isPastDay(t("2026-10-25T23:00:00Z")));
    assertTrue(mRange.isPastDay(t("2026-10-25T22:59:00Z")));
    assertFalse(mRange.isPastDay(t("2026-11-13T08:30:00Z")));
  }

  @Test
  public void farAheadAfterTwoWeeks()
  {
    assertFalse(TransitDateRange.isFarAhead(null, NOW));
    assertFalse(TransitDateRange.isFarAhead(t("2026-11-09T08:41:30Z"), NOW));
    assertTrue(TransitDateRange.isFarAhead(t("2026-11-09T08:42:00Z"), NOW));
    assertTrue(TransitDateRange.isFarAhead(t("2027-05-14T08:30:00Z"), NOW));
  }

  @Test
  public void pickerMillisAreUtcMidnight()
  {
    final LocalDate date = LocalDate.of(2026, 11, 14);
    assertEquals(t("2026-11-14T00:00:00Z").toEpochMilli(), TransitDateRange.toPickerMillis(date));
    assertEquals(date, TransitDateRange.fromPickerMillis(TransitDateRange.toPickerMillis(date)));
    // Independent of the device zone: a date far west or east of UTC stays the same date.
    assertEquals(LocalDate.of(2027, 3, 28),
                 TransitDateRange.fromPickerMillis(TransitDateRange.toPickerMillis(LocalDate.of(2027, 3, 28))));
  }
}
