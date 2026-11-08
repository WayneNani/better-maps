package app.organicmaps.search.route;

import static app.organicmaps.search.route.RouteSearchTestData.position;
import static app.organicmaps.search.route.RouteSearchTestData.result;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.organicmaps.sdk.search.SearchResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class RouteSearchMapAreaTest
{
  @Test
  public void nothingToShow()
  {
    assertNull(RouteSearchMapArea.of(List.of()));
  }

  @Test
  public void fewPlacesWithTheWholeRoute()
  {
    final List<SearchResult> results = List.of(result("Aral", SearchResult.OPEN_NOW_YES, position(12, 60)),
                                               result("Shell", SearchResult.OPEN_NOW_YES, position(3, 60)));
    final RouteSearchMapArea area = RouteSearchMapArea.of(results);
    assertTrue(area.wholeRoute);
    // Ordered along the route.
    assertEquals(List.of(results.get(1), results.get(0)), area.places);
    assertEquals(12000, area.distanceAlongRouteMeters, 1e-6);
    assertArrayEquals(new double[] {50.8, 7.2, 50.8, 7.2}, area.getLatLons(), 1e-9);
  }

  @Test
  public void manyPlacesOnlyTheNextOnes()
  {
    final List<SearchResult> results = new ArrayList<>();
    for (int km = 30; km > 0; --km)
      results.add(result("Place " + km, SearchResult.OPEN_NOW_UNKNOWN, position(km, 60)));
    final RouteSearchMapArea area = RouteSearchMapArea.of(results);
    assertFalse(area.wholeRoute);
    assertEquals(RouteSearchMapArea.MAX_PLACES, area.places.size());
    assertEquals("Place 1", area.places.get(0).name);
    // The route up to the last of them.
    assertEquals(RouteSearchMapArea.MAX_PLACES * 1000, area.distanceAlongRouteMeters, 1e-6);
  }
}
