package app.organicmaps.search.route;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RouteSearchSheetTest
{
  private static final float FLING = 1000;

  @Test
  public void slowDragSettlesAtTheNearerState()
  {
    assertEquals(RouteSearchPresenter.Display.LIST, RouteSearchSheet.settle(600, 1000, 100, FLING));
    assertEquals(RouteSearchPresenter.Display.MAP, RouteSearchSheet.settle(400, 1000, -100, FLING));
  }

  @Test
  public void flingDecidesByItsDirection()
  {
    assertEquals(RouteSearchPresenter.Display.LIST, RouteSearchSheet.settle(100, 1000, -2000, FLING));
    assertEquals(RouteSearchPresenter.Display.MAP, RouteSearchSheet.settle(900, 1000, 2000, FLING));
  }
}
