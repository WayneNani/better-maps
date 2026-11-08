package app.organicmaps.search.route;

import static app.organicmaps.search.route.RouteSearchTestData.position;
import static app.organicmaps.search.route.RouteSearchTestData.result;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.organicmaps.sdk.search.SearchResult;
import org.junit.Test;

public class RouteSearchPlacesTest
{
  @Test
  public void distance()
  {
    // 0.001° of latitude is about 111 m.
    assertEquals(111, RouteSearchPlaces.distanceMeters(50.8, 7.2, 50.801, 7.2), 1);
    // 0.001° of longitude at 50.8° is about 70 m.
    assertEquals(70, RouteSearchPlaces.distanceMeters(50.8, 7.2, 50.8, 7.201), 1);
  }

  @Test
  public void routePointOfAResult()
  {
    final SearchResult aral = result("Aral", SearchResult.OPEN_NOW_YES, position(12, 60));
    // The route point may be moved a bit, e.g. to the road.
    assertTrue(RouteSearchPlaces.isAt(aral, 50.8001, 7.2001));
    assertFalse(RouteSearchPlaces.isAt(aral, 50.801, 7.2));
  }
}
