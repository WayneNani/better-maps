package app.organicmaps.search.route;

import android.content.Context;
import androidx.annotation.NonNull;
import app.organicmaps.R;
import app.organicmaps.sdk.util.Distance;

/** {@link RouteSearchTexts} from the Android resources. */
public class AndroidRouteSearchTexts implements RouteSearchTexts
{
  @NonNull
  private final Context mContext;

  public AndroidRouteSearchTexts(@NonNull Context context)
  {
    mContext = context;
  }

  @NonNull
  @Override
  public String distance(@NonNull Distance distance)
  {
    return distance.toString(mContext);
  }

  @NonNull
  @Override
  public String inDistance(@NonNull String distance)
  {
    return mContext.getString(R.string.route_search_in_distance, distance);
  }

  @NonNull
  @Override
  public String detourMinutes(int minutes)
  {
    final String duration = mContext.getResources().getQuantityString(R.plurals.minutes_short, minutes, minutes);
    return mContext.getString(R.string.route_search_detour, duration);
  }

  @NonNull
  @Override
  public String onTheRoute()
  {
    return mContext.getString(R.string.route_search_on_route);
  }

  @NonNull
  @Override
  public String openOnArrival(boolean open)
  {
    return mContext.getString(open ? R.string.route_search_open_on_arrival : R.string.route_search_closed_on_arrival);
  }

  @NonNull
  @Override
  public String width(int meters)
  {
    if (meters < 1000)
      return meters + " " + mContext.getString(app.organicmaps.sdk.R.string.m);
    if (meters % 1000 == 0)
      return (meters / 1000) + " " + mContext.getString(app.organicmaps.sdk.R.string.km);
    return String.format(mContext.getResources().getConfiguration().getLocales().get(0), "%.1f", meters / 1000.0) + " "
  + mContext.getString(app.organicmaps.sdk.R.string.km);
  }
}
