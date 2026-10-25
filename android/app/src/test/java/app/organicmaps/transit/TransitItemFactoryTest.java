package app.organicmaps.transit;

import static app.organicmaps.sdk.transit.TransitTestData.itinerary;
import static app.organicmaps.sdk.transit.TransitTestData.leg;
import static app.organicmaps.sdk.transit.TransitTestData.place;
import static app.organicmaps.sdk.transit.TransitTestData.t;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.organicmaps.sdk.transit.TransitDeparture;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitMode;
import app.organicmaps.sdk.transit.TransitTestData;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class TransitItemFactoryTest
{
  private final TransitItemFactory mFactory = new TransitItemFactory(Fixtures.formatter());

  @Test
  public void itineraryItemWithDelay()
  {
    final ItineraryItem item = mFactory.itinerary(Fixtures.s5("id-1", 29));
    assertEquals("id-1", item.getKey());
    // Start of the walk to the station, end of the walk at the destination.
    assertEquals("17:29", item.getDeparture());
    assertEquals("17:46", item.getArrival());
    assertEquals("+2", item.getDepartureDelay().getText());
    assertEquals(TransitFormatter.Delay.Status.LATE, item.getArrivalDelay().getStatus());
    assertEquals("17 min", item.getDuration());
    assertEquals("direct", item.getTransfers());
    assertEquals(1, item.getBadges().size());
    assertEquals("S5", item.getBadges().get(0).getText());
    assertEquals(0xFFEB7405, item.getBadges().get(0).getBackgroundColor());
    assertEquals("S+U Berlin Hauptbahnhof · Pl. 15", item.getBoarding());
    assertTrue(item.isRealTime());
    assertFalse(item.isCancelled());
    assertFalse(item.isWalkOnly());
    assertEquals(0, item.getArrivalDayOffset());
  }

  @Test
  public void itineraryWithTransferAndWithoutRealtime()
  {
    final ItineraryItem item = mFactory.itinerary(Fixtures.busAndSubway("b"));
    assertEquals("1 transfer", item.getTransfers());
    assertEquals(2, item.getBadges().size());
    assertEquals("100", item.getBadges().get(0).getText());
    assertEquals("U5", item.getBadges().get(1).getText());
    // The bus has no realtime data, the subway is on time.
    assertEquals(TransitFormatter.Delay.Status.UNKNOWN, item.getDepartureDelay().getStatus());
    assertEquals(TransitFormatter.Delay.Status.ON_TIME, item.getArrivalDelay().getStatus());
    assertEquals("Hauptbahnhof", item.getBoarding());
  }

  @Test
  public void cancelledAndWalkOnly()
  {
    final TransitItinerary cancelled = itinerary(
        "c", leg(TransitMode.TRAM).times(t("2026-10-25T16:00:00Z"), t("2026-10-25T16:10:00Z")).cancelled().build());
    assertTrue(mFactory.itinerary(cancelled).isCancelled());

    final TransitItinerary walk = itinerary("", leg(TransitMode.WALK)
                                                    .from(place("START").build())
                                                    .to(place("END").build())
                                                    .times(t("2026-10-25T16:00:00Z"), t("2026-10-25T16:40:00Z"))
                                                    .build());
    final ItineraryItem item = mFactory.itinerary(walk);
    assertTrue(item.isWalkOnly());
    assertTrue(item.getBadges().isEmpty());
    assertNull(item.getBoarding());
    assertEquals(TransitFormatter.Delay.UNKNOWN, item.getDepartureDelay());
    // Without a server id, a key is derived from times and legs.
    assertFalse(item.getKey().isEmpty());
  }

  @Test
  public void arrivalAfterMidnight()
  {
    final TransitItinerary night = itinerary("n", leg(TransitMode.BUS)
                                                      .from(place("A").build())
                                                      .to(place("B").build())
                                                      .times(t("2026-10-25T22:50:00Z"), t("2026-10-25T23:20:00Z"))
                                                      .build());
    final ItineraryItem item = mFactory.itinerary(night);
    assertEquals("23:50", item.getDeparture());
    assertEquals("00:20", item.getArrival());
    assertEquals(1, item.getArrivalDayOffset());
  }

  @Test
  public void legsWithWalksTracksAndStops()
  {
    final List<LegItem> legs = mFactory.legs(Fixtures.s5("x", 29), Collections.emptySet());
    assertEquals(3, legs.size());

    final LegItem walk = legs.get(0);
    assertEquals(LegItem.Type.WALK, walk.getType());
    assertEquals("Start", walk.getFromName());
    assertEquals("S+U Berlin Hauptbahnhof", walk.getToName());
    assertEquals("Walk 2 min (140 m)", walk.getSummary());
    assertNull(walk.getBadge());
    assertFalse(walk.hasStops());

    final LegItem ride = legs.get(1);
    assertEquals(LegItem.Type.TRANSIT, ride.getType());
    assertEquals(1, ride.getIndex());
    assertEquals("S5", ride.getBadge().getText());
    assertEquals("S Mahlsdorf", ride.getDirection());
    assertEquals("17:33", ride.getDeparture());
    assertEquals("+2", ride.getDepartureDelay().getText());
    assertEquals("Pl. 15", ride.getFromTrack());
    assertFalse(ride.isFromTrackChanged());
    assertNull(ride.getToTrack());
    assertEquals("10 min · 3 stops", ride.getSummary());
    assertEquals(2, ride.getStops().size());
    assertEquals("S Friedrichstraße", ride.getStops().get(0).getName());
    assertEquals("17:36", ride.getStops().get(0).getTime());
    assertEquals("+2", ride.getStops().get(0).getDelay().getText());
    assertFalse(ride.isExpanded());

    assertEquals("Destination", legs.get(2).getToName());
    assertEquals(LegItem.Type.WALK, legs.get(2).getType());
  }

  @Test
  public void expandedLegsAndShortTransfers()
  {
    final List<LegItem> legs = mFactory.legs(Fixtures.busAndSubway("b"), Set.of(0, 2));
    // The 30 s walk between bus and subway is left out.
    assertEquals(2, legs.size());
    assertEquals(0, legs.get(0).getIndex());
    assertEquals(2, legs.get(1).getIndex());
    // Expanding a leg without intermediate stops has no effect.
    assertFalse(legs.get(0).isExpanded());
    assertEquals("Pl. 2", legs.get(1).getFromTrack());
    assertTrue(legs.get(1).isFromTrackChanged());

    final List<LegItem> s5 = mFactory.legs(Fixtures.s5("x", 0), Set.of(1));
    assertTrue(s5.get(1).isExpanded());
  }

  @Test
  public void otherStreetModes()
  {
    final TransitItinerary bike = itinerary("k", leg(TransitMode.BIKE)
                                                     .from(place("START").build())
                                                     .to(place("END").build())
                                                     .times(t("2026-10-25T16:00:00Z"), t("2026-10-25T16:10:00Z"))
                                                     .build());
    assertEquals(LegItem.Type.OTHER, mFactory.legs(bike, Collections.emptySet()).get(0).getType());
  }

  @Test
  public void departureItem()
  {
    final TransitDeparture departure = TransitTestData.departure(
        TransitMode.TRAM, "M4", "S Hackescher Markt",
        place("Alexanderplatz").departure(t("2026-10-25T16:05:00Z"), t("2026-10-25T16:01:00Z")).track("3", "2").build(),
        true, false, "be1414");
    final DepartureItem item = mFactory.departure(departure);
    assertEquals("M4", item.getBadge().getText());
    assertEquals("S Hackescher Markt", item.getDirection());
    assertEquals("17:05", item.getTime());
    assertEquals("+4", item.getDelay().getText());
    assertEquals("Pl. 3", item.getTrack());
    assertTrue(item.isTrackChanged());
    assertFalse(item.isCancelled());

    final DepartureItem noRealtime = mFactory.departure(TransitTestData.departure(
        TransitMode.BUS, "200", "Michelangelostr.",
        place("Alexanderplatz").departure(t("2026-10-25T16:05:00Z"), t("2026-10-25T16:05:00Z")).build(), false, true,
        null));
    assertEquals(TransitFormatter.Delay.UNKNOWN, noRealtime.getDelay());
    assertNull(noRealtime.getTrack());
    assertTrue(noRealtime.isCancelled());
  }
}
