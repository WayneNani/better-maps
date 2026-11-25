package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds itineraries from legs of other itineraries, e.g. to replace one ride of a connection with another departure
 * of the same or another line. The models are immutable; these helpers create new objects.
 */
public final class TransitItineraries
{
  private TransitItineraries() {}

  /**
   * An itinerary of the given legs in travel order. Start and end are those of the first and the last leg, the
   * duration is the time between them, and the transfers are the changes between transit vehicles (staying in an
   * interlined vehicle is no change).
   *
   * @throws IllegalArgumentException if there are no legs.
   */
  @NonNull
  public static TransitItinerary of(@NonNull String id, @NonNull List<TransitLeg> legs)
  {
    if (legs.isEmpty())
      throw new IllegalArgumentException("An itinerary needs at least one leg");
    final TransitItinerary itinerary = new TransitItinerary();
    itinerary.mId = id;
    itinerary.mLegs = Collections.unmodifiableList(new ArrayList<>(legs));
    itinerary.mStartTime = legs.get(0).mStartTime;
    itinerary.mEndTime = legs.get(legs.size() - 1).mEndTime;
    itinerary.mDurationSeconds = Math.max(0, Duration.between(itinerary.mStartTime, itinerary.mEndTime).getSeconds());
    int rides = 0;
    for (TransitLeg leg : legs)
      if (leg.isTransit() && !leg.mInterlineWithPreviousLeg)
        ++rides;
    itinerary.mTransfers = Math.max(0, rides - 1);
    return itinerary;
  }

  /**
   * A copy of the leg moved in time so that it starts at {@code start}, with the same duration. All times of the
   * leg and its places move by the same amount. Meant for walks, e.g. a transfer that starts later after another
   * ride; a ride keeps its timetable and is never moved.
   */
  @NonNull
  public static TransitLeg startingAt(@NonNull TransitLeg leg, @NonNull Instant start)
  {
    final Duration shift = Duration.between(leg.mStartTime, start);
    final TransitLeg copy = new TransitLeg();
    copy.mMode = leg.mMode;
    copy.mFrom = shifted(leg.mFrom, shift);
    copy.mTo = shifted(leg.mTo, shift);
    copy.mStartTime = leg.mStartTime.plus(shift);
    copy.mEndTime = leg.mEndTime.plus(shift);
    copy.mScheduledStartTime = leg.mScheduledStartTime.plus(shift);
    copy.mScheduledEndTime = leg.mScheduledEndTime.plus(shift);
    copy.mRealTime = leg.mRealTime;
    copy.mDurationSeconds = leg.mDurationSeconds;
    copy.mDistanceMeters = leg.mDistanceMeters;
    copy.mRouteShortName = leg.mRouteShortName;
    copy.mDisplayName = leg.mDisplayName;
    copy.mTripShortName = leg.mTripShortName;
    copy.mHeadsign = leg.mHeadsign;
    copy.mDirection = leg.mDirection;
    copy.mAgencyName = leg.mAgencyName;
    copy.mRouteColor = leg.mRouteColor;
    copy.mRouteTextColor = leg.mRouteTextColor;
    copy.mRouteId = leg.mRouteId;
    copy.mTripId = leg.mTripId;
    copy.mCancelled = leg.mCancelled;
    copy.mInterlineWithPreviousLeg = leg.mInterlineWithPreviousLeg;
    copy.mBikesAllowed = leg.mBikesAllowed;
    final List<TransitPlace> stops = new ArrayList<>(leg.mIntermediateStops.size());
    for (TransitPlace stop : leg.mIntermediateStops)
      stops.add(shifted(stop, shift));
    copy.mIntermediateStops = Collections.unmodifiableList(stops);
    copy.mAlerts = leg.mAlerts;
    copy.mEncodedPolyline = leg.mEncodedPolyline;
    copy.mPolylinePrecision = leg.mPolylinePrecision;
    return copy;
  }

  @NonNull
  private static TransitPlace shifted(@NonNull TransitPlace place, @NonNull Duration shift)
  {
    final TransitPlace copy = new TransitPlace();
    copy.mName = place.mName;
    copy.mStopId = place.mStopId;
    copy.mLat = place.mLat;
    copy.mLon = place.mLon;
    copy.mTimeZone = place.mTimeZone;
    copy.mArrival = plus(place.mArrival, shift);
    copy.mScheduledArrival = plus(place.mScheduledArrival, shift);
    copy.mDeparture = plus(place.mDeparture, shift);
    copy.mScheduledDeparture = plus(place.mScheduledDeparture, shift);
    copy.mTrack = place.mTrack;
    copy.mScheduledTrack = place.mScheduledTrack;
    copy.mCancelled = place.mCancelled;
    return copy;
  }

  @Nullable
  private static Instant plus(@Nullable Instant time, @NonNull Duration shift)
  {
    return time == null ? null : time.plus(shift);
  }
}
