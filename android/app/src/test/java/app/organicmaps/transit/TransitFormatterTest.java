package app.organicmaps.transit;

import static app.organicmaps.sdk.transit.TransitTestData.t;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.organicmaps.sdk.transit.TransitTestData;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import org.junit.Test;

public class TransitFormatterTest
{
  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  private final TransitFormatter mFormatter = new TransitFormatter(Locale.GERMANY, BERLIN, true, new FakeTexts());

  @Test
  public void timeInStopZone()
  {
    // Winter time (CET, +1) after the change on 2026-10-25.
    assertEquals("17:29", mFormatter.formatTime(t("2026-10-25T16:29:00Z"), "Europe/Berlin"));
    // Summer time (CEST, +2).
    assertEquals("14:00", mFormatter.formatTime(t("2026-07-01T12:00:00Z"), "Europe/Berlin"));
    // Same offset as the device: no zone shown.
    assertEquals("17:29", mFormatter.formatTime(t("2026-10-25T16:29:00Z"), "Europe/Paris"));
  }

  @Test
  public void timeInOtherZoneShowsZone()
  {
    final TransitFormatter english = new TransitFormatter(Locale.UK, BERLIN, true, new FakeTexts());
    final String london = english.formatTime(t("2026-10-25T16:29:00Z"), "Europe/London");
    assertTrue(london, london.startsWith("16:29 "));
    assertNotEquals("16:29", london);
    final String helsinki = english.formatTime(t("2026-10-25T16:29:00Z"), "Europe/Helsinki");
    assertTrue(helsinki, helsinki.startsWith("18:29 "));
  }

  @Test
  public void unknownOrInvalidZoneUsesDeviceZone()
  {
    assertEquals("17:29", mFormatter.formatTime(t("2026-10-25T16:29:00Z"), null));
    assertEquals("17:29", mFormatter.formatTime(t("2026-10-25T16:29:00Z"), ""));
    assertEquals("17:29", mFormatter.formatTime(t("2026-10-25T16:29:00Z"), "Mars/Olympus"));
    assertEquals(BERLIN, mFormatter.zoneOf("Not/AZone"));
  }

  @Test
  public void missingTime()
  {
    assertEquals(TransitFormatter.NO_TIME, mFormatter.formatTime(null, "Europe/Berlin"));
  }

  @Test
  public void twelveHourClock()
  {
    final TransitFormatter us = new TransitFormatter(Locale.US, ZoneId.of("America/New_York"), false, new FakeTexts());
    final String text = us.formatTime(t("2026-10-25T21:05:00Z"), "America/New_York");
    assertTrue(text, text.matches("5:05\\s?PM"));
  }

  @Test
  public void duration()
  {
    assertEquals("0 min", mFormatter.formatDuration(0));
    assertEquals("0 min", mFormatter.formatDuration(-5));
    assertEquals("1 min", mFormatter.formatDuration(20));
    assertEquals("1 min", mFormatter.formatDuration(89));
    assertEquals("2 min", mFormatter.formatDuration(90));
    assertEquals("59 min", mFormatter.formatDuration(59 * 60));
    assertEquals("1 h", mFormatter.formatDuration(3600));
    assertEquals("1 h 5 min", mFormatter.formatDuration(3900));
    assertEquals("25 h 1 min", mFormatter.formatDuration(25 * 3600 + 60));
  }

  @Test
  public void transfers()
  {
    assertEquals("direct", mFormatter.formatTransfers(0));
    assertEquals("1 transfer", mFormatter.formatTransfers(1));
    assertEquals("3 transfers", mFormatter.formatTransfers(3));
    assertEquals("direct", mFormatter.formatTransfers(-1));
  }

  @Test
  public void delay()
  {
    assertEquals(TransitFormatter.Delay.UNKNOWN, mFormatter.delay(120L, false));
    assertEquals(TransitFormatter.Delay.UNKNOWN, mFormatter.delay(null, true));
    assertEquals(TransitFormatter.Delay.ON_TIME, mFormatter.delay(0L, true));
    assertEquals(TransitFormatter.Delay.ON_TIME, mFormatter.delay(29L, true));
    assertEquals("", TransitFormatter.Delay.ON_TIME.getText());
    assertEquals("", TransitFormatter.Delay.UNKNOWN.getText());

    final TransitFormatter.Delay late = mFormatter.delay(180L, true);
    assertEquals(TransitFormatter.Delay.Status.LATE, late.getStatus());
    assertTrue(late.isLate());
    assertEquals(3, late.getMinutes());
    assertEquals("+3", late.getText());
    assertEquals("+1", mFormatter.delay(30L, true).getText());

    final TransitFormatter.Delay early = mFormatter.delay(-60L, true);
    assertEquals(TransitFormatter.Delay.Status.EARLY, early.getStatus());
    assertFalse(early.isLate());
    assertEquals("−1", early.getText());
  }

  @Test
  public void dayOffset()
  {
    assertEquals(0, mFormatter.dayOffset(t("2026-10-25T16:00:00Z"), t("2026-10-25T22:00:00Z"), "Europe/Berlin"));
    // 23:50 -> 00:10 local time.
    assertEquals(1, mFormatter.dayOffset(t("2026-10-25T22:50:00Z"), t("2026-10-25T23:10:00Z"), "Europe/Berlin"));
    // Same instants, but in London both are on the same day.
    assertEquals(0, mFormatter.dayOffset(t("2026-10-25T22:50:00Z"), t("2026-10-25T23:10:00Z"), "Europe/London"));
  }

  @Test
  public void placeNames()
  {
    assertEquals("Start", mFormatter.placeName(TransitTestData.place("START").build()));
    assertEquals("Destination", mFormatter.placeName(TransitTestData.place("END").build()));
    // A real stop called "END" keeps its name.
    assertEquals("END", mFormatter.placeName(TransitTestData.place("END").stop("x:1").build()));
    assertEquals("S+U Alexanderplatz", mFormatter.placeName(TransitTestData.place("S+U Alexanderplatz").build()));
  }

  @Test
  public void queryTime()
  {
    final Instant now = t("2026-10-25T16:00:00Z");
    assertEquals("Depart now", mFormatter.formatQueryTime(null, false, now));
    assertEquals("Arrive by 17:00", mFormatter.formatQueryTime(null, true, now));
    assertEquals("Depart 19:30", mFormatter.formatQueryTime(t("2026-10-25T18:30:00Z"), false, now));
    assertEquals("Arrive by 19:30", mFormatter.formatQueryTime(t("2026-10-25T18:30:00Z"), true, now));
    final String tomorrow = mFormatter.formatQueryTime(t("2026-10-26T08:00:00Z"), false, now);
    assertTrue(tomorrow, tomorrow.startsWith("Depart ") && tomorrow.endsWith(", 09:00") && tomorrow.contains("26"));
  }

  @Test
  public void dateOnlyIfNotToday()
  {
    final Instant now = t("2026-10-25T16:00:00Z");
    assertNull(mFormatter.formatDateIfNotToday(t("2026-10-25T22:59:00Z"), now));
    assertTrue(mFormatter.formatDateIfNotToday(t("2026-10-25T23:00:00Z"), now) != null);
  }

  @Test
  public void queryTimeOnAnotherDay()
  {
    final Instant now = t("2026-10-25T16:00:00Z");
    // Weekday and short date, then the time (device zone), like "Abfahrt Sa., 14. Nov., 09:30".
    assertEquals("Depart Mo., 26. Okt., 09:00", mFormatter.formatQueryTime(t("2026-10-26T08:00:00Z"), false, now));
    assertEquals("Depart Sa., 14. Nov., 09:30", mFormatter.formatQueryTime(t("2026-11-14T08:30:00Z"), false, now));
    assertEquals("Arrive by Sa., 14. Nov., 09:30", mFormatter.formatQueryTime(t("2026-11-14T08:30:00Z"), true, now));
    // After midnight in the device zone it is the next day.
    assertEquals("Depart So., 15. Nov., 00:30", mFormatter.formatQueryTime(t("2026-11-14T23:30:00Z"), false, now));
    // Another year: with the year.
    assertEquals("Depart Fr., 14. Mai 2027, 10:30", mFormatter.formatQueryTime(t("2027-05-14T08:30:00Z"), false, now));
  }

  @Test
  public void shortDateInEnglish()
  {
    final TransitFormatter us = new TransitFormatter(Locale.US, BERLIN, false, new FakeTexts());
    final java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 25);
    assertEquals("Sat, Nov 14", us.formatShortDate(java.time.LocalDate.of(2026, 11, 14), today));
    assertEquals("Fri, May 14, 2027", us.formatShortDate(java.time.LocalDate.of(2027, 5, 14), today));
    assertEquals("Depart Sat, Nov 14, 9:30 AM",
                 us.formatQueryTime(t("2026-11-14T08:30:00Z"), false, t("2026-10-25T16:00:00Z")));
    // Patterns from Android (DateFormat.getBestDateTimePattern) are used as given.
    final TransitFormatter uk =
        new TransitFormatter(Locale.UK, BERLIN, true, new FakeTexts(), "EEE d MMM", "EEE d MMM y");
    assertEquals("Sat 14 Nov", uk.formatShortDate(java.time.LocalDate.of(2026, 11, 14), today));
  }
}
