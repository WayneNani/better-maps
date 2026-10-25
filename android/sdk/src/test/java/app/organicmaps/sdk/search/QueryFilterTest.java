package app.organicmaps.sdk.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QueryFilterTest
{
  @Test
  public void openNow()
  {
    final QueryFilter filter = new QueryFilter(1, 0, "Café ");
    assertEquals(OpeningHoursFilter.OPEN_NOW, filter.getOpeningHoursFilter());
    assertFalse(filter.hasOpeningHoursTime());
    assertEquals("Café ", filter.getQuery());
  }

  @Test
  public void openAtTime()
  {
    final QueryFilter filter = new QueryFilter(1, 1792929600L, "Apotheke ");
    assertTrue(filter.hasOpeningHoursTime());
    assertEquals(1792929600000L, filter.getOpeningHoursTimeMillis());
  }

  @Test
  public void fromNative()
  {
    for (OpeningHoursFilter filter : OpeningHoursFilter.values())
      assertEquals(filter, OpeningHoursFilter.fromNative(filter.toNative()));
    assertThrows(IllegalArgumentException.class, () -> OpeningHoursFilter.fromNative(42));
  }
}
