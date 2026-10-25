package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.motis.model.Alert;
import app.organicmaps.sdk.motis.model.EncodedPolyline;
import app.organicmaps.sdk.motis.model.Itinerary;
import app.organicmaps.sdk.motis.model.Leg;
import app.organicmaps.sdk.motis.model.Match;
import app.organicmaps.sdk.motis.model.Mode;
import app.organicmaps.sdk.motis.model.Place;
import app.organicmaps.sdk.motis.model.Plan200Response;
import app.organicmaps.sdk.motis.model.StopTime;
import app.organicmaps.sdk.motis.model.Stoptimes200Response;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Maps the generated MOTIS DTOs to the public model classes of this package. */
final class MotisMapper
{
  private MotisMapper() {}

  @NonNull
  static TransitPlan toPlan(@NonNull Plan200Response response)
  {
    final TransitPlan plan = new TransitPlan();
    if (response.getFrom() != null)
      plan.mFrom = toPlace(response.getFrom());
    if (response.getTo() != null)
      plan.mTo = toPlace(response.getTo());
    plan.mItineraries = toItineraries(response.getItineraries());
    plan.mDirect = toItineraries(response.getDirect());
    plan.mPreviousPageCursor = emptyToNull(response.getPreviousPageCursor());
    plan.mNextPageCursor = emptyToNull(response.getNextPageCursor());
    return plan;
  }

  @NonNull
  static List<TransitItinerary> toItineraries(@Nullable List<Itinerary> itineraries)
  {
    if (itineraries == null || itineraries.isEmpty())
      return Collections.emptyList();
    final List<TransitItinerary> result = new ArrayList<>(itineraries.size());
    for (Itinerary itinerary : itineraries)
      result.add(toItinerary(itinerary));
    return Collections.unmodifiableList(result);
  }

  @NonNull
  static TransitItinerary toItinerary(@NonNull Itinerary itinerary)
  {
    final TransitItinerary result = new TransitItinerary();
    result.mId = nonNull(itinerary.getId());
    result.mStartTime = toInstantOrEpoch(itinerary.getStartTime());
    result.mEndTime = toInstantOrEpoch(itinerary.getEndTime());
    result.mDurationSeconds = toLong(itinerary.getDuration());
    result.mTransfers = itinerary.getTransfers() == null ? 0 : itinerary.getTransfers();
    final List<Leg> legs = itinerary.getLegs();
    if (legs != null && !legs.isEmpty())
    {
      final List<TransitLeg> mapped = new ArrayList<>(legs.size());
      for (Leg leg : legs)
        mapped.add(toLeg(leg));
      result.mLegs = Collections.unmodifiableList(mapped);
    }
    return result;
  }

  @NonNull
  static TransitLeg toLeg(@NonNull Leg leg)
  {
    final TransitLeg result = new TransitLeg();
    result.mMode = toMode(leg.getMode());
    if (leg.getFrom() != null)
      result.mFrom = toPlace(leg.getFrom());
    if (leg.getTo() != null)
      result.mTo = toPlace(leg.getTo());
    result.mStartTime = toInstantOrEpoch(leg.getStartTime());
    result.mEndTime = toInstantOrEpoch(leg.getEndTime());
    // Scheduled times are required by the API, fall back to the actual times just in case.
    result.mScheduledStartTime =
        leg.getScheduledStartTime() != null ? leg.getScheduledStartTime().toInstant() : result.mStartTime;
    result.mScheduledEndTime =
        leg.getScheduledEndTime() != null ? leg.getScheduledEndTime().toInstant() : result.mEndTime;
    result.mRealTime = Boolean.TRUE.equals(leg.getRealTime());
    result.mDurationSeconds = toLong(leg.getDuration());
    result.mDistanceMeters = toDouble(leg.getDistance());
    result.mRouteShortName = emptyToNull(leg.getRouteShortName());
    result.mDisplayName = emptyToNull(leg.getDisplayName());
    result.mTripShortName = emptyToNull(leg.getTripShortName());
    result.mHeadsign = emptyToNull(leg.getHeadsign());
    result.mDirection = direction(result.mHeadsign, leg.getTripTo());
    result.mAgencyName = emptyToNull(leg.getAgencyName());
    result.mRouteColor = emptyToNull(leg.getRouteColor());
    result.mRouteTextColor = emptyToNull(leg.getRouteTextColor());
    result.mRouteId = emptyToNull(leg.getRouteId());
    result.mTripId = emptyToNull(leg.getTripId());
    result.mCancelled = Boolean.TRUE.equals(leg.getCancelled());
    result.mInterlineWithPreviousLeg = Boolean.TRUE.equals(leg.getInterlineWithPreviousLeg());
    result.mBikesAllowed = leg.getBikesAllowed();
    result.mIntermediateStops = toPlaces(leg.getIntermediateStops());
    result.mAlerts = toAlertTexts(leg.getAlerts());
    final EncodedPolyline geometry = leg.getLegGeometry();
    if (geometry != null)
    {
      result.mEncodedPolyline = emptyToNull(geometry.getPoints());
      if (geometry.getPrecision() != null)
        result.mPolylinePrecision = geometry.getPrecision();
    }
    return result;
  }

  @NonNull
  static TransitPlace toPlace(@NonNull Place place)
  {
    final TransitPlace result = new TransitPlace();
    result.mName = nonNull(place.getName());
    result.mStopId = emptyToNull(place.getStopId());
    result.mLat = toDoubleOrNaN(place.getLat());
    result.mLon = toDoubleOrNaN(place.getLon());
    result.mTimeZone = emptyToNull(place.getTz());
    result.mArrival = toInstant(place.getArrival());
    result.mScheduledArrival = toInstant(place.getScheduledArrival());
    result.mDeparture = toInstant(place.getDeparture());
    result.mScheduledDeparture = toInstant(place.getScheduledDeparture());
    result.mTrack = emptyToNull(place.getTrack());
    result.mScheduledTrack = emptyToNull(place.getScheduledTrack());
    result.mCancelled = Boolean.TRUE.equals(place.getCancelled());
    return result;
  }

  @NonNull
  static List<TransitPlace> toPlaces(@Nullable List<Place> places)
  {
    if (places == null || places.isEmpty())
      return Collections.emptyList();
    final List<TransitPlace> result = new ArrayList<>(places.size());
    for (Place place : places)
      result.add(toPlace(place));
    return Collections.unmodifiableList(result);
  }

  @NonNull
  static List<TransitStop> toStops(@Nullable List<Match> matches)
  {
    if (matches == null || matches.isEmpty())
      return Collections.emptyList();
    final List<TransitStop> result = new ArrayList<>(matches.size());
    for (Match match : matches)
    {
      final TransitStop stop = new TransitStop();
      stop.mId = nonNull(match.getId());
      stop.mName = nonNull(match.getName());
      stop.mLat = toDoubleOrNaN(match.getLat());
      stop.mLon = toDoubleOrNaN(match.getLon());
      stop.mModes = toModes(match.getModes());
      result.add(stop);
    }
    return Collections.unmodifiableList(result);
  }

  @NonNull
  static TransitDepartures toDepartures(@NonNull Stoptimes200Response response)
  {
    final TransitDepartures result = new TransitDepartures();
    if (response.getPlace() != null)
      result.mStop = toPlace(response.getPlace());
    final List<StopTime> stopTimes = response.getStopTimes();
    if (stopTimes != null && !stopTimes.isEmpty())
    {
      final List<TransitDeparture> departures = new ArrayList<>(stopTimes.size());
      for (StopTime stopTime : stopTimes)
        departures.add(toDeparture(stopTime));
      result.mDepartures = Collections.unmodifiableList(departures);
    }
    result.mPreviousPageCursor = emptyToNull(response.getPreviousPageCursor());
    result.mNextPageCursor = emptyToNull(response.getNextPageCursor());
    return result;
  }

  @NonNull
  static TransitDeparture toDeparture(@NonNull StopTime stopTime)
  {
    final TransitDeparture result = new TransitDeparture();
    result.mMode = toMode(stopTime.getMode());
    if (stopTime.getPlace() != null)
      result.mPlace = toPlace(stopTime.getPlace());
    result.mRouteShortName = emptyToNull(stopTime.getRouteShortName());
    result.mDisplayName = emptyToNull(stopTime.getDisplayName());
    result.mHeadsign = emptyToNull(stopTime.getHeadsign());
    result.mDirection = direction(result.mHeadsign, stopTime.getTripTo());
    result.mAgencyName = emptyToNull(stopTime.getAgencyName());
    result.mRouteColor = emptyToNull(stopTime.getRouteColor());
    result.mRouteTextColor = emptyToNull(stopTime.getRouteTextColor());
    result.mTripId = emptyToNull(stopTime.getTripId());
    result.mRealTime = Boolean.TRUE.equals(stopTime.getRealTime());
    result.mCancelled = Boolean.TRUE.equals(stopTime.getCancelled()) || Boolean.TRUE.equals(stopTime.getTripCancelled())
                     || result.mPlace.isCancelled();
    return result;
  }

  @NonNull
  static TransitMode toMode(@Nullable Mode mode)
  {
    return mode == null ? TransitMode.UNKNOWN : TransitMode.fromApi(mode.getValue());
  }

  @NonNull
  private static Set<TransitMode> toModes(@Nullable List<Mode> modes)
  {
    if (modes == null || modes.isEmpty())
      return Collections.emptySet();
    final Set<TransitMode> result = EnumSet.noneOf(TransitMode.class);
    for (Mode mode : modes)
      result.add(toMode(mode));
    return Collections.unmodifiableSet(result);
  }

  @NonNull
  private static List<String> toAlertTexts(@Nullable List<Alert> alerts)
  {
    if (alerts == null || alerts.isEmpty())
      return Collections.emptyList();
    final List<String> result = new ArrayList<>(alerts.size());
    for (Alert alert : alerts)
    {
      final String text = emptyToNull(alert.getHeaderText());
      if (text != null)
        result.add(text);
    }
    return Collections.unmodifiableList(result);
  }

  @Nullable
  private static String direction(@Nullable String headsign, @Nullable Place tripTo)
  {
    if (headsign != null)
      return headsign;
    return tripTo == null ? null : emptyToNull(tripTo.getName());
  }

  @Nullable
  private static Instant toInstant(@Nullable OffsetDateTime time)
  {
    return time == null ? null : time.toInstant();
  }

  @NonNull
  private static Instant toInstantOrEpoch(@Nullable OffsetDateTime time)
  {
    return time == null ? Instant.EPOCH : time.toInstant();
  }

  private static long toLong(@Nullable Integer value)
  {
    return value == null ? 0 : value;
  }

  @Nullable
  private static Double toDouble(@Nullable BigDecimal value)
  {
    return value == null ? null : value.doubleValue();
  }

  private static double toDoubleOrNaN(@Nullable BigDecimal value)
  {
    return value == null ? Double.NaN : value.doubleValue();
  }

  @NonNull
  private static String nonNull(@Nullable String value)
  {
    return value == null ? "" : value;
  }

  @Nullable
  private static String emptyToNull(@Nullable String value)
  {
    return value == null || value.isEmpty() ? null : value;
  }
}
