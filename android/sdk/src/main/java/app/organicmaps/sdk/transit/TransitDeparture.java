package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Instant;

/** One departure of a vehicle at a stop. */
public final class TransitDeparture
{
  @NonNull
  TransitMode mMode = TransitMode.UNKNOWN;
  @NonNull
  TransitPlace mPlace = new TransitPlace();
  @Nullable
  String mRouteShortName;
  @Nullable
  String mDisplayName;
  @Nullable
  String mHeadsign;
  @Nullable
  String mDirection;
  @Nullable
  String mScheduledDirection;
  boolean mShortened;
  @Nullable
  String mAgencyName;
  @Nullable
  String mRouteColor;
  @Nullable
  String mRouteTextColor;
  @Nullable
  String mTripId;
  boolean mRealTime;
  boolean mCancelled;

  TransitDeparture() {}

  @NonNull
  public TransitMode getMode()
  {
    return mMode;
  }

  /** The stop with departure times and track. */
  @NonNull
  public TransitPlace getPlace()
  {
    return mPlace;
  }

  @Nullable
  public String getRouteShortName()
  {
    return mRouteShortName;
  }

  @Nullable
  public String getDisplayName()
  {
    return mDisplayName;
  }

  /** Best available line name: display name or route short name. */
  @Nullable
  public String getLineName()
  {
    if (mDisplayName != null && !mDisplayName.isEmpty())
      return mDisplayName;
    return mRouteShortName;
  }

  @Nullable
  public String getHeadsign()
  {
    return mHeadsign;
  }

  /**
   * Direction for display: the headsign, or the final stop of the trip if there is no headsign. If realtime data
   * cuts the trip short ({@link #isShortened()}), the last stop the trip still serves.
   */
  @Nullable
  public String getDirection()
  {
    return mDirection;
  }

  /**
   * True if realtime data cancels the last stops of the trip, so it ends before its scheduled destination, e.g. a
   * tram turning early because a replacement bus serves the rest of the line. {@link #getDirection()} is the new end.
   */
  public boolean isShortened()
  {
    return mShortened;
  }

  /** The direction according to the timetable if the trip {@link #isShortened()}, else null. */
  @Nullable
  public String getScheduledDirection()
  {
    return mScheduledDirection;
  }

  @Nullable
  public String getAgencyName()
  {
    return mAgencyName;
  }

  @Nullable
  public String getRouteColor()
  {
    return mRouteColor;
  }

  @Nullable
  public String getRouteTextColor()
  {
    return mRouteTextColor;
  }

  @Nullable
  public String getTripId()
  {
    return mTripId;
  }

  /** Expected departure (realtime if available). */
  @Nullable
  public Instant getDeparture()
  {
    return mPlace.getDeparture();
  }

  @Nullable
  public Instant getScheduledDeparture()
  {
    return mPlace.getScheduledDeparture();
  }

  /** Departure delay in seconds, negative if early, 0 if unknown. */
  public long getDelaySeconds()
  {
    final Long delay = mPlace.getDepartureDelaySeconds();
    return delay == null ? 0 : delay;
  }

  public boolean isRealTime()
  {
    return mRealTime;
  }

  /** True if the trip or this stop is cancelled, or no later stop of the trip is served. */
  public boolean isCancelled()
  {
    return mCancelled;
  }

  @NonNull
  @Override
  public String toString()
  {
    return mMode + " " + getLineName() + " -> " + mDirection + " " + getDeparture();
  }
}
