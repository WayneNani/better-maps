package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Converts itineraries to and from JSON for storing them on the device (e.g. saved connections). The format is
 * independent of the MOTIS API and of the field names of the model classes: times are ISO-8601 strings in UTC,
 * modes their {@link TransitMode} names, optional values are left out. Unknown fields are ignored and missing
 * optional fields get their defaults, so that older and newer app versions can read each other's data.
 */
public final class TransitItineraryJson
{
  private TransitItineraryJson() {}

  @NonNull
  public static JsonObject toJson(@NonNull TransitItinerary itinerary)
  {
    final JsonObject json = new JsonObject();
    json.addProperty("id", itinerary.mId);
    json.addProperty("start", itinerary.mStartTime.toString());
    json.addProperty("end", itinerary.mEndTime.toString());
    json.addProperty("duration", itinerary.mDurationSeconds);
    json.addProperty("transfers", itinerary.mTransfers);
    final JsonArray legs = new JsonArray();
    for (TransitLeg leg : itinerary.mLegs)
      legs.add(toJson(leg));
    json.add("legs", legs);
    return json;
  }

  /** @throws JsonParseException if a required value is missing or invalid. */
  @NonNull
  public static TransitItinerary fromJson(@NonNull JsonObject json)
  {
    final TransitItinerary itinerary = new TransitItinerary();
    itinerary.mId = string(json, "id", "");
    itinerary.mStartTime = requiredInstant(json, "start");
    itinerary.mEndTime = requiredInstant(json, "end");
    itinerary.mDurationSeconds =
        longValue(json, "duration", itinerary.mEndTime.getEpochSecond() - itinerary.mStartTime.getEpochSecond());
    itinerary.mTransfers = (int) longValue(json, "transfers", 0);
    final List<TransitLeg> legs = new ArrayList<>();
    for (JsonElement leg : array(json, "legs"))
      legs.add(legFromJson(object(leg, "leg")));
    itinerary.mLegs = Collections.unmodifiableList(legs);
    return itinerary;
  }

  @NonNull
  private static JsonObject toJson(@NonNull TransitLeg leg)
  {
    final JsonObject json = new JsonObject();
    json.addProperty("mode", leg.mMode.name());
    json.add("from", toJson(leg.mFrom));
    json.add("to", toJson(leg.mTo));
    json.addProperty("start", leg.mStartTime.toString());
    json.addProperty("end", leg.mEndTime.toString());
    json.addProperty("scheduledStart", leg.mScheduledStartTime.toString());
    json.addProperty("scheduledEnd", leg.mScheduledEndTime.toString());
    if (leg.mRealTime)
      json.addProperty("realTime", true);
    json.addProperty("duration", leg.mDurationSeconds);
    putFinite(json, "distance", leg.mDistanceMeters);
    putString(json, "routeShortName", leg.mRouteShortName);
    putString(json, "displayName", leg.mDisplayName);
    putString(json, "tripShortName", leg.mTripShortName);
    putString(json, "headsign", leg.mHeadsign);
    putString(json, "direction", leg.mDirection);
    putString(json, "agency", leg.mAgencyName);
    putString(json, "routeColor", leg.mRouteColor);
    putString(json, "routeTextColor", leg.mRouteTextColor);
    putString(json, "routeId", leg.mRouteId);
    putString(json, "tripId", leg.mTripId);
    if (leg.mCancelled)
      json.addProperty("cancelled", true);
    if (leg.mInterlineWithPreviousLeg)
      json.addProperty("interline", true);
    if (leg.mBikesAllowed != null)
      json.addProperty("bikesAllowed", leg.mBikesAllowed);
    if (!leg.mIntermediateStops.isEmpty())
    {
      final JsonArray stops = new JsonArray();
      for (TransitPlace stop : leg.mIntermediateStops)
        stops.add(toJson(stop));
      json.add("stops", stops);
    }
    if (!leg.mAlerts.isEmpty())
    {
      final JsonArray alerts = new JsonArray();
      for (String alert : leg.mAlerts)
        alerts.add(alert);
      json.add("alerts", alerts);
    }
    putString(json, "polyline", leg.mEncodedPolyline);
    json.addProperty("polylinePrecision", leg.mPolylinePrecision);
    return json;
  }

  @NonNull
  private static TransitLeg legFromJson(@NonNull JsonObject json)
  {
    final TransitLeg leg = new TransitLeg();
    leg.mMode = TransitMode.fromApi(string(json, "mode", null));
    leg.mFrom = placeFromJson(object(json.get("from"), "from"));
    leg.mTo = placeFromJson(object(json.get("to"), "to"));
    leg.mStartTime = requiredInstant(json, "start");
    leg.mEndTime = requiredInstant(json, "end");
    final Instant scheduledStart = instant(json, "scheduledStart");
    final Instant scheduledEnd = instant(json, "scheduledEnd");
    leg.mScheduledStartTime = scheduledStart != null ? scheduledStart : leg.mStartTime;
    leg.mScheduledEndTime = scheduledEnd != null ? scheduledEnd : leg.mEndTime;
    leg.mRealTime = bool(json, "realTime");
    leg.mDurationSeconds = longValue(json, "duration", leg.mEndTime.getEpochSecond() - leg.mStartTime.getEpochSecond());
    leg.mDistanceMeters = doubleOrNull(json, "distance");
    leg.mRouteShortName = string(json, "routeShortName", null);
    leg.mDisplayName = string(json, "displayName", null);
    leg.mTripShortName = string(json, "tripShortName", null);
    leg.mHeadsign = string(json, "headsign", null);
    leg.mDirection = string(json, "direction", null);
    leg.mAgencyName = string(json, "agency", null);
    leg.mRouteColor = string(json, "routeColor", null);
    leg.mRouteTextColor = string(json, "routeTextColor", null);
    leg.mRouteId = string(json, "routeId", null);
    leg.mTripId = string(json, "tripId", null);
    leg.mCancelled = bool(json, "cancelled");
    leg.mInterlineWithPreviousLeg = bool(json, "interline");
    final JsonElement bikes = json.get("bikesAllowed");
    leg.mBikesAllowed = bikes != null && bikes.isJsonPrimitive() ? bikes.getAsBoolean() : null;
    final List<TransitPlace> stops = new ArrayList<>();
    for (JsonElement stop : array(json, "stops"))
      stops.add(placeFromJson(object(stop, "stop")));
    leg.mIntermediateStops = Collections.unmodifiableList(stops);
    final List<String> alerts = new ArrayList<>();
    for (JsonElement alert : array(json, "alerts"))
      if (alert.isJsonPrimitive())
        alerts.add(alert.getAsString());
    leg.mAlerts = Collections.unmodifiableList(alerts);
    leg.mEncodedPolyline = string(json, "polyline", null);
    leg.mPolylinePrecision = (int) longValue(json, "polylinePrecision", 6);
    return leg;
  }

  @NonNull
  private static JsonObject toJson(@NonNull TransitPlace place)
  {
    final JsonObject json = new JsonObject();
    json.addProperty("name", place.mName);
    putString(json, "stopId", place.mStopId);
    putFinite(json, "lat", place.mLat);
    putFinite(json, "lon", place.mLon);
    putString(json, "timeZone", place.mTimeZone);
    putInstant(json, "arrival", place.mArrival);
    putInstant(json, "scheduledArrival", place.mScheduledArrival);
    putInstant(json, "departure", place.mDeparture);
    putInstant(json, "scheduledDeparture", place.mScheduledDeparture);
    putString(json, "track", place.mTrack);
    putString(json, "scheduledTrack", place.mScheduledTrack);
    if (place.mCancelled)
      json.addProperty("cancelled", true);
    return json;
  }

  @NonNull
  private static TransitPlace placeFromJson(@NonNull JsonObject json)
  {
    final TransitPlace place = new TransitPlace();
    place.mName = string(json, "name", "");
    place.mStopId = string(json, "stopId", null);
    final Double lat = doubleOrNull(json, "lat");
    final Double lon = doubleOrNull(json, "lon");
    place.mLat = lat != null ? lat : Double.NaN;
    place.mLon = lon != null ? lon : Double.NaN;
    place.mTimeZone = string(json, "timeZone", null);
    place.mArrival = instant(json, "arrival");
    place.mScheduledArrival = instant(json, "scheduledArrival");
    place.mDeparture = instant(json, "departure");
    place.mScheduledDeparture = instant(json, "scheduledDeparture");
    place.mTrack = string(json, "track", null);
    place.mScheduledTrack = string(json, "scheduledTrack", null);
    place.mCancelled = bool(json, "cancelled");
    return place;
  }

  private static void putString(@NonNull JsonObject json, @NonNull String key, @Nullable String value)
  {
    if (value != null)
      json.addProperty(key, value);
  }

  private static void putInstant(@NonNull JsonObject json, @NonNull String key, @Nullable Instant value)
  {
    if (value != null)
      json.addProperty(key, value.toString());
  }

  // JSON has no NaN or infinity.
  private static void putFinite(@NonNull JsonObject json, @NonNull String key, @Nullable Double value)
  {
    if (value != null && !value.isNaN() && !value.isInfinite())
      json.addProperty(key, value);
  }

  @NonNull
  private static JsonObject object(@Nullable JsonElement element, @NonNull String what)
  {
    if (element == null || !element.isJsonObject())
      throw new JsonParseException("Missing or invalid " + what);
    return element.getAsJsonObject();
  }

  @NonNull
  private static JsonArray array(@NonNull JsonObject json, @NonNull String key)
  {
    final JsonElement element = json.get(key);
    return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
  }

  @Nullable
  private static String string(@NonNull JsonObject json, @NonNull String key, @Nullable String fallback)
  {
    final JsonElement element = json.get(key);
    return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
  }

  private static boolean bool(@NonNull JsonObject json, @NonNull String key)
  {
    final JsonElement element = json.get(key);
    return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
 && element.getAsBoolean();
  }

  private static long longValue(@NonNull JsonObject json, @NonNull String key, long fallback)
  {
    final JsonElement element = json.get(key);
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber())
      return fallback;
    return element.getAsLong();
  }

  @Nullable
  private static Double doubleOrNull(@NonNull JsonObject json, @NonNull String key)
  {
    final JsonElement element = json.get(key);
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber())
      return null;
    return element.getAsDouble();
  }

  @Nullable
  private static Instant instant(@NonNull JsonObject json, @NonNull String key)
  {
    final String value = string(json, key, null);
    if (value == null)
      return null;
    try
    {
      return Instant.parse(value);
    }
    catch (DateTimeException e)
    {
      throw new JsonParseException("Invalid time " + key + ": " + value, e);
    }
  }

  @NonNull
  private static Instant requiredInstant(@NonNull JsonObject json, @NonNull String key)
  {
    final Instant value = instant(json, key);
    if (value == null)
      throw new JsonParseException("Missing time " + key);
    return value;
  }
}
