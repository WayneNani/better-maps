package app.organicmaps.search.route;

import static app.organicmaps.search.route.RouteSearchTestData.position;
import static app.organicmaps.search.route.RouteSearchTestData.result;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import app.organicmaps.sdk.search.SearchResult;
import org.junit.Test;

public class RouteSearchFormatterTest
{
  private final FakeRouteSearchTexts mTexts = new FakeRouteSearchTexts();

  @Test
  public void distanceAndDetour()
  {
    assertEquals("in 12 km · Detour ~2 min", RouteSearchFormatter.formatPosition(position(12, 120), mTexts));
    assertEquals("in 300 m · Detour ~5 min", RouteSearchFormatter.formatPosition(position(0.3, 300), mTexts));
  }

  @Test
  public void detourIsRoundedToMinutesButAtLeastOne()
  {
    assertEquals("in 12 km · Detour ~1 min", RouteSearchFormatter.formatPosition(position(12, 30), mTexts));
    assertEquals("in 12 km · Detour ~1 min", RouteSearchFormatter.formatPosition(position(12, 89), mTexts));
    assertEquals("in 12 km · Detour ~3 min", RouteSearchFormatter.formatPosition(position(12, 150), mTexts));
    assertEquals("in 12 km · Detour ~62 min", RouteSearchFormatter.formatPosition(position(12, 3700), mTexts));
  }

  @Test
  public void placeNextToTheRouteHasNoDetour()
  {
    assertEquals("in 12 km · On the route", RouteSearchFormatter.formatPosition(position(12, 0), mTexts));
    assertEquals("in 12 km · On the route",
                 RouteSearchFormatter.formatPosition(position(12, RouteSearchFormatter.MIN_DETOUR_SEC - 1), mTexts));
  }

  @Test
  public void openingHoursAtArrival()
  {
    assertEquals("Open on arrival",
                 RouteSearchFormatter.formatOpeningHours(result("Aral", SearchResult.OPEN_NOW_YES, null), mTexts));
    assertEquals("Closed on arrival",
                 RouteSearchFormatter.formatOpeningHours(result("Aral", SearchResult.OPEN_NOW_NO, null), mTexts));
    assertNull(RouteSearchFormatter.formatOpeningHours(result("Aral", SearchResult.OPEN_NOW_UNKNOWN, null), mTexts));
  }
}
