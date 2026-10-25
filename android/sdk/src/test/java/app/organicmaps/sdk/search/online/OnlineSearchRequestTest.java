package app.organicmaps.sdk.search.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OnlineSearchRequestTest
{
  @Test
  public void queryIsTrimmedAndCollapsed()
  {
    assertEquals("Marienplatz München", OnlineSearchRequest.builder(" Marienplatz \t München\n").build().getQuery());
    assertThrows(IllegalArgumentException.class, () -> OnlineSearchRequest.builder("   "));
  }

  @Test
  public void defaults()
  {
    final OnlineSearchRequest request = OnlineSearchRequest.builder("Dom").build();
    assertEquals(OnlineSearchRequest.DEFAULT_LIMIT, request.getLimit());
    assertNull(request.getLanguage());
    assertFalse(request.hasLocationBias());
    assertNull(request.getBiasLat());
  }

  @Test
  public void biasIsRoundedToAboutOneKilometre()
  {
    final OnlineSearchRequest request =
        OnlineSearchRequest.builder("Dom").setLocationBias(52.516275, 13.377704).build();
    assertTrue(request.hasLocationBias());
    assertEquals(52.52, request.getBiasLat(), 0);
    assertEquals(13.38, request.getBiasLon(), 0);
    assertEquals(-0.0 + 0.0, OnlineSearchRequest.builder("x y z").setLocationBias(-0.001, 0.001).build().getBiasLat(),
                 0);
    assertFalse(OnlineSearchRequest.builder("Dom").setLocationBias(1, 2).clearLocationBias().build().hasLocationBias());
  }

  @Test
  public void nearbyPositionsGiveEqualRequests()
  {
    final OnlineSearchRequest a =
        OnlineSearchRequest.builder("Dom").setLanguage("de").setLocationBias(52.5161, 13.3771).build();
    final OnlineSearchRequest b =
        OnlineSearchRequest.builder("Dom ").setLanguage("de-AT").setLocationBias(52.5199, 13.3812).build();
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, OnlineSearchRequest.builder("Dom").setLanguage("en").setLocationBias(52.5161, 13.3771).build());
    assertNotEquals(a, OnlineSearchRequest.builder("Dom").setLanguage("de").build());
    assertNotEquals(
        a, OnlineSearchRequest.builder("Dom").setLanguage("de").setLimit(5).setLocationBias(52.5161, 13.3771).build());
    assertNotEquals(a, OnlineSearchRequest.builder("Dom2").setLanguage("de").setLocationBias(52.5161, 13.3771).build());
  }

  @Test
  public void invalidArguments()
  {
    assertThrows(IllegalArgumentException.class, () -> OnlineSearchRequest.builder("a").setLimit(0));
    assertThrows(IllegalArgumentException.class,
                 () -> OnlineSearchRequest.builder("a").setLimit(OnlineSearchRequest.MAX_LIMIT + 1));
    assertThrows(IllegalArgumentException.class, () -> OnlineSearchRequest.builder("a").setLocationBias(91, 0));
    assertThrows(IllegalArgumentException.class, () -> OnlineSearchRequest.builder("a").setLocationBias(0, -181));
    assertThrows(IllegalArgumentException.class, () -> OnlineSearchRequest.builder("a").setLocationBias(Double.NaN, 0));
  }

  @Test
  public void languageNormalization()
  {
    assertEquals("de", OnlineSearchRequest.normalizeLanguage("de-DE"));
    assertEquals("de", OnlineSearchRequest.normalizeLanguage("de_AT"));
    assertEquals("en", OnlineSearchRequest.normalizeLanguage(" EN "));
    assertEquals("zh", OnlineSearchRequest.normalizeLanguage("zh-Hans-CN"));
    assertEquals("ast", OnlineSearchRequest.normalizeLanguage("ast"));
    assertNull(OnlineSearchRequest.normalizeLanguage("d"));
    assertNull(OnlineSearchRequest.normalizeLanguage("deutsch"));
    assertNull(OnlineSearchRequest.normalizeLanguage("d3"));
    assertNull(OnlineSearchRequest.normalizeLanguage(""));
    assertNull(OnlineSearchRequest.normalizeLanguage(null));
    assertNull(OnlineSearchRequest.builder("a").setLanguage("--").build().getLanguage());
  }

  @Test
  public void readableToString()
  {
    assertEquals(
        "\"Dom\" lang=de limit=10 near 52.52,13.38",
        OnlineSearchRequest.builder("Dom").setLanguage("de").setLocationBias(52.516, 13.377).build().toString());
  }
}
