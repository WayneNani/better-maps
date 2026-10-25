package app.organicmaps.sdk.transit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.organicmaps.sdk.motis.invoker.JSON;
import app.organicmaps.sdk.motis.model.Match;
import app.organicmaps.sdk.motis.model.Plan200Response;
import app.organicmaps.sdk.motis.model.Stoptimes200Response;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import org.junit.Test;

/** Mapping of recorded Transitous responses to the public model classes. */
public class MotisMapperTest
{
  @Test
  public void planBerlinHbfToAlexanderplatz() throws IOException
  {
    final TransitPlan plan = MotisMapper.toPlan(parsePlan(Fixtures.read("plan_hbf_alex.json")));

    assertFalse(plan.isEmpty());
    assertEquals(7, plan.getItineraries().size());
    assertTrue(plan.getDirect().isEmpty());
    assertEquals("EARLIER|1792945740", plan.getPreviousPageCursor());
    assertEquals("LATER|1792946700", plan.getNextPageCursor());
    assertEquals("START", plan.getFrom().getName());
    assertFalse(plan.getFrom().isStop());
    assertEquals(52.525, plan.getFrom().getLat(), 1e-9);
    assertEquals("END", plan.getTo().getName());
    assertEquals(13.4132, plan.getTo().getLon(), 1e-9);

    final TransitItinerary itinerary = plan.getItineraries().get(0);
    assertEquals(Instant.parse("2026-10-25T16:29:00Z"), itinerary.getStartTime());
    assertEquals(Instant.parse("2026-10-25T16:41:00Z"), itinerary.getEndTime());
    assertEquals(720, itinerary.getDurationSeconds());
    assertEquals(0, itinerary.getTransfers());
    assertFalse(itinerary.getId().isEmpty());
    assertEquals(3, itinerary.getLegs().size());
    assertTrue(itinerary.hasRealTime());
    assertFalse(itinerary.isCancelled());
    assertEquals(0, itinerary.getDepartureDelaySeconds());
    assertEquals(108.0 + 242.0, itinerary.getWalkingDistanceMeters(), 1e-9);
    assertEquals(120 + 240, itinerary.getWalkingDurationSeconds());

    // Walk to the platform.
    final TransitLeg walk = itinerary.getLegs().get(0);
    assertEquals(TransitMode.WALK, walk.getMode());
    assertTrue(walk.isWalk());
    assertFalse(walk.isTransit());
    assertEquals(108.0, walk.getDistanceMeters(), 1e-9);
    assertEquals("de-VBB_de:11000:900003201:3:54", walk.getTo().getStopId());
    assertNull(walk.getLineName());
    assertNull(walk.getDirection());

    // S5 to Alexanderplatz with realtime data.
    final TransitLeg ride = itinerary.getLegs().get(1);
    assertEquals(TransitMode.SUBURBAN, ride.getMode());
    assertTrue(ride.isTransit());
    assertEquals("S5", ride.getRouteShortName());
    assertEquals("S5", ride.getLineName());
    assertEquals("S Mahlsdorf Bhf (Berlin)", ride.getHeadsign());
    assertEquals("S Mahlsdorf Bhf (Berlin)", ride.getDirection());
    assertEquals("S-Bahn Berlin GmbH", ride.getAgencyName());
    assertEquals("eb7405", ride.getRouteColor());
    assertTrue(ride.isRealTime());
    assertEquals(Instant.parse("2026-10-25T16:31:00Z"), ride.getStartTime());
    assertEquals(Instant.parse("2026-10-25T16:31:00Z"), ride.getScheduledStartTime());
    assertEquals(0, ride.getDepartureDelaySeconds());
    assertEquals(360, ride.getDurationSeconds());
    assertEquals("de-VBB_de:11000:900003201:3:54", ride.getFrom().getStopId());
    assertEquals("15", ride.getFrom().getTrack());
    assertEquals("de-VBB_de:11000:900100003:2:52", ride.getTo().getStopId());
    assertEquals(2, ride.getIntermediateStops().size());
    assertNotNull(ride.getTripId());
    assertFalse(ride.isCancelled());

    // Polyline: precision 6, starts at the departure stop and ends at the arrival stop.
    assertEquals(6, ride.getPolylinePrecision());
    final double[] line = ride.getPolyline();
    assertEquals(2 * 112, line.length);
    assertEquals(ride.getFrom().getLat(), line[0], 1e-3);
    assertEquals(ride.getFrom().getLon(), line[1], 1e-3);
    assertEquals(ride.getTo().getLat(), line[line.length - 2], 1e-3);
    assertEquals(ride.getTo().getLon(), line[line.length - 1], 1e-3);

    // Detailed transfers: the walking legs have a geometry, too.
    assertEquals(2 * 21, walk.getPolyline().length);
    assertEquals(plan.getFrom().getLat(), walk.getPolyline()[0], 1e-3);
  }

  @Test
  public void nextPageHasDifferentCursorsAndLaterItineraries() throws IOException
  {
    final TransitPlan first = MotisMapper.toPlan(parsePlan(Fixtures.read("plan_hbf_alex.json")));
    final TransitPlan next = MotisMapper.toPlan(parsePlan(Fixtures.read("plan_hbf_alex_next.json")));
    assertEquals("EARLIER|1792946700", next.getPreviousPageCursor());
    assertEquals("LATER|1792953900", next.getNextPageCursor());
    assertEquals(2, next.getItineraries().size());
    final Instant lastOfFirst = first.getItineraries().get(first.getItineraries().size() - 1).getStartTime();
    assertTrue(next.getItineraries().get(0).getStartTime().isAfter(lastOfFirst));
  }

  @Test
  public void regionalTripWithTransfersAndEarlyArrival() throws IOException
  {
    final TransitPlan plan = MotisMapper.toPlan(parsePlan(Fixtures.read("plan_regional.json")));
    final TransitItinerary itinerary = plan.getItineraries().get(0);
    assertEquals(2, itinerary.getTransfers());
    assertEquals(7, itinerary.getLegs().size());

    final List<TransitLeg> rides = itinerary.getTransitLegs();
    assertEquals(3, rides.size());
    assertEquals("S7", rides.get(0).getLineName());
    assertEquals(TransitMode.REGIONAL_RAIL, rides.get(1).getMode());
    assertEquals("RE2", rides.get(1).getDisplayName());
    assertEquals(TransitMode.BUS, rides.get(2).getMode());
    assertEquals("10", rides.get(2).getLineName());
    assertEquals("Cottbus, Schloss Branitz", rides.get(2).getDirection());
    assertEquals(rides.get(0), itinerary.getFirstTransitLeg());
    assertEquals(rides.get(2), itinerary.getLastTransitLeg());

    // RE2 arrives one minute early: realtime 18:54, scheduled 18:55.
    final TransitLeg re2 = rides.get(1);
    assertTrue(re2.isRealTime());
    assertEquals(Instant.parse("2026-10-25T18:54:00Z"), re2.getEndTime());
    assertEquals(Instant.parse("2026-10-25T18:55:00Z"), re2.getScheduledEndTime());
    assertEquals(-60, re2.getArrivalDelaySeconds());
    assertEquals(Long.valueOf(-60), re2.getTo().getArrivalDelaySeconds());
    assertEquals("Cottbus, Hauptbahnhof", re2.getTo().getName());

    // Transfer walk at Ostkreuz between tracks.
    final TransitLeg transfer = itinerary.getLegs().get(2);
    assertTrue(transfer.isWalk());
    assertEquals("S Ostkreuz Bhf (Berlin)", transfer.getFrom().getName());
    assertEquals("S Ostkreuz Bhf (Berlin)", transfer.getTo().getName());
    assertEquals(116.0, transfer.getDistanceMeters(), 1e-9);
    assertEquals(108.0 + 116.0 + 326.0 + 179.0, itinerary.getWalkingDistanceMeters(), 1e-9);
  }

  @Test
  public void stopIdQueryArriveBy() throws IOException
  {
    final TransitPlan plan = MotisMapper.toPlan(parsePlan(Fixtures.read("plan_stopid_arriveby.json")));
    assertEquals("S+U Alexanderplatz Bhf (Berlin)", plan.getFrom().getName());
    assertEquals("de-VBB_de:11000:900100003", plan.getFrom().getStopId());
    assertTrue(plan.getFrom().isStop());
    assertEquals("Europe/Berlin", plan.getFrom().getTimeZone());
    assertEquals(3, plan.getItineraries().size());
    for (TransitItinerary itinerary : plan.getItineraries())
      assertTrue(itinerary.getEndTime().isBefore(Instant.parse("2026-10-25T18:00:01Z")));
    assertEquals("S Potsdam Hauptbahnhof", plan.getItineraries().get(0).getLegs().get(0).getDirection());
  }

  @Test
  public void emptyPlan() throws IOException
  {
    final TransitPlan plan = MotisMapper.toPlan(parsePlan(Fixtures.read("plan_empty.json")));
    assertTrue(plan.isEmpty());
    assertTrue(plan.getItineraries().isEmpty());
    assertTrue(plan.getDirect().isEmpty());
    // Paging is still possible.
    assertEquals("LATER|1792945800", plan.getNextPageCursor());
  }

  @Test
  public void delayAndCancellation() throws IOException
  {
    // No delayed or cancelled leg in the recordings: derive one from a real response.
    final JsonObject json = JsonParser.parseString(Fixtures.read("plan_hbf_alex.json")).getAsJsonObject();
    final JsonObject leg =
        json.getAsJsonArray("itineraries").get(0).getAsJsonObject().getAsJsonArray("legs").get(1).getAsJsonObject();
    leg.addProperty("startTime", "2026-10-25T16:34:00Z");
    leg.addProperty("endTime", "2026-10-25T16:40:30Z");
    leg.addProperty("cancelled", true);
    leg.getAsJsonObject("from").addProperty("departure", "2026-10-25T16:34:00Z");
    leg.getAsJsonObject("from").addProperty("track", "16");
    leg.remove("headsign");

    final TransitPlan plan = MotisMapper.toPlan(parsePlan(json.toString()));
    final TransitItinerary itinerary = plan.getItineraries().get(0);
    final TransitLeg ride = itinerary.getLegs().get(1);
    assertEquals(180, ride.getDepartureDelaySeconds());
    assertEquals(210, ride.getArrivalDelaySeconds());
    assertEquals(180, itinerary.getDepartureDelaySeconds());
    assertEquals(210, itinerary.getArrivalDelaySeconds());
    assertTrue(ride.isCancelled());
    assertTrue(itinerary.isCancelled());
    assertEquals(Long.valueOf(180), ride.getFrom().getDepartureDelaySeconds());
    assertEquals("16", ride.getFrom().getTrack());
    assertEquals("15", ride.getFrom().getScheduledTrack());
    assertTrue(ride.getFrom().isTrackChanged());
    // Without headsign the final stop of the trip is used as direction.
    assertNull(ride.getHeadsign());
    assertEquals("S Mahlsdorf Bhf (Berlin)", ride.getDirection());
  }

  @Test
  public void unknownModeFromNewerServerDoesNotFail() throws IOException
  {
    final JsonObject json = JsonParser.parseString(Fixtures.read("plan_hbf_alex.json")).getAsJsonObject();
    json.getAsJsonArray("itineraries")
        .get(0)
        .getAsJsonObject()
        .getAsJsonArray("legs")
        .get(1)
        .getAsJsonObject()
        .addProperty("mode", "HOVERCRAFT");
    final TransitPlan plan = MotisMapper.toPlan(parsePlan(json.toString()));
    assertEquals(TransitMode.UNKNOWN, plan.getItineraries().get(0).getLegs().get(1).getMode());
  }

  @Test
  public void itineraryWithoutTransitLegs() throws IOException
  {
    final JsonObject json = JsonParser.parseString(Fixtures.read("plan_hbf_alex.json")).getAsJsonObject();
    final JsonArray legs = json.getAsJsonArray("itineraries").get(0).getAsJsonObject().getAsJsonArray("legs");
    legs.remove(2);
    legs.remove(1);
    final TransitItinerary itinerary = MotisMapper.toPlan(parsePlan(json.toString())).getItineraries().get(0);
    assertNull(itinerary.getFirstTransitLeg());
    assertNull(itinerary.getLastTransitLeg());
    assertEquals(0, itinerary.getDepartureDelaySeconds());
    assertFalse(itinerary.hasRealTime());
  }

  @Test
  public void departuresAtAlexanderplatz() throws IOException
  {
    final TransitDepartures departures =
        MotisMapper.toDepartures(Stoptimes200Response.fromJson(Fixtures.read("stoptimes_alex.json")));
    assertEquals("S+U Alexanderplatz Bhf (Berlin)", departures.getStop().getName());
    assertEquals("de-VBB_de:11000:900100003", departures.getStop().getStopId());
    assertEquals(21, departures.getDepartures().size());
    assertEquals("EARLIER|1792945740", departures.getPreviousPageCursor());
    assertEquals("LATER|1792946460", departures.getNextPageCursor());

    final TransitDeparture u2 = departures.getDepartures().get(0);
    assertEquals(TransitMode.SUBWAY, u2.getMode());
    assertEquals("U2", u2.getLineName());
    assertEquals("U Ruhleben (Berlin)", u2.getDirection());
    assertEquals("1 (U2)", u2.getPlace().getTrack());
    assertFalse(u2.isRealTime());
    assertEquals(0, u2.getDelaySeconds());

    // S7 to Potsdam is one minute late.
    final TransitDeparture s7 = departures.getDepartures().get(1);
    assertEquals(TransitMode.SUBURBAN, s7.getMode());
    assertEquals("S7", s7.getLineName());
    assertEquals("S Potsdam Hauptbahnhof", s7.getHeadsign());
    assertEquals("S-Bahn Berlin GmbH", s7.getAgencyName());
    assertEquals("816da6", s7.getRouteColor());
    assertTrue(s7.isRealTime());
    assertEquals(Instant.parse("2026-10-25T16:31:00Z"), s7.getDeparture());
    assertEquals(Instant.parse("2026-10-25T16:30:00Z"), s7.getScheduledDeparture());
    assertEquals(60, s7.getDelaySeconds());
    assertFalse(s7.isCancelled());
  }

  @Test
  public void cancelledTripDeparture() throws IOException
  {
    final JsonObject json = JsonParser.parseString(Fixtures.read("stoptimes_alex.json")).getAsJsonObject();
    json.getAsJsonArray("stopTimes").get(0).getAsJsonObject().addProperty("tripCancelled", true);
    final TransitDepartures departures = MotisMapper.toDepartures(Stoptimes200Response.fromJson(json.toString()));
    assertTrue(departures.getDepartures().get(0).isCancelled());
    assertFalse(departures.getDepartures().get(1).isCancelled());
  }

  @Test
  public void stopsFromReverseGeocoding()
  {
    final List<Match> matches =
        JSON.getGson().fromJson(Fixtures.read("reverse_geocode_alex.json"), new TypeToken<List<Match>>() {}.getType());
    final List<TransitStop> stops = MotisMapper.toStops(matches);
    assertEquals(5, stops.size());
    final TransitStop tram = stops.get(0);
    assertEquals("de-VBB_de:11000:900100024::1", tram.getId());
    assertEquals("S+U Alexanderplatz Bhf/Dircksenstr. (Berlin)", tram.getName());
    assertEquals(52.521454, tram.getLat(), 1e-9);
    assertEquals(EnumSet.of(TransitMode.TRAM), tram.getModes());
    assertEquals(EnumSet.of(TransitMode.SUBURBAN, TransitMode.SUBWAY), stops.get(2).getModes());
    assertTrue(MotisMapper.toStops(null).isEmpty());
  }

  private static Plan200Response parsePlan(String json) throws IOException
  {
    return Plan200Response.fromJson(json);
  }
}
