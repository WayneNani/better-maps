package app.organicmaps.search.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.organicmaps.sdk.Router;
import java.util.List;
import org.junit.Test;

public class RouteSearchCategoryTest
{
  @Test
  public void carHasFuelChargingAndParking()
  {
    final List<RouteSearchCategory> categories = RouteSearchCategory.forRouter(Router.Vehicle);
    assertEquals(
        List.of(RouteSearchCategory.FUEL, RouteSearchCategory.CHARGING, RouteSearchCategory.RESTAURANT,
                RouteSearchCategory.CAFE, RouteSearchCategory.BAKERY, RouteSearchCategory.SUPERMARKET,
                RouteSearchCategory.TOILET, RouteSearchCategory.PARKING, RouteSearchCategory.RECYCLING_CONTAINER),
        categories);
    assertFalse(categories.contains(RouteSearchCategory.DRINKING_WATER));
  }

  @Test
  public void bicycleAndPedestrianHaveNoCarCategories()
  {
    for (Router router : new Router[] {Router.Bicycle, Router.Pedestrian})
    {
      final List<RouteSearchCategory> categories = RouteSearchCategory.forRouter(router);
      assertFalse(categories.contains(RouteSearchCategory.FUEL));
      assertFalse(categories.contains(RouteSearchCategory.CHARGING));
      assertFalse(categories.contains(RouteSearchCategory.PARKING));
      assertTrue(categories.contains(RouteSearchCategory.CAFE));
      assertTrue(categories.contains(RouteSearchCategory.BAKERY));
      assertTrue(categories.contains(RouteSearchCategory.TOILET));
      assertTrue(categories.contains(RouteSearchCategory.DRINKING_WATER));
      assertTrue(categories.contains(RouteSearchCategory.RECYCLING_CONTAINER));
    }
  }

  @Test
  public void rulerHasNoCategories()
  {
    assertTrue(RouteSearchCategory.forRouter(Router.Ruler).isEmpty());
  }
}
