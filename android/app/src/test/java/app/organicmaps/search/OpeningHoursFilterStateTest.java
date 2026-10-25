package app.organicmaps.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.organicmaps.sdk.search.OpeningHoursFilter;
import org.junit.Test;

public class OpeningHoursFilterStateTest
{
  @Test
  public void isOffByDefault()
  {
    final OpeningHoursFilterState state = new OpeningHoursFilterState();
    assertFalse(state.isOpenNow());
    assertFalse(state.isIncludeUnknown());
    assertEquals(OpeningHoursFilter.OFF, state.getFilter());
  }

  @Test
  public void openNowHidesUnknownByDefault()
  {
    final OpeningHoursFilterState state = new OpeningHoursFilterState();
    state.setOpenNow(true);
    assertEquals(OpeningHoursFilter.OPEN_NOW, state.getFilter());
  }

  @Test
  public void includeUnknown()
  {
    final OpeningHoursFilterState state = new OpeningHoursFilterState();
    state.setOpenNow(true);
    state.setIncludeUnknown(true);
    assertEquals(OpeningHoursFilter.OPEN_NOW_OR_UNKNOWN, state.getFilter());
  }

  @Test
  public void includeUnknownAloneDoesNotFilter()
  {
    final OpeningHoursFilterState state = new OpeningHoursFilterState();
    state.setIncludeUnknown(true);
    assertEquals(OpeningHoursFilter.OFF, state.getFilter());
  }

  @Test
  public void includeUnknownIsRestoredWhenFilterIsEnabledAgain()
  {
    final OpeningHoursFilterState state = new OpeningHoursFilterState();
    state.setOpenNow(true);
    state.setIncludeUnknown(true);
    state.setOpenNow(false);
    assertEquals(OpeningHoursFilter.OFF, state.getFilter());
    assertTrue(state.isIncludeUnknown());

    state.setOpenNow(true);
    assertEquals(OpeningHoursFilter.OPEN_NOW_OR_UNKNOWN, state.getFilter());
  }
}
