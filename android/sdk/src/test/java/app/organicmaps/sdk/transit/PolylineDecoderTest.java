package app.organicmaps.sdk.transit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class PolylineDecoderTest
{
  private static final double EPS = 1e-9;

  @Test
  public void decodesGoogleReferenceExample()
  {
    // Example from the Google polyline algorithm documentation.
    final double[] points = PolylineDecoder.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5);
    assertArrayEquals(new double[] {38.5, -120.2, 40.7, -120.95, 43.252, -126.453}, points, EPS);
  }

  @Test
  public void decodesWithPrecision6()
  {
    // Same deltas as above, but interpreted with 6 decimal places.
    final double[] points = PolylineDecoder.decode("_p~iF~ps|U", 6);
    assertArrayEquals(new double[] {3.85, -12.02}, points, EPS);
  }

  @Test
  public void decodesSinglePointAtOrigin()
  {
    assertArrayEquals(new double[] {0, 0}, PolylineDecoder.decode("??", 6), EPS);
  }

  @Test
  public void emptyOrNullGivesNoPoints()
  {
    assertEquals(0, PolylineDecoder.decode(null, 6).length);
    assertEquals(0, PolylineDecoder.decode("", 6).length);
  }

  @Test
  public void rejectsTruncatedPolyline()
  {
    // Latitude without longitude.
    assertThrows(IllegalArgumentException.class, () -> PolylineDecoder.decode("_p~iF", 5));
    // Unterminated value (continuation bit set on the last character).
    assertThrows(IllegalArgumentException.class, () -> PolylineDecoder.decode("_p~iF~", 5));
  }

  @Test
  public void rejectsInvalidCharactersAndPrecision()
  {
    assertThrows(IllegalArgumentException.class, () -> PolylineDecoder.decode("ab cd", 5));
    assertThrows(IllegalArgumentException.class, () -> PolylineDecoder.decode("??", 12));
  }

  @Test
  public void decodesManyPoints()
  {
    // 1000 times the point delta (+0.00001, +0.00001) = "AA" in precision 5.
    final StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 1000; ++i)
      sb.append("AA");
    final double[] points = PolylineDecoder.decode(sb.toString(), 5);
    assertEquals(2000, points.length);
    assertEquals(0.01, points[1998], EPS);
    assertEquals(0.01, points[1999], EPS);
  }
}
