package app.organicmaps.search.online;

import static app.organicmaps.search.online.OfflinePlace.Match.FULL;
import static app.organicmaps.search.online.OfflinePlace.Match.PARTIAL;
import static app.organicmaps.search.online.OfflinePlace.Match.UNHIGHLIGHTED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Does an offline result answer the whole query? Highlights are pairs of (start, length). */
public class OfflinePlaceTest
{
  private static final String QUERY = "Marienplatz München";

  @NonNull
  private static List<OfflinePlace> places(@NonNull OfflinePlace.Match... matches)
  {
    final List<OfflinePlace> places = new ArrayList<>();
    for (OfflinePlace.Match match : matches)
      places.add(new OfflinePlace("x", 1, 2, null, match));
    return places;
  }

  @Test
  public void partialMatchesInAnotherCity()
  {
    // Searched in Berlin: "Marienplatz" (Berlin, Germany) and "Münchener Straße" match only a part of the query.
    assertEquals(PARTIAL, OfflinePlace.match(QUERY, new int[] {0, 11}, new int[] {}));
    assertEquals(PARTIAL, OfflinePlace.match(QUERY, new int[] {0, 7}, null));
  }

  @Test
  public void wholeQueryInNameAndAddress()
  {
    // With the map of Munich: name "Marienplatz", address "München, Bayern".
    assertEquals(FULL, OfflinePlace.match(QUERY, new int[] {0, 11}, new int[] {0, 7}));
    assertEquals(FULL, OfflinePlace.match("Alexanderplatz", new int[] {0, 14}, null));
    // Small typos or missing letters are tolerated.
    assertEquals(FULL, OfflinePlace.match("Alexanderplatz", new int[] {0, 11}, null));
  }

  @Test
  public void withoutHighlight()
  {
    assertEquals(UNHIGHLIGHTED, OfflinePlace.match("Frühstück", new int[] {}, new int[] {}));
    assertEquals(UNHIGHLIGHTED, OfflinePlace.match("Frühstück", null, null));
    // Malformed ranges (odd length, negative) count as no highlight.
    assertEquals(UNHIGHLIGHTED, OfflinePlace.match(QUERY, new int[] {0, -5, 3}, null));
  }

  @Test
  public void onlyLettersAndDigitsOfTheQueryCount()
  {
    assertEquals(FULL, OfflinePlace.match("  Dom - Köln ", new int[] {0, 3}, new int[] {0, 4}));
    assertEquals(FULL, OfflinePlace.match(null, new int[] {0, 1}, null));
    assertEquals(PARTIAL, OfflinePlace.match(QUERY, new int[] {0, 4, 9}, null));
  }

  @Test
  public void categorySearchCountsUnhighlightedResults()
  {
    // "Frühstück": cafés found by the synonym, none of them highlighted.
    assertEquals(3, OfflinePlace.countAnswers(places(UNHIGHLIGHTED, UNHIGHLIGHTED, UNHIGHLIGHTED)));
  }

  @Test
  public void fuzzyResultsNextToHighlightedOnesDontCount()
  {
    // "Marienplatz München" in Berlin: Marienplatz (partial), Münchener Straße (partial), Münchner Straße and
    // Müncheberger Straße (fuzzy, no highlight).
    assertEquals(0, OfflinePlace.countAnswers(places(PARTIAL, PARTIAL, UNHIGHLIGHTED, UNHIGHLIGHTED, UNHIGHLIGHTED)));
    assertEquals(2, OfflinePlace.countAnswers(places(FULL, FULL, PARTIAL, UNHIGHLIGHTED)));
    assertEquals(0, OfflinePlace.countAnswers(Collections.emptyList()));
  }

  @Test
  public void bestResultAnswersTheQuery()
  {
    // "Nürnberg Hbf": the station "Nürnberg Hauptbahnhof" (full), then other stations of Nürnberg (partial).
    assertTrue(OfflinePlace.bestIsAnswer(places(FULL, PARTIAL, PARTIAL, PARTIAL)));
    // Only the first result counts: a full match further down may be a fuzzy one.
    assertFalse(OfflinePlace.bestIsAnswer(places(PARTIAL, FULL)));
    assertFalse(OfflinePlace.bestIsAnswer(places(UNHIGHLIGHTED)));
    assertFalse(OfflinePlace.bestIsAnswer(Collections.emptyList()));
  }

  @Test
  public void defaultConstructorIsAFullMatch()
  {
    assertEquals(FULL, new OfflinePlace("Marienplatz", 1, 2).getMatch());
  }
}
