package app.organicmaps.maplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class AerialAttributionTest
{
  private static final String ANSWER = "{\"sources\": ["
                                     + "{\"id\": \"nw\", \"name\": \"Nordrhein-Westfalen, DOP 10 cm\", "
                                     + "\"attribution\": \"Geobasis NRW, dl-de/zero-2-0\", \"license\": "
                                     + "\"dl-de/zero-2.0\", \"license_url\": \"https://www.govdata.de/dl-de/zero-2-0\", "
                                     + "\"min_zoom\": 12, \"bbox\": [5.8, 50.3, 9.5, 52.5]},"
                                     + "{\"id\": \"s2cloudless2016\", \"name\": \"Sentinel-2 cloudless 2016\", "
                                     + "\"attribution\": \"EOxCloudless by EOX\", \"license\": \"CC BY 4.0\", "
                                     + "\"min_zoom\": 0, \"bbox\": [-180, -85, 180, 85]}]}";

  @Test
  public void parse()
  {
    final List<AerialAttribution.Source> sources = AerialAttribution.parse(ANSWER);
    assertEquals(2, sources.size());
    assertEquals("nw", sources.get(0).id);
    assertEquals("Geobasis NRW, dl-de/zero-2-0", sources.get(0).attribution);
    assertEquals("dl-de/zero-2.0", sources.get(0).license);
    assertEquals("https://www.govdata.de/dl-de/zero-2-0", sources.get(0).licenseUrl);
    assertEquals("", sources.get(1).licenseUrl);
    assertEquals("Geobasis NRW, dl-de/zero-2-0 | EOxCloudless by EOX", AerialAttribution.shortText(sources));
  }

  @Test
  public void parseBrokenAnswers()
  {
    for (String bad : new String[] {null, "", "x", "[]", "{}", "{\"sources\": 1}", "{\"sources\": [1, \"x\"]}",
                                    "{\"sources\": [{\"id\": \"no-attribution\"}]}", "{\"sources\": [{\"attr"})
      assertTrue(String.valueOf(bad), AerialAttribution.parse(bad).isEmpty());
    assertEquals("", AerialAttribution.shortText(AerialAttribution.parse(null)));
  }

  @Test
  public void requestUrl()
  {
    assertEquals("https://x/v1/attribution?bbox=7.00000,50.70000,7.20000,50.80000&z=15",
                 AerialAttribution.requestUrl("https://x", new double[] {7.0, 50.7, 7.2, 50.8}, 15));
    // Clamped to the valid range.
    assertEquals("https://x/v1/attribution?bbox=-180.00000,-85.06000,180.00000,85.06000&z=0",
                 AerialAttribution.requestUrl("https://x", new double[] {-200, -89, 200, 89}, -3));
    assertTrue(AerialAttribution.requestUrl("https://x", new double[] {1, 2, 3, 4}, 30).endsWith("&z=22"));
  }

  @Test
  public void viewKeyIgnoresSmallMoves()
  {
    final double[] view = {7.0, 50.7, 7.2, 50.8};
    final String key = AerialAttribution.viewKey(view, 14);
    assertEquals(key, AerialAttribution.viewKey(new double[] {7.0001, 50.7001, 7.2001, 50.8001}, 14));
    assertNotEquals(key, AerialAttribution.viewKey(new double[] {7.05, 50.7, 7.25, 50.8}, 14));
    assertNotEquals(key, AerialAttribution.viewKey(view, 15));
    assertNull(AerialAttribution.viewKey(null, 14));
    assertNull(AerialAttribution.viewKey(new double[] {1, 2, 3}, 14));
    assertNull(AerialAttribution.viewKey(new double[] {3, 2, 1, 4}, 14));
    assertNull(AerialAttribution.viewKey(new double[] {Double.NaN, 2, 3, 4}, 14));
  }
}
