package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;

/** A stop or a point (start, destination) in a public transport result, with scheduled and realtime times. */
public final class TransitPlace
{
  @NonNull
  String mName = "";
  @Nullable
  String mStopId;
  double mLat;
  double mLon;
  @Nullable
  String mTimeZone;
  @Nullable
  Instant mArrival;
  @Nullable
  Instant mScheduledArrival;
  @Nullable
  Instant mDeparture;
  @Nullable
  Instant mScheduledDeparture;
  @Nullable
  String mTrack;
  @Nullable
  String mScheduledTrack;
  boolean mCancelled;

  TransitPlace() {}

  /** Name of the stop, or "START"/"END" for coordinates given in the query. */
  @NonNull
  public String getName()
  {
    return mName;
  }

  /** @return MOTIS stop id, null if the place is not a stop (e.g. start or destination coordinates). */
  @Nullable
  public String getStopId()
  {
    return mStopId;
  }

  public boolean isStop()
  {
    return mStopId != null;
  }

  public double getLat()
  {
    return mLat;
  }

  public double getLon()
  {
    return mLon;
  }

  /** IANA time zone of the stop, e.g. "Europe/Berlin", if known. */
  @Nullable
  public String getTimeZone()
  {
    return mTimeZone;
  }

  /** Expected arrival (realtime if available, otherwise scheduled). */
  @Nullable
  public Instant getArrival()
  {
    return mArrival;
  }

  @Nullable
  public Instant getScheduledArrival()
  {
    return mScheduledArrival;
  }

  /** Expected departure (realtime if available, otherwise scheduled). */
  @Nullable
  public Instant getDeparture()
  {
    return mDeparture;
  }

  @Nullable
  public Instant getScheduledDeparture()
  {
    return mScheduledDeparture;
  }

  /** @return arrival delay in seconds (negative if early), null if times are unknown. */
  @Nullable
  public Long getArrivalDelaySeconds()
  {
    return delaySeconds(mScheduledArrival, mArrival);
  }

  /** @return departure delay in seconds (negative if early), null if times are unknown. */
  @Nullable
  public Long getDepartureDelaySeconds()
  {
    return delaySeconds(mScheduledDeparture, mDeparture);
  }

  /** Expected track/platform (realtime if available). */
  @Nullable
  public String getTrack()
  {
    return mTrack;
  }

  @Nullable
  public String getScheduledTrack()
  {
    return mScheduledTrack;
  }

  public boolean isTrackChanged()
  {
    return mTrack != null && mScheduledTrack != null && !mTrack.equals(mScheduledTrack);
  }

  /** True if the stop is cancelled for this trip (no boarding/alighting). */
  public boolean isCancelled()
  {
    return mCancelled;
  }

  @Nullable
  static Long delaySeconds(@Nullable Instant scheduled, @Nullable Instant actual)
  {
    if (scheduled == null || actual == null)
      return null;
    return Duration.between(scheduled, actual).getSeconds();
  }

  @NonNull
  @Override
  public String toString()
  {
    return mName + (mStopId != null ? " [" + mStopId + "]" : "");
  }
}
