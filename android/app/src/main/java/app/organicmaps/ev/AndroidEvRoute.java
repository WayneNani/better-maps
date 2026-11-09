package app.organicmaps.ev;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import app.organicmaps.sdk.routing.RoutingController;
import java.util.ArrayList;
import java.util.List;

/** The route of the route planner: charging stops as intermediate points. */
public class AndroidEvRoute implements EvPlanPresenter.Route
{
  @NonNull
  private final String mStopSubtitle;
  @NonNull
  private final Runnable mStartNavigation;

  /**
   * @param stopSubtitle    subtitle of the charging stops in the list of route points.
   * @param startNavigation starts the navigation like the start button of the route planner.
   */
  public AndroidEvRoute(@NonNull String stopSubtitle, @NonNull Runnable startNavigation)
  {
    mStopSubtitle = stopSubtitle;
    mStartNavigation = startNavigation;
  }

  @Override
  public void setChargingStops(@NonNull List<EvPlanResult.Stop> oldStops, @NonNull List<EvPlanResult.Stop> newStops,
                               @NonNull Runnable onBuilt)
  {
    if (oldStops.isEmpty() && newStops.isEmpty())
    {
      onBuilt.run();
      return;
    }
    final RoutingController controller = RoutingController.get();
    controller.addRouteChangedListener(new RoutingController.RouteChangedListener() {
      @Override
      public void onRouteChanged()
      {
        controller.removeRouteChangedListener(this);
        onBuilt.run();
      }
    });
    final List<RouteMarkData> marks = new ArrayList<>();
    for (EvPlanResult.Stop stop : newStops)
      marks.add(new RouteMarkData(stop.title, mStopSubtitle, RouteMarkType.Intermediate, 0, true, false, false,
                                  stop.lat, stop.lon));
    controller.replaceIntermediatePoints(
        point -> EvRouteStops.findStop(oldStops, point.mLat, point.mLon) != null, marks);
  }

  @Override
  public void startNavigation()
  {
    mStartNavigation.run();
  }
}
