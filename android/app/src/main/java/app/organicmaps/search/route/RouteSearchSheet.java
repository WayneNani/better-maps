package app.organicmaps.search.route;

import androidx.annotation.NonNull;

/** Where the list panel of the search along the route goes after a drag: open (the list) or closed (the map). */
final class RouteSearchSheet
{
  private RouteSearchSheet() {}

  /**
   * @param bodyHeight height of the list when the finger is lifted.
   * @param listHeight height of the open list.
   * @param velocity pixels per second, positive downwards.
   * @param flingVelocity speed above which the direction of the drag decides.
   */
  @NonNull
  static RouteSearchPresenter.Display settle(int bodyHeight, int listHeight, float velocity, float flingVelocity)
  {
    if (Math.abs(velocity) >= flingVelocity)
      return velocity < 0 ? RouteSearchPresenter.Display.LIST : RouteSearchPresenter.Display.MAP;
    return bodyHeight * 2 >= listHeight ? RouteSearchPresenter.Display.LIST : RouteSearchPresenter.Display.MAP;
  }
}
