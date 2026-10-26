package app.organicmaps.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import app.organicmaps.R;
import org.junit.Test;

public class OpeningHoursFilterHintTest
{
  private static Resources germanResources()
  {
    final Resources resources = mock(Resources.class);
    when(resources.getQuantityString(R.plurals.search_filter_hidden_without_opening_hours, 12, 12))
        .thenReturn("12 Treffer ohne Öffnungszeiten ausgeblendet");
    when(resources.getQuantityString(R.plurals.search_filter_hidden_without_opening_hours, 1, 1))
        .thenReturn("1 Treffer ohne Öffnungszeiten ausgeblendet");
    when(resources.getString(R.string.search_filter_show_hidden)).thenReturn("Anzeigen");
    when(resources.getQuantityString(R.plurals.search_filter_hidden_without_opening_hours, 100, 100))
        .thenReturn("100 Treffer ohne Öffnungszeiten ausgeblendet");
    when(resources.getString(R.string.search_filter_hidden_without_opening_hours_many, 100))
        .thenReturn("Mehr als 100 Treffer ohne Öffnungszeiten ausgeblendet");
    return resources;
  }

  @Test
  public void shownOnlyIfPlacesAreHidden()
  {
    assertFalse(OpeningHoursFilterHint.isShown(0));
    assertFalse(OpeningHoursFilterHint.isShown(-1));
    assertTrue(OpeningHoursFilterHint.isShown(1));
    assertTrue(OpeningHoursFilterHint.isShown(12));
  }

  @Test
  public void message()
  {
    assertEquals("12 Treffer ohne Öffnungszeiten ausgeblendet",
                 OpeningHoursFilterHint.getMessage(germanResources(), 12));
    assertEquals("1 Treffer ohne Öffnungszeiten ausgeblendet", OpeningHoursFilterHint.getMessage(germanResources(), 1));
  }

  @Test
  public void manyHiddenPlacesAreNotCountedExactly()
  {
    assertEquals("100 Treffer ohne Öffnungszeiten ausgeblendet",
                 OpeningHoursFilterHint.getMessage(germanResources(), 100));
    assertEquals("Mehr als 100 Treffer ohne Öffnungszeiten ausgeblendet",
                 OpeningHoursFilterHint.getMessage(germanResources(), 101));
    assertEquals("Mehr als 100 Treffer ohne Öffnungszeiten ausgeblendet",
                 OpeningHoursFilterHint.getMessage(germanResources(), 696));
  }

  @Test
  public void contentDescriptionHasTheAction()
  {
    assertEquals("12 Treffer ohne Öffnungszeiten ausgeblendet · Anzeigen",
                 OpeningHoursFilterHint.getContentDescription(germanResources(), 12));
  }
}
