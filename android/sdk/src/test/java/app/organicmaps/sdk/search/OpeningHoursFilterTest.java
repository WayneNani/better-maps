package app.organicmaps.sdk.search;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OpeningHoursFilterTest
{
  @Test
  public void nativeValuesMatchCore()
  {
    // Must be in sync with search::OpeningHoursFilter in libs/search/opening_hours_filter.hpp.
    assertEquals(0, OpeningHoursFilter.OFF.toNative());
    assertEquals(1, OpeningHoursFilter.OPEN_NOW.toNative());
    assertEquals(2, OpeningHoursFilter.OPEN_NOW_OR_UNKNOWN.toNative());
  }

  @Test
  public void ofDisabledIsOff()
  {
    assertEquals(OpeningHoursFilter.OFF, OpeningHoursFilter.of(false, false));
    // "Include unknown" alone doesn't enable the filter.
    assertEquals(OpeningHoursFilter.OFF, OpeningHoursFilter.of(false, true));
  }

  @Test
  public void ofOpenNow()
  {
    assertEquals(OpeningHoursFilter.OPEN_NOW, OpeningHoursFilter.of(true, false));
    assertEquals(OpeningHoursFilter.OPEN_NOW_OR_UNKNOWN, OpeningHoursFilter.of(true, true));
  }
}
