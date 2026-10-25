package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Builds SDK model objects for app tests. Lives in the SDK package because the model constructors and fields are
 * package-private (the models are normally created by the MOTIS mapper only).
 */
public final class TransitTestData
{
  private TransitTestData() {}

  @NonNull
  public static Instant t(@NonNull String isoUtc)
  {
    return Instant.parse(isoUtc);
  }

  @NonNull
  public static PlaceBuilder place(@NonNull String name)
  {
    return new PlaceBuilder(name);
  }

  @NonNull
  public static LegBuilder leg(@NonNull TransitMode mode)
  {
    return new LegBuilder(mode);
  }

  @NonNull
  public static TransitItinerary itinerary(@NonNull String id, @NonNull TransitLeg... legs)
  {
    final TransitItinerary itinerary = new TransitItinerary();
    itinerary.mId = id;
    itinerary.mLegs = Collections.unmodifiableList(Arrays.asList(legs));
    if (legs.length > 0)
    {
      itinerary.mStartTime = legs[0].mStartTime;
      itinerary.mEndTime = legs[legs.length - 1].mEndTime;
      itinerary.mDurationSeconds = itinerary.mEndTime.getEpochSecond() - itinerary.mStartTime.getEpochSecond();
    }
    int transit = 0;
    for (TransitLeg leg : legs)
      if (leg.isTransit())
        ++transit;
    itinerary.mTransfers = Math.max(0, transit - 1);
    return itinerary;
  }

  @NonNull
  public static TransitPlan plan(@Nullable String previousCursor, @Nullable String nextCursor,
                                 @NonNull TransitItinerary... itineraries)
  {
    final TransitPlan plan = new TransitPlan();
    plan.mItineraries = Collections.unmodifiableList(Arrays.asList(itineraries));
    plan.mPreviousPageCursor = previousCursor;
    plan.mNextPageCursor = nextCursor;
    return plan;
  }

  @NonNull
  public static TransitPlan planWithDirect(@NonNull List<TransitItinerary> itineraries,
                                           @NonNull List<TransitItinerary> direct)
  {
    final TransitPlan plan = new TransitPlan();
    plan.mItineraries = itineraries;
    plan.mDirect = direct;
    return plan;
  }

  @NonNull
  public static TransitDepartures departures(@NonNull String stopName, @NonNull TransitDeparture... departures)
  {
    final TransitDepartures result = new TransitDepartures();
    result.mStop.mName = stopName;
    result.mStop.mStopId = "stop:" + stopName;
    result.mDepartures = Collections.unmodifiableList(Arrays.asList(departures));
    return result;
  }

  @NonNull
  public static TransitDeparture departure(@NonNull TransitMode mode, @NonNull String line, @NonNull String direction,
                                           @NonNull TransitPlace place, boolean realTime, boolean cancelled,
                                           @Nullable String color)
  {
    final TransitDeparture departure = new TransitDeparture();
    departure.mMode = mode;
    departure.mDisplayName = line;
    departure.mRouteShortName = line;
    departure.mDirection = direction;
    departure.mHeadsign = direction;
    departure.mPlace = place;
    departure.mRealTime = realTime;
    departure.mCancelled = cancelled;
    departure.mRouteColor = color;
    return departure;
  }

  public static final class PlaceBuilder
  {
    private final TransitPlace mPlace = new TransitPlace();

    PlaceBuilder(@NonNull String name)
    {
      mPlace.mName = name;
      mPlace.mTimeZone = "Europe/Berlin";
    }

    @NonNull
    public PlaceBuilder stop(@Nullable String stopId)
    {
      mPlace.mStopId = stopId;
      return this;
    }

    @NonNull
    public PlaceBuilder at(double lat, double lon)
    {
      mPlace.mLat = lat;
      mPlace.mLon = lon;
      return this;
    }

    @NonNull
    public PlaceBuilder zone(@Nullable String timeZone)
    {
      mPlace.mTimeZone = timeZone;
      return this;
    }

    /** Expected and scheduled departure. */
    @NonNull
    public PlaceBuilder departure(@Nullable Instant expected, @Nullable Instant scheduled)
    {
      mPlace.mDeparture = expected;
      mPlace.mScheduledDeparture = scheduled;
      return this;
    }

    @NonNull
    public PlaceBuilder arrival(@Nullable Instant expected, @Nullable Instant scheduled)
    {
      mPlace.mArrival = expected;
      mPlace.mScheduledArrival = scheduled;
      return this;
    }

    @NonNull
    public PlaceBuilder track(@Nullable String expected, @Nullable String scheduled)
    {
      mPlace.mTrack = expected;
      mPlace.mScheduledTrack = scheduled;
      return this;
    }

    @NonNull
    public PlaceBuilder cancelled()
    {
      mPlace.mCancelled = true;
      return this;
    }

    @NonNull
    public TransitPlace build()
    {
      return mPlace;
    }
  }

  public static final class LegBuilder
  {
    private final TransitLeg mLeg = new TransitLeg();

    LegBuilder(@NonNull TransitMode mode)
    {
      mLeg.mMode = mode;
    }

    @NonNull
    public LegBuilder from(@NonNull TransitPlace from)
    {
      mLeg.mFrom = from;
      return this;
    }

    @NonNull
    public LegBuilder to(@NonNull TransitPlace to)
    {
      mLeg.mTo = to;
      return this;
    }

    /** Scheduled times; realtime equals scheduled until {@link #delay} is called. */
    @NonNull
    public LegBuilder times(@NonNull Instant start, @NonNull Instant end)
    {
      mLeg.mScheduledStartTime = start;
      mLeg.mStartTime = start;
      mLeg.mScheduledEndTime = end;
      mLeg.mEndTime = end;
      mLeg.mDurationSeconds = end.getEpochSecond() - start.getEpochSecond();
      return this;
    }

    /** Realtime data with the given departure and arrival delays. */
    @NonNull
    public LegBuilder delay(long departureSeconds, long arrivalSeconds)
    {
      mLeg.mRealTime = true;
      mLeg.mStartTime = mLeg.mScheduledStartTime.plusSeconds(departureSeconds);
      mLeg.mEndTime = mLeg.mScheduledEndTime.plusSeconds(arrivalSeconds);
      return this;
    }

    @NonNull
    public LegBuilder line(@Nullable String name, @Nullable String color, @Nullable String textColor)
    {
      mLeg.mDisplayName = name;
      mLeg.mRouteShortName = name;
      mLeg.mRouteColor = color;
      mLeg.mRouteTextColor = textColor;
      return this;
    }

    @NonNull
    public LegBuilder direction(@Nullable String direction)
    {
      mLeg.mDirection = direction;
      mLeg.mHeadsign = direction;
      return this;
    }

    @NonNull
    public LegBuilder distance(@Nullable Double meters)
    {
      mLeg.mDistanceMeters = meters;
      return this;
    }

    @NonNull
    public LegBuilder stops(@NonNull TransitPlace... stops)
    {
      mLeg.mIntermediateStops = new ArrayList<>(Arrays.asList(stops));
      return this;
    }

    @NonNull
    public LegBuilder cancelled()
    {
      mLeg.mCancelled = true;
      return this;
    }

    @NonNull
    public LegBuilder alerts(@NonNull String... alerts)
    {
      mLeg.mAlerts = Arrays.asList(alerts);
      return this;
    }

    /** Polyline given as encoded string with precision 6 (see PolylineDecoder). */
    @NonNull
    public LegBuilder polyline(@Nullable String encoded)
    {
      mLeg.mEncodedPolyline = encoded;
      return this;
    }

    @NonNull
    public TransitLeg build()
    {
      return mLeg;
    }
  }
}
