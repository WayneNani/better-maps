package app.organicmaps.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.organicmaps.sdk.search.SearchEngine;
import org.junit.After;
import org.junit.Test;

/**
 * A query from a tapped search category must stay a categorial request while its text is unchanged: when the "Open
 * now" chips start a new search, after the search screen is recreated, and after a detour to the map ("View on map",
 * a result) and back to the search screen. Otherwise the text is searched as a normal query and also finds
 * restaurants, fast food etc. for "Café".
 */
public class SearchQueryStateTest
{
  private static final String CAFE = "Café ";

  @After
  public void tearDown()
  {
    SearchEngine.INSTANCE.setQuery(null);
  }

  /** The search screen after a tap on a category: the text field reports the new text. */
  private static SearchQueryState tapCategory(String category)
  {
    final SearchQueryState state = new SearchQueryState();
    state.setQuery(category, true);
    state.onTextChanged(category);
    return state;
  }

  @Test
  public void tappedCategoryIsCategorial()
  {
    final SearchQueryState state = tapCategory(CAFE);
    assertTrue(state.isCategory());
    assertEquals(CAFE, state.getCategoryQuery());
  }

  @Test
  public void openingHoursChipsKeepTheCategory()
  {
    // The chips start a new search with the unchanged text; the text field may report it again.
    final SearchQueryState state = tapCategory(CAFE);
    state.onTextChanged(CAFE);
    assertTrue(state.isCategory());
    state.onTextChanged(CAFE);
    assertTrue(state.isCategory());
  }

  @Test
  public void keyboardTrimmingTheSpaceKeepsTheCategory()
  {
    // Category texts end with a space; a keyboard may remove or add it when the field loses focus.
    final SearchQueryState state = tapCategory(CAFE);
    state.onTextChanged("Café");
    assertTrue(state.isCategory());
    state.onTextChanged("Café  ");
    assertTrue(state.isCategory());
  }

  @Test
  public void editedTextIsANormalQuery()
  {
    final SearchQueryState state = tapCategory(CAFE);
    state.onTextChanged("Italiener offen");
    assertFalse(state.isCategory());
    assertNull(state.getCategoryQuery());
    // Typing the category text again does not make it a category.
    state.onTextChanged(CAFE);
    assertFalse(state.isCategory());
  }

  @Test
  public void addedWordIsANormalQuery()
  {
    final SearchQueryState state = tapCategory(CAFE);
    state.onTextChanged("Café offen");
    assertFalse(state.isCategory());
  }

  @Test
  public void typedTextIsNoCategory()
  {
    final SearchQueryState state = new SearchQueryState();
    state.setQuery(CAFE, false);
    state.onTextChanged(CAFE);
    assertFalse(state.isCategory());
  }

  @Test
  public void clearedQueryIsNoCategory()
  {
    final SearchQueryState state = tapCategory(CAFE);
    state.setQuery("", false);
    state.onTextChanged("");
    assertFalse(state.isCategory());
  }

  @Test
  public void recreatedSearchScreenKeepsTheCategory()
  {
    final SearchQueryState before = tapCategory("Bars & Clubs ");
    final String saved = before.getCategoryQuery();

    // New screen: the saved category is restored, then the text field restores its text.
    final SearchQueryState after = new SearchQueryState();
    after.restoreCategory(saved);
    after.onTextChanged("Bars & Clubs ");
    assertTrue(after.isCategory());
  }

  @Test
  public void restoredCategoryNeedsTheSameText()
  {
    final SearchQueryState after = new SearchQueryState();
    after.restoreCategory(CAFE);
    after.onTextChanged("Café offen");
    assertFalse(after.isCategory());
  }

  @Test
  public void categoryQueryOnTheMapIsCategorial()
  {
    // "View on map" or a result: the search screen hands the query over to the map.
    final SearchQueryState search = tapCategory(CAFE);
    SearchEngine.INSTANCE.setQuery(CAFE, search.isCategory());
    assertEquals(CAFE, SearchEngine.INSTANCE.getQuery());
    assertTrue(SearchEngine.INSTANCE.isQueryCategory());

    // Back to the search screen from the search bar on the map: it is opened with the query of the map.
    final SearchQueryState reopened = new SearchQueryState();
    reopened.setQuery(SearchEngine.INSTANCE.getQuery(), SearchEngine.INSTANCE.isQueryCategory());
    reopened.onTextChanged(SearchEngine.INSTANCE.getQuery());
    assertTrue(reopened.isCategory());
  }

  @Test
  public void normalQueryOnTheMapIsNoCategory()
  {
    SearchEngine.INSTANCE.setQuery(CAFE, true);
    SearchEngine.INSTANCE.setQuery("Italiener offen");
    assertFalse(SearchEngine.INSTANCE.isQueryCategory());
    SearchEngine.INSTANCE.setQuery(null);
    assertFalse(SearchEngine.INSTANCE.isQueryCategory());
  }
}
