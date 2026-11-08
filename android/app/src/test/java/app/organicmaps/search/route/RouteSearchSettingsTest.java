package app.organicmaps.search.route;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.organicmaps.sdk.Router;
import org.junit.Test;

public class RouteSearchSettingsTest
{
  private final RouteSearchTestData.Store mStore = new RouteSearchTestData.Store();
  private final RouteSearchSettings mSettings = new RouteSearchSettings(mStore);

  @Test
  public void defaultWidthDependsOnTheRouter()
  {
    assertEquals(1000, mSettings.getHalfWidth(Router.Vehicle));
    assertEquals(300, mSettings.getHalfWidth(Router.Bicycle));
    assertEquals(150, mSettings.getHalfWidth(Router.Pedestrian));
    for (Router router : new Router[] {Router.Vehicle, Router.Bicycle, Router.Pedestrian})
    {
      boolean isOption = false;
      for (int option : RouteSearchSettings.getHalfWidthOptions(router))
        isOption |= option == RouteSearchSettings.getDefaultHalfWidth(router);
      assertTrue(router.name(), isOption);
    }
  }

  @Test
  public void widthIsKeptPerRouter()
  {
    mSettings.setHalfWidth(Router.Vehicle, 2000);
    mSettings.setHalfWidth(Router.Pedestrian, 50);
    assertEquals(2000, new RouteSearchSettings(mStore).getHalfWidth(Router.Vehicle));
    assertEquals(50, new RouteSearchSettings(mStore).getHalfWidth(Router.Pedestrian));
    assertEquals(300, new RouteSearchSettings(mStore).getHalfWidth(Router.Bicycle));
  }

  @Test
  public void unknownWidthFallsBackToTheDefault()
  {
    mSettings.setHalfWidth(Router.Vehicle, 1234);
    assertEquals(1000, mSettings.getHalfWidth(Router.Vehicle));
  }

  @Test
  public void optionsAndSupportedRouters()
  {
    assertArrayEquals(new int[] {500, 1000, 2000, 5000}, RouteSearchSettings.getHalfWidthOptions(Router.Vehicle));
    assertTrue(RouteSearchSettings.isSupported(Router.Vehicle));
    assertTrue(RouteSearchSettings.isSupported(Router.Bicycle));
    assertTrue(RouteSearchSettings.isSupported(Router.Pedestrian));
    assertFalse(RouteSearchSettings.isSupported(Router.Transit));
    assertFalse(RouteSearchSettings.isSupported(Router.Ruler));
  }
}
