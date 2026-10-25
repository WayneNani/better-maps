package app.organicmaps.sdk.transit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Locale;
import org.junit.Test;

public class TransitLocationTest
{
  @Test
  public void coordinatesAreFormattedWithDotIndependentOfLocale()
  {
    final Locale saved = Locale.getDefault();
    Locale.setDefault(Locale.GERMANY);
    try
    {
      final TransitLocation location = TransitLocation.fromCoordinates(52.525, 13.3694);
      assertEquals("52.525000,13.369400", location.toApiString());
      assertFalse(location.isStop());
      assertNull(location.getStopId());
      assertEquals(52.525, location.getLat(), 0);
      assertEquals(13.3694, location.getLon(), 0);
    }
    finally
    {
      Locale.setDefault(saved);
    }
  }

  @Test
  public void negativeCoordinates()
  {
    assertEquals("-33.856800,-151.215300", TransitLocation.fromCoordinates(-33.8568, -151.2153).toApiString());
  }

  @Test
  public void stopIdIsPassedUnchanged()
  {
    final TransitLocation location = TransitLocation.fromStopId("de-VBB_de:11000:900100003");
    assertTrue(location.isStop());
    assertEquals("de-VBB_de:11000:900100003", location.getStopId());
    assertEquals("de-VBB_de:11000:900100003", location.toApiString());
  }

  @Test
  public void rejectsInvalidInput()
  {
    assertThrows(IllegalArgumentException.class, () -> TransitLocation.fromCoordinates(91, 0));
    assertThrows(IllegalArgumentException.class, () -> TransitLocation.fromCoordinates(0, 181));
    assertThrows(IllegalArgumentException.class, () -> TransitLocation.fromCoordinates(Double.NaN, 0));
    assertThrows(IllegalArgumentException.class, () -> TransitLocation.fromStopId(" "));
  }

  @Test
  public void equalsAndHashCode()
  {
    assertEquals(TransitLocation.fromStopId("a"), TransitLocation.fromStopId("a"));
    assertEquals(TransitLocation.fromStopId("a").hashCode(), TransitLocation.fromStopId("a").hashCode());
    assertEquals(TransitLocation.fromCoordinates(1, 2), TransitLocation.fromCoordinates(1, 2));
    assertNotEquals(TransitLocation.fromCoordinates(1, 2), TransitLocation.fromCoordinates(2, 1));
    assertNotEquals(TransitLocation.fromStopId("a"), TransitLocation.fromStopId("b"));
  }
}
