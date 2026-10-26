package app.organicmaps.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.organicmaps.sdk.search.OpeningHoursFilter;
import app.organicmaps.sdk.search.QueryFilter;
import org.junit.Test;

public class OpeningHoursFilterStateTest
{
  private static final String CAFE = "Café";
  // The text of the "Café" category.
  private static final String CAFE_CATEGORY = "Café ";

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
    state.setOpenNow(true, CAFE);
    assertEquals(OpeningHoursFilter.OPEN_NOW, state.getFilter());
  }

  @Test
  public void includeUnknown()
  {
    final OpeningHoursFilterState state = new OpeningHoursFilterState();
    state.setOpenNow(true, CAFE);
    state.setIncludeUnknown(true, CAFE);
    assertEquals(OpeningHoursFilter.OPEN_NOW_OR_UNKNOWN, state.getFilter());
  }

  @Test
  public void includeUnknownAloneDoesNotFilter()
  {
    final OpeningHoursFilterState state = new OpeningHoursFilterState();
    state.setIncludeUnknown(true, CAFE);
    assertEquals(OpeningHoursFilter.OFF, state.getFilter());
  }

  @Test
  public void includeUnknownIsRestoredWhenFilterIsEnabledAgain()
  {
    final OpeningHoursFilterState state = new OpeningHoursFilterState();
    state.setOpenNow(true, CAFE);
    state.setIncludeUnknown(true, CAFE);
    state.setOpenNow(false, CAFE);
    assertEquals(OpeningHoursFilter.OFF, state.getFilter());
    assertTrue(state.isIncludeUnknown());

    state.setOpenNow(true, CAFE);
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
    state.setIncludeUnknown(true, "Apotheke Sonntag");
    assertEquals(OpeningHoursFilter.OPEN_NOW_OR_UNKNOWN, state.getFilter(pharmacySunday));
    assertEquals(OpeningHoursFilter.OFF, state.getFilter(null));
  }

  private static OpeningHoursFilterState openNowFor(String query)
  {
    final OpeningHoursFilterState state = new OpeningHoursFilterState();
    state.onSearchOpened(null);
    state.onQueryChanged(query);
    state.setOpenNow(true, query);
    return state;
  }

  @Test
  public void newQueryStartsWithoutFilter()
  {
    // The reported case: "Café" with "Open now", then a search for containers which have no opening hours.
    final OpeningHoursFilterState state = openNowFor(CAFE);
    state.onQueryChanged("Wertstoffcontainer");
    assertFalse(state.isOpenNow());
    assertEquals(OpeningHoursFilter.OFF, state.getFilter());
  }

  @Test
  public void clearedQueryStartsWithoutFilter()
  {
    final OpeningHoursFilterState state = openNowFor(CAFE);
    state.onQueryChanged("");
    // Typing a new query which happens to start like the old one.
    state.onQueryChanged("C");
    assertFalse(state.isOpenNow());
  }

  @Test
  public void otherCategoryStartsWithoutFilter()
  {
    final OpeningHoursFilterState state = openNowFor(CAFE_CATEGORY);
    state.onQueryChanged("Recycling ");
    assertEquals(OpeningHoursFilter.OFF, state.getFilter());
  }

  @Test
  public void includeUnknownIsResetByNewQuery()
  {
    final OpeningHoursFilterState state = openNowFor(CAFE);
    state.setIncludeUnknown(true, CAFE);
    state.onQueryChanged("Bäckerei");
    assertFalse(state.isIncludeUnknown());

    // Also for a filter recognized in the query ("Café offen").
    state.setIncludeUnknown(true, "Café offen");
    state.onQueryChanged("Bäckerei offen");
    assertFalse(state.isIncludeUnknown());
  }

  @Test
  public void editingTheQueryKeepsFilter()
  {
    final OpeningHoursFilterState state = openNowFor(CAFE);
    for (String query : new String[] {"Café ", "café", "Café Müller", "Caf", "Café  Müller"})
    {
      state.onQueryChanged(query);
      assertEquals(query, OpeningHoursFilter.OPEN_NOW, state.getFilter());
    }
  }

  @Test
  public void categoryOfTheSameNameKeepsFilter()
  {
    final OpeningHoursFilterState state = openNowFor(CAFE);
    state.onQueryChanged(CAFE_CATEGORY);
    assertTrue(state.isOpenNow());
  }

  @Test
  public void sameSearchOpenedAgainKeepsFilter()
  {
    // Back from the results on the map: the search is opened again with its query.
    final OpeningHoursFilterState state = openNowFor(CAFE_CATEGORY);
    state.setIncludeUnknown(true, CAFE_CATEGORY);
    state.onSearchOpened(CAFE_CATEGORY);
    state.onQueryChanged(CAFE_CATEGORY);
    assertEquals(OpeningHoursFilter.OPEN_NOW_OR_UNKNOWN, state.getFilter());
  }

  @Test
  public void searchOpenedAgainWithoutQueryStartsWithoutFilter()
  {
    final OpeningHoursFilterState state = openNowFor(CAFE);
    state.onSearchOpened(null);
    assertEquals(OpeningHoursFilter.OFF, state.getFilter());

    final OpeningHoursFilterState other = openNowFor(CAFE);
    other.onSearchOpened("");
    assertEquals(OpeningHoursFilter.OFF, other.getFilter());
  }

  @Test
  public void searchOpenedWithAnotherQueryStartsWithoutFilter()
  {
    // A prefix is not enough for a newly opened search.
    final OpeningHoursFilterState state = openNowFor(CAFE);
    state.onSearchOpened("Caf");
    assertFalse(state.isOpenNow());
  }

  @Test
  public void recreatedScreenKeepsFilter()
  {
    // A recreated screen restores the text of its field, it is not a newly opened search.
    final OpeningHoursFilterState state = openNowFor(CAFE);
    state.onQueryChanged(CAFE);
    assertTrue(state.isOpenNow());
  }

  @Test
  public void filterSetForTheNewQueryIsKept()
  {
    final OpeningHoursFilterState state = openNowFor(CAFE);
    state.onQueryChanged("Wertstoffcontainer");
    state.setOpenNow(true, "Wertstoffcontainer");
    state.onQueryChanged("Wertstoffcontainer ");
    assertTrue(state.isOpenNow());
  }
}
