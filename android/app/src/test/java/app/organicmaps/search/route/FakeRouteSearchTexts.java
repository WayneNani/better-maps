package app.organicmaps.search.route;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.util.Distance;

/** English texts for tests. */
class FakeRouteSearchTexts implements RouteSearchTexts
{
  @NonNull
  @Override
  public String distance(@NonNull Distance distance)
  {
    return distance.mDistanceStr + " " + (distance.mUnits == Distance.Units.Kilometers ? "km" : "m");
  }

  @NonNull
  @Override
  public String inDistance(@NonNull String distance)
  {
    return "in " + distance;
  }

  @NonNull
  @Override
  public String detourMinutes(int minutes)
  {
    return "Detour ~" + minutes + " min";
  }

  @NonNull
  @Override
  public String onTheRoute()
  {
    return "On the route";
  }

  @NonNull
  @Override
  public String openOnArrival(boolean open)
  {
    return open ? "Open on arrival" : "Closed on arrival";
  }

  @NonNull
  @Override
  public String width(int meters)
  {
    return meters + " m";
  }
}
