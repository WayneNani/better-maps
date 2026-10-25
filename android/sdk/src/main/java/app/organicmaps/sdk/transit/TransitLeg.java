package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/** One part of an itinerary: a ride with one vehicle or a walk (or another street mode). */
public final class TransitLeg
{
  @NonNull
  TransitMode mMode = TransitMode.UNKNOWN;
  @NonNull
  TransitPlace mFrom = new TransitPlace();
  @NonNull
  TransitPlace mTo = new TransitPlace();
  @NonNull
  Instant mStartTime = Instant.EPOCH;
  @NonNull
  Instant mEndTime = Instant.EPOCH;
  @NonNull
  Instant mScheduledStartTime = Instant.EPOCH;
  @NonNull
  Instant mScheduledEndTime = Instant.EPOCH;
  boolean mRealTime;
  long mDurationSeconds;
  @Nullable
  Double mDistanceMeters;
  @Nullable
  String mRouteShortName;
  @Nullable
  String mDisplayName;
  @Nullable
  String mTripShortName;
  @Nullable
  String mHeadsign;
  @Nullable
  String mDirection;
  @Nullable
  String mAgencyName;
  @Nullable
  String mRouteColor;
  @Nullable
  String mRouteTextColor;
  @Nullable
  String mRouteId;
  @Nullable
  String mTripId;
  boolean mCancelled;
  boolean mInterlineWithPreviousLeg;
  @Nullable
  Boolean mBikesAllowed;
  @NonNull
  List<TransitPlace> mIntermediateStops = Collections.emptyList();
  @NonNull
  List<String> mAlerts = Collections.emptyList();
  @Nullable
  String mEncodedPolyline;
  int mPolylinePrecision = 6;
  @Nullable
  private double[] mPolyline;

  TransitLeg() {}

  @NonNull
  public TransitMode getMode()
  {
    return mMode;
  }

  /** True for rides with public transport, false for walking, cycling etc. */
  public boolean isTransit()
  {
    return mMode.isTransit();
  }

  public boolean isWalk()
  {
    return mMode == TransitMode.WALK;
  }

  @NonNull
  public TransitPlace getFrom()
  {
    return mFrom;
  }

  @NonNull
  public TransitPlace getTo()
  {
    return mTo;
  }

  /** Expected start (realtime if available). */
  @NonNull
  public Instant getStartTime()
  {
    return mStartTime;
  }

  /** Expected end (realtime if available). */
  @NonNull
  public Instant getEndTime()
  {
    return mEndTime;
  }

  @NonNull
  public Instant getScheduledStartTime()
  {
    return mScheduledStartTime;
  }

  @NonNull
  public Instant getScheduledEndTime()
  {
    return mScheduledEndTime;
  }

  /** True if realtime data is available for this leg. */
  public boolean isRealTime()
  {
    return mRealTime;
  }

  /** Departure delay in seconds, negative if early, 0 without realtime data. */
  public long getDepartureDelaySeconds()
  {
    final Long delay = TransitPlace.delaySeconds(mScheduledStartTime, mStartTime);
    return delay == null ? 0 : delay;
  }

  /** Arrival delay in seconds, negative if early, 0 without realtime data. */
  public long getArrivalDelaySeconds()
  {
    final Long delay = TransitPlace.delaySeconds(mScheduledEndTime, mEndTime);
    return delay == null ? 0 : delay;
  }

  public long getDurationSeconds()
  {
    return mDurationSeconds;
  }

  /** Distance in meters, known for street legs (e.g. walking), otherwise null. */
  @Nullable
  public Double getDistanceMeters()
  {
    return mDistanceMeters;
  }

  /** Short line name as published by the operator, e.g. "S7" or "M4". */
  @Nullable
  public String getRouteShortName()
  {
    return mRouteShortName;
  }

  /** Name to display for the line, e.g. "S7" or "ICE 1601". */
  @Nullable
  public String getDisplayName()
  {
    return mDisplayName;
  }

  /** Best available line name: display name, route short name or trip short name. */
  @Nullable
  public String getLineName()
  {
    if (mDisplayName != null && !mDisplayName.isEmpty())
      return mDisplayName;
    if (mRouteShortName != null && !mRouteShortName.isEmpty())
      return mRouteShortName;
    return mTripShortName;
  }

  /** Destination sign of the vehicle, e.g. "S Potsdam Hauptbahnhof". */
  @Nullable
  public String getHeadsign()
  {
    return mHeadsign;
  }

  /** Direction for display: the headsign, or the final stop of the trip if there is no headsign. */
  @Nullable
  public String getDirection()
  {
    return mDirection;
  }

  @Nullable
  public String getAgencyName()
  {
    return mAgencyName;
  }

  /** Route color as hex RGB without '#', e.g. "008D4F", if published. */
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
  public String getRouteId()
  {
    return mRouteId;
  }

  @Nullable
  public String getTripId()
  {
    return mTripId;
  }

  public boolean isCancelled()
  {
    return mCancelled;
  }

  /** True if the passenger stays in the vehicle from the previous leg. */
  public boolean isInterlineWithPreviousLeg()
  {
    return mInterlineWithPreviousLeg;
  }

  @Nullable
  public Boolean getBikesAllowed()
  {
    return mBikesAllowed;
  }

  /** Stops between {@link #getFrom()} and {@link #getTo()} of a transit leg. */
  @NonNull
  public List<TransitPlace> getIntermediateStops()
  {
    return mIntermediateStops;
  }

  /** Header texts of service alerts for this leg. */
  @NonNull
  public List<String> getAlerts()
  {
    return mAlerts;
  }

  @Nullable
  public String getEncodedPolyline()
  {
    return mEncodedPolyline;
  }

  public int getPolylinePrecision()
  {
    return mPolylinePrecision;
  }

  /**
   * @return geometry of the leg as interleaved coordinates {lat0, lon0, lat1, lon1, ...}; decoded lazily.
   */
  @NonNull
  public synchronized double[] getPolyline()
  {
    if (mPolyline == null)
      mPolyline = PolylineDecoder.decode(mEncodedPolyline, mPolylinePrecision);
    return mPolyline;
  }

  @NonNull
  @Override
  public String toString()
  {
    final String line = getLineName();
    return mMode + (line != null ? " " + line : "") + (mDirection != null ? " -> " + mDirection : "") + " ["
  + mFrom.getName() + " " + mStartTime + " - " + mTo.getName() + " " + mEndTime + "]";
  }
}
