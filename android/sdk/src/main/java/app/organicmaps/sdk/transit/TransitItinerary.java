package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A journey from start to destination, consisting of transit and walking legs. */
public final class TransitItinerary
{
  @NonNull
  String mId = "";
  @NonNull
  Instant mStartTime = Instant.EPOCH;
  @NonNull
  Instant mEndTime = Instant.EPOCH;
  long mDurationSeconds;
  int mTransfers;
  @NonNull
  List<TransitLeg> mLegs = Collections.emptyList();

  TransitItinerary() {}

  /** Server-side id of the itinerary (opaque string). */
  @NonNull
  public String getId()
  {
    return mId;
  }

  @NonNull
  public Instant getStartTime()
  {
    return mStartTime;
  }

  @NonNull
  public Instant getEndTime()
  {
    return mEndTime;
  }

  public long getDurationSeconds()
  {
    return mDurationSeconds;
  }

  /** Number of changes between transit vehicles. */
  public int getTransfers()
  {
    return mTransfers;
  }

  @NonNull
  public List<TransitLeg> getLegs()
  {
    return mLegs;
  }

  @NonNull
  public List<TransitLeg> getTransitLegs()
  {
    final List<TransitLeg> result = new ArrayList<>();
    for (TransitLeg leg : mLegs)
      if (leg.isTransit())
        result.add(leg);
    return result;
  }

  @Nullable
  public TransitLeg getFirstTransitLeg()
  {
    for (TransitLeg leg : mLegs)
      if (leg.isTransit())
        return leg;
    return null;
  }

  @Nullable
  public TransitLeg getLastTransitLeg()
  {
    for (int i = mLegs.size() - 1; i >= 0; --i)
      if (mLegs.get(i).isTransit())
        return mLegs.get(i);
    return null;
  }

  /** Total walking distance in meters (legs with unknown distance are ignored). */
  public double getWalkingDistanceMeters()
  {
    double distance = 0;
    for (TransitLeg leg : mLegs)
      if (leg.isWalk() && leg.getDistanceMeters() != null)
        distance += leg.getDistanceMeters();
    return distance;
  }

  public long getWalkingDurationSeconds()
  {
    long duration = 0;
    for (TransitLeg leg : mLegs)
      if (leg.isWalk())
        duration += leg.getDurationSeconds();
    return duration;
  }

  /** True if realtime data is available for at least one transit leg. */
  public boolean hasRealTime()
  {
    for (TransitLeg leg : mLegs)
      if (leg.isTransit() && leg.isRealTime())
        return true;
    return false;
  }

  /** True if any transit leg is cancelled, i.e. the itinerary is not possible as planned. */
  public boolean isCancelled()
  {
    for (TransitLeg leg : mLegs)
      if (leg.isCancelled())
        return true;
    return false;
  }

  /** Delay of the first transit departure in seconds (0 without realtime data or without transit). */
  public long getDepartureDelaySeconds()
  {
    final TransitLeg leg = getFirstTransitLeg();
    return leg == null ? 0 : leg.getDepartureDelaySeconds();
  }

  /** Delay of the last transit arrival in seconds (0 without realtime data or without transit). */
  public long getArrivalDelaySeconds()
  {
    final TransitLeg leg = getLastTransitLeg();
    return leg == null ? 0 : leg.getArrivalDelaySeconds();
  }

  @NonNull
  @Override
  public String toString()
  {
    final StringBuilder sb = new StringBuilder();
    sb.append(mStartTime).append(" - ").append(mEndTime).append(", ").append(mTransfers).append(" transfers:");
    for (TransitLeg leg : mLegs)
      sb.append("\n  ").append(leg);
    return sb.toString();
  }
}
