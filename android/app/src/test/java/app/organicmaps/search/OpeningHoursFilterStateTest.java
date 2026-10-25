package app.organicmaps.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.organicmaps.sdk.search.OpeningHoursFilter;
import app.organicmaps.sdk.search.QueryFilter;
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
  @Test
  public void queryFilterTurnsTheChipOn()
  {
    final OpeningHoursFilterState state = new OpeningHoursFilterState();
    final QueryFilter cafeOpen = new QueryFilter(OpeningHoursFilter.OPEN_NOW.toNative(), 0, "Café ");
    assertFalse(state.isOpenNowChipChecked(null));
    assertTrue(state.isOpenNowChipChecked(cafeOpen));
    assertEquals(OpeningHoursFilter.OFF, state.getFilter(null));
    assertEquals(OpeningHoursFilter.OPEN_NOW, state.getFilter(cafeOpen));
    // The chip state itself is not changed.
    assertFalse(state.isOpenNow());
  }

  @Test
  public void includeUnknownWorksWithQueryFilter()
  {
    final OpeningHoursFilterState state = new OpeningHoursFilterState();
    final QueryFilter pharmacySunday =
        new QueryFilter(OpeningHoursFilter.OPEN_NOW.toNative(), 1792929600L, "Apotheke ");
    state.setIncludeUnknown(true);
    assertEquals(OpeningHoursFilter.OPEN_NOW_OR_UNKNOWN, state.getFilter(pharmacySunday));
    assertEquals(OpeningHoursFilter.OFF, state.getFilter(null));
  }
}
