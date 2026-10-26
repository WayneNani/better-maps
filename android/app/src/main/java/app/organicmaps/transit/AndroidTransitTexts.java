package app.organicmaps.transit;

import android.content.Context;
import android.content.res.Resources;
import androidx.annotation.NonNull;
import app.organicmaps.R;
import app.organicmaps.sdk.util.StringUtils;

/** {@link TransitTexts} from Android resources. */
public final class AndroidTransitTexts implements TransitTexts
{
  @NonNull
  private final Context mContext;
  @NonNull
  private final Resources mResources;

  public AndroidTransitTexts(@NonNull Context context)
  {
    mContext = context;
    mResources = context.getResources();
  }

  @NonNull
  @Override
  public String minutes(long minutes)
  {
    return mResources.getString(R.string.transit_minutes, minutes);
  }

  @NonNull
  @Override
  public String hoursMinutes(long hours, long minutes)
  {
    if (minutes == 0)
      return mResources.getString(R.string.transit_hours, hours);
    return mResources.getString(R.string.transit_hours_minutes, hours, minutes);
  }

  @NonNull
  @Override
  public String transfers(int transfers)
  {
    if (transfers == 0)
      return mResources.getString(R.string.transit_direct);
    return mResources.getQuantityString(R.plurals.transit_transfers, transfers, transfers);
  }

  @NonNull
  @Override
  public String departNow()
  {
    return mResources.getString(R.string.transit_depart_now);
  }

  @NonNull
  @Override
  public String departAt(@NonNull String time)
  {
    return mResources.getString(R.string.transit_depart_at, time);
  }

  @NonNull
  @Override
  public String arriveBy(@NonNull String time)
  {
    return mResources.getString(R.string.transit_arrive_by, time);
  }

  @NonNull
  @Override
  public String track(@NonNull String track)
  {
    return mResources.getString(R.string.transit_track, track);
  }

  @NonNull
  @Override
  public String walk(@NonNull String duration, @NonNull String distance)
  {
    if (distance.isEmpty())
      return mResources.getString(R.string.transit_walk, duration);
    return mResources.getString(R.string.transit_walk_distance, duration, distance);
  }

  @NonNull
  @Override
  public String distance(double meters)
  {
    // Uses the unit system chosen in the settings.
    return StringUtils.nativeFormatDistance(meters).toString(mContext);
  }

  @NonNull
  @Override
  public String start()
  {
    return mResources.getString(R.string.transit_start);
  }

  @NonNull
  @Override
  public String destination()
  {
    return mResources.getString(R.string.transit_destination);
  }

  @NonNull
  @Override
  public String stops(int count)
  {
    return mResources.getQuantityString(R.plurals.transit_stops, count, count);
  }

  @NonNull
  @Override
  public String searching()
  {
    return mResources.getString(R.string.transit_searching);
  }

  @NonNull
  @Override
  public String noConnections()
  {
    return mResources.getString(R.string.transit_no_connections);
  }

  @NonNull
  @Override
  public String connections(int count)
  {
    return mResources.getQuantityString(R.plurals.transit_connections, count, count);
  }

  @NonNull
  @Override
  public String myPosition()
  {
    return mResources.getString(R.string.transit_my_position);
  }
}
