package app.organicmaps.sdk.transit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class TransitItinerariesTest
{
  private static final Instant T0 = Instant.parse("2026-11-25T08:00:00Z");

  private static TransitPlace place(String name, String stopId)
  {
    final TransitPlace place = new TransitPlace();
    place.mName = name;
    place.mStopId = stopId;
    place.mLat = 50.7;
    place.mLon = 7.1;
    place.mTimeZone = "Europe/Berlin";
    return place;
  }

  private static TransitLeg leg(TransitMode mode, int startMinute, int endMinute)
  {
    final TransitLeg leg = new TransitLeg();
    leg.mMode = mode;
    leg.mStartTime = T0.plusSeconds(startMinute * 60L);
    leg.mEndTime = T0.plusSeconds(endMinute * 60L);
    leg.mScheduledStartTime = leg.mStartTime;
    leg.mScheduledEndTime = leg.mEndTime;
    leg.mDurationSeconds = (endMinute - startMinute) * 60L;
    leg.mFrom = place("A", "a");
    leg.mTo = place("B", "b");
    return leg;
  }

  @Test
  public void composesTimesDurationAndTransfers()
  {
    final TransitLeg walk = leg(TransitMode.WALK, 0, 5);
    final TransitLeg tram = leg(TransitMode.TRAM, 5, 20);
    final TransitLeg transfer = leg(TransitMode.WALK, 20, 25);
    final TransitLeg train = leg(TransitMode.REGIONAL_RAIL, 30, 60);
    final TransitLeg interlined = leg(TransitMode.REGIONAL_RAIL, 60, 70);
    interlined.mInterlineWithPreviousLeg = true;

    final TransitItinerary itinerary =
        TransitItineraries.of("x", Arrays.asList(walk, tram, transfer, train, interlined));

    assertEquals("x", itinerary.getId());
    assertEquals(T0, itinerary.getStartTime());
    assertEquals(T0.plusSeconds(70 * 60), itinerary.getEndTime());
    assertEquals(70 * 60, itinerary.getDurationSeconds());
    // Staying in the interlined train is no change.
    assertEquals(1, itinerary.getTransfers());
    assertEquals(5, itinerary.getLegs().size());
    assertSame(tram, itinerary.getLegs().get(1));
  }

  @Test
  public void walkOnlyHasNoTransfers()
  {
    final TransitItinerary itinerary =
        TransitItineraries.of("w", Collections.singletonList(leg(TransitMode.WALK, 0, 12)));
    assertEquals(0, itinerary.getTransfers());
    assertEquals(12 * 60, itinerary.getDurationSeconds());
  }

  @Test
  public void legsAreCopied()
  {
    final java.util.List<TransitLeg> legs = new java.util.ArrayList<>();
    legs.add(leg(TransitMode.BUS, 0, 10));
    final TransitItinerary itinerary = TransitItineraries.of("c", legs);
    legs.add(leg(TransitMode.WALK, 10, 12));
    assertEquals(1, itinerary.getLegs().size());
  }

  @Test
  public void emptyIsRejected()
  {
    assertThrows(IllegalArgumentException.class, () -> TransitItineraries.of("e", Collections.emptyList()));
  }

  @Test
  public void startingAtMovesAllTimes()
  {
    final TransitLeg walk = leg(TransitMode.WALK, 20, 27);
    walk.mDistanceMeters = 460.0;
    walk.mFrom.mArrival = T0.plusSeconds(20 * 60);
    walk.mFrom.mDeparture = T0.plusSeconds(20 * 60);
    walk.mTo.mArrival = T0.plusSeconds(27 * 60);
    walk.mTo.mScheduledArrival = T0.plusSeconds(27 * 60);
    walk.mEncodedPolyline = "abc";

    final TransitLeg moved = TransitItineraries.startingAt(walk, T0.plusSeconds(31 * 60));

    assertEquals(T0.plusSeconds(31 * 60), moved.getStartTime());
    assertEquals(T0.plusSeconds(38 * 60), moved.getEndTime());
    assertEquals(T0.plusSeconds(31 * 60), moved.getScheduledStartTime());
    assertEquals(T0.plusSeconds(38 * 60), moved.getScheduledEndTime());
    assertEquals(7 * 60, moved.getDurationSeconds());
    assertEquals(460.0, moved.getDistanceMeters(), 0);
    assertEquals(T0.plusSeconds(31 * 60), moved.getFrom().getDeparture());
    assertEquals(T0.plusSeconds(38 * 60), moved.getTo().getArrival());
    assertEquals(T0.plusSeconds(38 * 60), moved.getTo().getScheduledArrival());
    assertNull(moved.getTo().getDeparture());
    assertEquals("b", moved.getTo().getStopId());
    assertEquals("abc", moved.getEncodedPolyline());
    assertTrue(moved.isWalk());
    // The original is unchanged.
    assertEquals(T0.plusSeconds(20 * 60), walk.getStartTime());
    assertEquals(T0.plusSeconds(27 * 60), walk.getTo().getArrival());
  }

  @Test
  public void startingAtKeepsLineData()
  {
    final TransitLeg bus = leg(TransitMode.BUS, 0, 10);
    bus.mDisplayName = "63";
    bus.mTripId = "trip";
    bus.mRealTime = true;
    bus.mIntermediateStops = Collections.singletonList(place("Mid", "m"));
    bus.mIntermediateStops.get(0).mDeparture = T0.plusSeconds(300);
    final TransitLeg moved = TransitItineraries.startingAt(bus, T0.plusSeconds(-120));
    assertEquals("63", moved.getLineName());
    assertEquals("trip", moved.getTripId());
    assertTrue(moved.isRealTime());
    assertFalse(moved.isCancelled());
    assertEquals(T0.plusSeconds(180), moved.getIntermediateStops().get(0).getDeparture());
  }
}
