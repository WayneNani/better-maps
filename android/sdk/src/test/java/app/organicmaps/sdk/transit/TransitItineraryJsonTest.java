package app.organicmaps.sdk.transit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.motis.model.Plan200Response;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.Collections;
import org.junit.Test;

/** Storing itineraries as JSON on the device. */
public class TransitItineraryJsonTest
{
  @Test
  public void recordedItinerariesSurviveARoundTrip() throws IOException
  {
    for (String fixture : new String[] {"plan_hbf_alex.json", "plan_regional.json", "plan_stopid_arriveby.json"})
    {
      final TransitPlan plan = MotisMapper.toPlan(Plan200Response.fromJson(Fixtures.read(fixture)));
      assertFalse(fixture, plan.getItineraries().isEmpty());
      for (TransitItinerary itinerary : plan.getItineraries())
      {
        // Through a string, as in the file.
        final String text = TransitItineraryJson.toJson(itinerary).toString();
        final TransitItinerary copy = TransitItineraryJson.fromJson(JsonParser.parseString(text).getAsJsonObject());
        assertItineraryEquals(itinerary, copy);
      }
    }
  }

  @Test
  public void realtimeAndScheduledTimesAreKept() throws IOException
  {
    final TransitPlan plan = MotisMapper.toPlan(Plan200Response.fromJson(Fixtures.read("plan_regional.json")));
    for (TransitItinerary itinerary : plan.getItineraries())
    {
      final TransitItinerary copy = roundTrip(itinerary);
      assertEquals(itinerary.getDepartureDelaySeconds(), copy.getDepartureDelaySeconds());
      assertEquals(itinerary.getArrivalDelaySeconds(), copy.getArrivalDelaySeconds());
      assertEquals(itinerary.hasRealTime(), copy.hasRealTime());
    }
  }

  @Test
  public void timesAreIsoStringsInUtc() throws IOException
  {
    final TransitPlan plan = MotisMapper.toPlan(Plan200Response.fromJson(Fixtures.read("plan_hbf_alex.json")));
    final JsonObject json = TransitItineraryJson.toJson(plan.getItineraries().get(0));
    assertEquals("2026-10-25T16:29:00Z", json.get("start").getAsString());
    assertEquals("WALK", json.getAsJsonArray("legs").get(0).getAsJsonObject().get("mode").getAsString());
  }

  @Test
  public void minimalJsonGetsDefaults()
  {
    final TransitItinerary itinerary = TransitItineraryJson.fromJson(
        parse("{\"start\":\"2026-10-25T16:00:00Z\",\"end\":\"2026-10-25T16:20:00Z\",\"legs\":[{\"mode\":\"BUS\","
              + "\"from\":{\"name\":\"A\"},\"to\":{\"name\":\"B\"},"
              + "\"start\":\"2026-10-25T16:05:00Z\",\"end\":\"2026-10-25T16:15:00Z\"}]}"));
    assertEquals("", itinerary.getId());
    assertEquals(1200, itinerary.getDurationSeconds());
    assertEquals(0, itinerary.getTransfers());
    final TransitLeg leg = itinerary.getLegs().get(0);
    assertEquals(TransitMode.BUS, leg.getMode());
    assertEquals(600, leg.getDurationSeconds());
    // Without scheduled times, the expected times count as scheduled: no delay.
    assertEquals(leg.getStartTime(), leg.getScheduledStartTime());
    assertEquals(0, leg.getDepartureDelaySeconds());
    assertFalse(leg.isRealTime());
    assertNull(leg.getLineName());
    assertNull(leg.getBikesAllowed());
    assertTrue(leg.getIntermediateStops().isEmpty());
    assertTrue(leg.getAlerts().isEmpty());
    assertEquals(0, leg.getPolyline().length);
    assertTrue(Double.isNaN(leg.getFrom().getLat()));
    assertFalse(leg.getFrom().isStop());
    assertNull(leg.getFrom().getDeparture());
  }

  @Test
  public void unknownValuesAreTolerated()
  {
    final TransitItinerary itinerary = TransitItineraryJson.fromJson(
        parse("{\"start\":\"2026-10-25T16:00:00Z\",\"end\":\"2026-10-25T16:20:00Z\",\"future\":{\"x\":1},"
              + "\"legs\":[{\"mode\":\"HOVERCRAFT\",\"from\":{\"name\":\"A\",\"extra\":[1]},\"to\":{\"name\":\"B\"},"
              + "\"start\":\"2026-10-25T16:05:00Z\",\"end\":\"2026-10-25T16:15:00Z\",\"duration\":\"long\","
              + "\"alerts\":[\"Works\",{\"text\":\"structured\"}]}]}"));
    final TransitLeg leg = itinerary.getLegs().get(0);
    assertEquals(TransitMode.UNKNOWN, leg.getMode());
    assertEquals(600, leg.getDurationSeconds());
    assertEquals(Collections.singletonList("Works"), leg.getAlerts());
  }

  @Test
  public void notANumberCoordinatesAreLeftOut() throws IOException
  {
    final TransitPlan plan = MotisMapper.toPlan(Plan200Response.fromJson(Fixtures.read("plan_hbf_alex.json")));
    final TransitItinerary itinerary = plan.getItineraries().get(0);
    itinerary.getLegs().get(0).mFrom.mLat = Double.NaN;
    final JsonObject json = TransitItineraryJson.toJson(itinerary);
    final JsonObject from = json.getAsJsonArray("legs").get(0).getAsJsonObject().getAsJsonObject("from");
    assertFalse(from.has("lat"));
    assertTrue(Double.isNaN(roundTrip(itinerary).getLegs().get(0).getFrom().getLat()));
  }

  @Test
  public void invalidJsonIsRejected()
  {
    assertRejected("{}");
    assertRejected("{\"start\":\"yesterday\",\"end\":\"2026-10-25T16:20:00Z\"}");
    assertRejected("{\"start\":\"2026-10-25T16:00:00Z\",\"end\":\"2026-10-25T16:20:00Z\",\"legs\":[1]}");
    assertRejected("{\"start\":\"2026-10-25T16:00:00Z\",\"end\":\"2026-10-25T16:20:00Z\",\"legs\":[{\"mode\":\"BUS\","
                   + "\"start\":\"2026-10-25T16:05:00Z\",\"end\":\"2026-10-25T16:15:00Z\"}]}");
  }

  private static void assertRejected(@NonNull String json)
  {
    try
    {
      TransitItineraryJson.fromJson(parse(json));
      fail("Accepted " + json);
    }
    catch (JsonParseException expected)
    {
      // Expected.
    }
  }

  @NonNull
  private static JsonObject parse(@NonNull String json)
  {
    return JsonParser.parseString(json).getAsJsonObject();
  }

  @NonNull
  private static TransitItinerary roundTrip(@NonNull TransitItinerary itinerary)
  {
    return TransitItineraryJson.fromJson(parse(TransitItineraryJson.toJson(itinerary).toString()));
  }

  private static void assertItineraryEquals(@NonNull TransitItinerary expected, @NonNull TransitItinerary actual)
  {
    assertEquals(expected.getId(), actual.getId());
    assertEquals(expected.getStartTime(), actual.getStartTime());
    assertEquals(expected.getEndTime(), actual.getEndTime());
    assertEquals(expected.getDurationSeconds(), actual.getDurationSeconds());
    assertEquals(expected.getTransfers(), actual.getTransfers());
    assertEquals(expected.getLegs().size(), actual.getLegs().size());
    for (int i = 0; i < expected.getLegs().size(); ++i)
    {
      final TransitLeg e = expected.getLegs().get(i);
      final TransitLeg a = actual.getLegs().get(i);
      assertEquals(e.getMode(), a.getMode());
      assertPlaceEquals(e.getFrom(), a.getFrom());
      assertPlaceEquals(e.getTo(), a.getTo());
      assertEquals(e.getStartTime(), a.getStartTime());
      assertEquals(e.getEndTime(), a.getEndTime());
      assertEquals(e.getScheduledStartTime(), a.getScheduledStartTime());
      assertEquals(e.getScheduledEndTime(), a.getScheduledEndTime());
      assertEquals(e.isRealTime(), a.isRealTime());
      assertEquals(e.getDurationSeconds(), a.getDurationSeconds());
      assertEquals(e.getDistanceMeters(), a.getDistanceMeters());
      assertEquals(e.getRouteShortName(), a.getRouteShortName());
      assertEquals(e.getDisplayName(), a.getDisplayName());
      assertEquals(e.getLineName(), a.getLineName());
      assertEquals(e.getHeadsign(), a.getHeadsign());
      assertEquals(e.getDirection(), a.getDirection());
      assertEquals(e.getAgencyName(), a.getAgencyName());
      assertEquals(e.getRouteColor(), a.getRouteColor());
      assertEquals(e.getRouteTextColor(), a.getRouteTextColor());
      assertEquals(e.getRouteId(), a.getRouteId());
      assertEquals(e.getTripId(), a.getTripId());
      assertEquals(e.isCancelled(), a.isCancelled());
      assertEquals(e.isInterlineWithPreviousLeg(), a.isInterlineWithPreviousLeg());
      assertEquals(e.getBikesAllowed(), a.getBikesAllowed());
      assertEquals(e.getAlerts(), a.getAlerts());
      assertEquals(e.getEncodedPolyline(), a.getEncodedPolyline());
      assertEquals(e.getPolylinePrecision(), a.getPolylinePrecision());
      assertArrayEquals(e.getPolyline(), a.getPolyline(), 1e-9);
      assertEquals(e.getIntermediateStops().size(), a.getIntermediateStops().size());
      for (int s = 0; s < e.getIntermediateStops().size(); ++s)
        assertPlaceEquals(e.getIntermediateStops().get(s), a.getIntermediateStops().get(s));
    }
  }

  private static void assertPlaceEquals(@NonNull TransitPlace expected, @NonNull TransitPlace actual)
  {
    assertEquals(expected.getName(), actual.getName());
    assertEquals(expected.getStopId(), actual.getStopId());
    assertEquals(expected.getLat(), actual.getLat(), 0);
    assertEquals(expected.getLon(), actual.getLon(), 0);
    assertEquals(expected.getTimeZone(), actual.getTimeZone());
    assertEquals(expected.getArrival(), actual.getArrival());
    assertEquals(expected.getScheduledArrival(), actual.getScheduledArrival());
    assertEquals(expected.getDeparture(), actual.getDeparture());
    assertEquals(expected.getScheduledDeparture(), actual.getScheduledDeparture());
    assertEquals(expected.getTrack(), actual.getTrack());
    assertEquals(expected.getScheduledTrack(), actual.getScheduledTrack());
    assertEquals(expected.isCancelled(), actual.isCancelled());
  }
}
