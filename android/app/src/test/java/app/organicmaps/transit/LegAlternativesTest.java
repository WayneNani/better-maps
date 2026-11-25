package app.organicmaps.transit;

import static app.organicmaps.sdk.transit.TransitTestData.itinerary;
import static app.organicmaps.transit.LegFixtures.at;
import static app.organicmaps.transit.LegFixtures.bonnTrain;
import static app.organicmaps.transit.LegFixtures.bonnTram;
import static app.organicmaps.transit.LegFixtures.connection;
import static app.organicmaps.transit.LegFixtures.end;
import static app.organicmaps.transit.LegFixtures.firstAlternative;
import static app.organicmaps.transit.LegFixtures.koeln;
import static app.organicmaps.transit.LegFixtures.olofPalme;
import static app.organicmaps.transit.LegFixtures.rest;
import static app.organicmaps.transit.LegFixtures.ride;
import static app.organicmaps.transit.LegFixtures.start;
import static app.organicmaps.transit.LegFixtures.stop;
import static app.organicmaps.transit.LegFixtures.walk;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLeg;
import app.organicmaps.sdk.transit.TransitLocation;
import app.organicmaps.sdk.transit.TransitMode;
import app.organicmaps.sdk.transit.TransitRoutingOptions;
import app.organicmaps.sdk.transit.TransitTestData;
import app.organicmaps.sdk.transit.provider.TransitPlanRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import org.junit.Test;

public class LegAlternativesTest
{
  private static final TransitLocation ORIGIN = TransitLocation.fromCoordinates(50.7068, 7.1406);
  private static final Instant EARLY = at("07:00");

  /** The departures from the start to the tram stop at Bonn Hbf in the example of the mockups. */
  private static List<TransitItinerary> firstAlternatives()
  {
    return Arrays.asList(firstAlternative("16", "08:59"), firstAlternative("16", "09:09"),
                         firstAlternative("63", "09:19"), firstAlternative("16", "09:22"),
                         firstAlternative("16", "09:26"),
                         // The ride of the connection itself.
                         firstAlternative("63", "09:29"), firstAlternative("63", "09:39"),
                         firstAlternative("16", "09:42"), firstAlternative("16", "09:52"));
  }

  @NonNull
  private static List<String> departures(@NonNull List<LegAlternatives.Option> options)
  {
    final List<String> result = new ArrayList<>();
    for (LegAlternatives.Option option : options)
      result.add(LegFixtures.local(option.getDeparture()) + " " + option.getRide().getLineName()
                 + (option.isCurrent() ? "*" : ""));
    return result;
  }

  @Test
  public void ridePositionsAndTransfer()
  {
    final TransitItinerary connection = connection();
    final LegAlternatives.Ride tram = LegAlternatives.ride(connection, 1);
    assertEquals(LegAlternatives.Position.FIRST, tram.getPosition());
    assertEquals(1, tram.getNumber());
    assertEquals(3, tram.getNextIndex());
    assertEquals("ICE 1022", tram.getNextLeg().getLineName());
    assertEquals(7 * 60, tram.getTransferSeconds());
    // The ICE leaves at 09:47 (3 min late); the walk takes 7 min.
    assertEquals(at("09:40"), tram.getLatestArrival());
    assertNull(tram.getEarliestDeparture());

    final LegAlternatives.Ride ice = LegAlternatives.ride(connection, 3);
    assertEquals(LegAlternatives.Position.LAST, ice.getPosition());
    assertEquals(2, ice.getNumber());
    assertNull(ice.getNextLeg());
    assertNull(ice.getLatestArrival());
    assertEquals(0, ice.getTransferSeconds());
    assertEquals(at("09:46"), ice.getEarliestDeparture());
  }

  @Test
  public void onlyAndMiddlePositions()
  {
    final TransitItinerary direct = itinerary("d", walk(start(), olofPalme(), "09:00", "09:10"),
                                              ride(TransitMode.TRAM, "16", "t", olofPalme(), koeln(), "09:10", "10:20"));
    assertEquals(LegAlternatives.Position.ONLY, LegAlternatives.ride(direct, 1).getPosition());
    assertFalse(LegAlternatives.Position.ONLY.hasNext());
    assertFalse(LegAlternatives.Position.ONLY.hasPrevious());
    assertEquals(LegAlternatives.Position.MIDDLE, LegAlternatives.ride(threeRides(), 3).getPosition());
    assertTrue(LegAlternatives.Position.MIDDLE.hasNext());
    assertTrue(LegAlternatives.Position.MIDDLE.hasPrevious());
  }

  @Test
  public void walksAreNoRides()
  {
    assertFalse(LegAlternatives.isRide(connection(), 0));
    assertTrue(LegAlternatives.isRide(connection(), 1));
    assertFalse(LegAlternatives.isRide(connection(), 9));
    assertThrows(IllegalArgumentException.class, () -> LegAlternatives.ride(connection(), 2));
    assertThrows(IllegalArgumentException.class, () -> LegAlternatives.ride(connection(), -1));
  }

  @Test
  public void firstRideIsSearchedFromTheStart()
  {
    final TransitRoutingOptions options =
        TransitRoutingOptions.builder().setTransitModes(EnumSet.of(TransitMode.TRAM, TransitMode.BUS)).build();
    final TransitPlanRequest request =
        LegAlternatives.request(LegAlternatives.ride(connection(), 1), ORIGIN, EARLY, options);
    assertEquals(ORIGIN, request.getFrom());
    assertEquals(TransitLocation.fromStopId("bhf-tram"), request.getTo());
    // 40 min before the start of the connection (09:16) to 30 min after it.
    assertEquals(at("08:36"), request.getTime());
    assertFalse(request.isArriveBy());
    assertNull(request.getPageCursor());
    assertEquals(Integer.valueOf(0), request.getOptions().getMaxTransfers());
    assertEquals(Integer.valueOf(70 * 60), request.getOptions().getSearchWindowSeconds());
    assertEquals(LegAlternatives.NUM_ITINERARIES, request.getOptions().getNumItineraries());
    // The user's settings stay.
    assertEquals(EnumSet.of(TransitMode.TRAM, TransitMode.BUS), request.getOptions().getTransitModes());
  }

  @Test
  public void firstRideWithoutOriginStartsAtTheConnectionsStart()
  {
    final TransitPlanRequest request = LegAlternatives.request(LegAlternatives.ride(connection(), 1), null, EARLY,
                                                               TransitRoutingOptions.defaults());
    assertEquals(TransitLocation.fromCoordinates(LegFixtures.START_LAT, LegFixtures.START_LON), request.getFrom());
  }

  @Test
  public void noDeparturesInThePast()
  {
    final TransitPlanRequest request = LegAlternatives.request(LegAlternatives.ride(connection(), 1), ORIGIN,
                                                               at("09:05"), TransitRoutingOptions.defaults());
    assertEquals(at("09:05"), request.getTime());
    assertEquals(Integer.valueOf(41 * 60), request.getOptions().getSearchWindowSeconds());

    // Even if the connection is about to leave, the window is not too short.
    final TransitPlanRequest late = LegAlternatives.request(LegAlternatives.ride(connection(), 1), ORIGIN,
                                                            at("09:40"), TransitRoutingOptions.defaults());
    assertEquals(at("09:40"), late.getTime());
    assertEquals(Integer.valueOf((int) LegAlternatives.MIN_WINDOW_SECONDS),
                 late.getOptions().getSearchWindowSeconds());
  }

  @Test
  public void laterRidesAreSearchedFromTheirStopAfterThePreviousRide()
  {
    final TransitPlanRequest request = LegAlternatives.request(LegAlternatives.ride(connection(), 3), ORIGIN, EARLY,
                                                               TransitRoutingOptions.defaults());
    assertEquals(TransitLocation.fromStopId("bhf"), request.getFrom());
    assertEquals(TransitLocation.fromStopId("koeln"), request.getTo());
    // The tram arrives at 09:39, the walk takes 7 min; the ICE leaves at 09:47.
    assertEquals(at("09:46"), request.getTime());
    // Until 30 min after the ride.
    assertEquals(Integer.valueOf(31 * 60), request.getOptions().getSearchWindowSeconds());
  }

  @Test
  public void noRequestWithoutPlaces()
  {
    final TransitItinerary nowhere =
        itinerary("n", ride(TransitMode.BUS, "1", "b", TransitTestData.place("A").at(Double.NaN, Double.NaN).build(),
                            TransitTestData.place("B").at(Double.NaN, Double.NaN).build(), "09:00", "09:10"));
    assertNull(LegAlternatives.request(LegAlternatives.ride(nowhere, 0), null, EARLY,
                                       TransitRoutingOptions.defaults()));
    assertNull(LegAlternatives.location(TransitTestData.place("X").at(91, 0).build()));
    assertEquals(TransitLocation.fromCoordinates(1, 2),
                 LegAlternatives.location(TransitTestData.place("X").stop(" ").at(1, 2).build()));
  }

  @Test
  public void firstRideShowsTheLatestConnectingAndTwoMissing()
  {
    final List<LegAlternatives.Option> options =
        LegAlternatives.options(LegAlternatives.ride(connection(), 1), firstAlternatives());
    // 08:59 would connect too, but only the 5 latest are shown; 09:52 is the third that misses.
    assertEquals(Arrays.asList("09:09 16", "09:19 63", "09:22 16", "09:26 16", "09:29 63*", "09:39 63", "09:42 16"),
                 departures(options));
    assertEquals(LegAlternatives.Status.COMFORTABLE, options.get(0).getStatus());
    assertEquals(Long.valueOf(21 * 60), options.get(0).getBufferSeconds());
    assertEquals(LegAlternatives.Status.TIGHT, options.get(3).getStatus());
    assertEquals(Long.valueOf(4 * 60), options.get(3).getBufferSeconds());
    // The found ride is tight: 1 min.
    assertEquals(LegAlternatives.Status.TIGHT, options.get(4).getStatus());
    assertEquals(Long.valueOf(60), options.get(4).getBufferSeconds());
    assertEquals(LegAlternatives.Status.MISSED, options.get(5).getStatus());
    assertTrue(options.get(5).missesNext());
    assertEquals(Long.valueOf(-9 * 60), options.get(5).getBufferSeconds());
    // The first ride includes the walk from the start.
    assertEquals(2, options.get(0).getLegs().size());
    assertEquals(at("08:56"), options.get(0).getStart());
    assertEquals(at("09:19"), options.get(0).getArrival());
  }

  @Test
  public void currentOptionUsesTheConnectionsOwnLegs()
  {
    final TransitItinerary connection = connection();
    final List<LegAlternatives.Option> options =
        LegAlternatives.options(LegAlternatives.ride(connection, 1), Collections.emptyList());
    assertEquals(1, options.size());
    final LegAlternatives.Option current = options.get(0);
    assertTrue(current.isCurrent());
    assertEquals(connection.getLegs().subList(0, 2), current.getLegs());
    assertSame(connection.getLegs().get(1), current.getRide());
    assertTrue(current.toString().contains("current"));
  }

  @Test
  public void otherItinerariesAreIgnored()
  {
    final TransitLeg cancelled = ride(TransitMode.TRAM, "16", "c", olofPalme(), bonnTram(), "09:20", "09:30");
    final TransitItinerary withCancelled = itinerary("c", TransitTestData.leg(TransitMode.TRAM)
                                                              .from(olofPalme())
                                                              .to(bonnTram())
                                                              .times(at("09:20"), at("09:30"))
                                                              .line("16", null, null)
                                                              .cancelled()
                                                              .build());
    final TransitItinerary twoRides =
        itinerary("2", ride(TransitMode.BUS, "600", "x", olofPalme(), bonnTram(), "09:10", "09:20"),
                  ride(TransitMode.BUS, "601", "y", bonnTram(), bonnTram(), "09:21", "09:25"));
    final TransitItinerary walkOnly = itinerary("w", walk(start(), bonnTram(), "09:00", "09:40"));
    final List<LegAlternatives.Option> options = LegAlternatives.options(
        LegAlternatives.ride(connection(), 1),
        Arrays.asList(withCancelled, twoRides, walkOnly, firstAlternative("16", "09:22"),
                      firstAlternative("16", "09:22")));
    assertEquals(Arrays.asList("09:22 16", "09:29 63*"), departures(options));
    assertNotEquals(LegAlternatives.key(cancelled), options.get(0).getKey());
  }

  @Test
  public void interlinedRideCountsAsOne()
  {
    final TransitLeg second = ride(TransitMode.TRAM, "16", "16b", bonnTram(), bonnTram(), "09:31", "09:33");
    TransitTestData.interline(second);
    final TransitItinerary interlined =
        itinerary("i", ride(TransitMode.TRAM, "16", "16a", olofPalme(), bonnTram(), "09:20", "09:30"), second);
    final List<LegAlternatives.Option> options =
        LegAlternatives.options(LegAlternatives.ride(connection(), 1), Collections.singletonList(interlined));
    assertEquals(Arrays.asList("09:20 16", "09:29 63*"), departures(options));
  }

  @Test
  public void lateCurrentRideIsShownAsMissed()
  {
    final TransitItinerary late = itinerary("late", walk(start(), olofPalme(), "09:16", "09:29"),
                                            TransitTestData.leg(TransitMode.TRAM)
                                                .from(olofPalme())
                                                .to(bonnTram())
                                                .times(at("09:29"), at("09:39"))
                                                .delay(300, 300)
                                                .line("63", null, null)
                                                .build(),
                                            walk(bonnTram(), bonnTrain(), "09:44", "09:51"),
                                            ride(TransitMode.HIGHSPEED_RAIL, "ICE 1022", "ice", bonnTrain(), koeln(),
                                                 "09:47", "10:06"));
    final List<LegAlternatives.Option> options = LegAlternatives.options(
        LegAlternatives.ride(late, 1),
        Arrays.asList(firstAlternative("16", "09:09"), firstAlternative("16", "09:40"),
                      firstAlternative("16", "09:41"), firstAlternative("16", "09:42")));
    // Two missing ones plus the late current ride.
    assertEquals(Arrays.asList("09:09 16", "09:34 63*", "09:40 16", "09:41 16"), departures(options));
    assertEquals(LegAlternatives.Status.MISSED, options.get(1).getStatus());
  }

  @Test
  public void currentRideStaysWhenManyLaterOnesConnect()
  {
    // A long transfer time: every departure up to 09:50 still connects.
    final TransitItinerary early = itinerary(
        "early", walk(start(), olofPalme(), "08:46", "08:59"),
        ride(TransitMode.TRAM, "63", "63-0859", olofPalme(), bonnTram(), "08:59", "09:09"),
        walk(bonnTram(), bonnTrain(), "09:09", "09:12"),
        ride(TransitMode.HIGHSPEED_RAIL, "ICE 1022", "ice", bonnTrain(), koeln(), "10:05", "10:30"));
    final List<LegAlternatives.Option> options = LegAlternatives.options(
        LegAlternatives.ride(early, 1),
        Arrays.asList(firstAlternative("16", "09:09"), firstAlternative("16", "09:19"),
                      firstAlternative("16", "09:29"), firstAlternative("16", "09:39"),
                      firstAlternative("16", "09:49")));
    assertEquals(Arrays.asList("08:59 63*", "09:09 16", "09:19 16", "09:29 16", "09:39 16"), departures(options));
  }

  @Test
  public void lastRideShowsEarlierAndLaterDepartures()
  {
    final List<TransitItinerary> found = new ArrayList<>();
    for (String time : new String[] {"09:46", "09:50", "09:52", "09:55", "09:58", "10:02", "10:10", "10:20", "10:25"})
    {
      final String arrival = LegFixtures.local(at(time).plusSeconds(20 * 60));
      found.add(
          itinerary("re-" + time, ride(TransitMode.REGIONAL_RAIL, "RE 5", "re-" + time, bonnTrain(), koeln(), time,
                                       arrival)));
    }
    final List<LegAlternatives.Option> options = LegAlternatives.options(LegAlternatives.ride(connection(), 3), found);
    // Sorted by departure: up to three before (only one is after the transfer), the ICE, three after.
    assertEquals(Arrays.asList("09:46 RE 5", "09:47 ICE 1022*", "09:50 RE 5", "09:52 RE 5", "09:55 RE 5"),
                 departures(options));
    for (LegAlternatives.Option option : options)
    {
      assertEquals(LegAlternatives.Status.ARRIVAL, option.getStatus());
      assertNull(option.getBufferSeconds());
      assertFalse(option.missesNext());
    }
    assertEquals(1, options.get(1).getLegs().size());
    assertEquals(at("10:06"), options.get(1).getArrival());
  }

  @Test
  public void middleRideDoesNotDepartBeforeThePreviousArrives()
  {
    final TransitItinerary connection = threeRides();
    final LegAlternatives.Ride ride = LegAlternatives.ride(connection, 3);
    final TransitItinerary tooEarly =
        itinerary("e", ride(TransitMode.BUS, "610", "e", bonnTram(), bonnTrain(), "09:40", "09:50"));
    final TransitItinerary fine =
        itinerary("f", ride(TransitMode.BUS, "610", "f", bonnTram(), bonnTrain(), "09:43", "09:53"));
    final List<LegAlternatives.Option> options = LegAlternatives.options(ride, Arrays.asList(tooEarly, fine));
    assertEquals(Arrays.asList("09:43 610", "09:45 608*"), departures(options));
    // The ICE leaves at 10:10, the transfer takes 2 min.
    assertEquals(Long.valueOf(15 * 60), options.get(0).getBufferSeconds());
    final TransitPlanRequest request =
        LegAlternatives.request(ride, ORIGIN, EARLY, TransitRoutingOptions.defaults());
    assertEquals(TransitLocation.fromStopId("bhf-tram"), request.getFrom());
    assertEquals(TransitLocation.fromStopId("bhf"), request.getTo());
    assertEquals(at("09:41"), request.getTime());
  }

  /** Tram 63 to Bonn Hbf, bus 608 to the train station, ICE to Köln. */
  @NonNull
  private static TransitItinerary threeRides()
  {
    return itinerary("three", walk(start(), olofPalme(), "09:16", "09:29"),
                     ride(TransitMode.TRAM, "63", "63", olofPalme(), stop("Bonn Hbf Bus", "bhf-bus"), "09:29",
                          "09:39"),
                     walk(stop("Bonn Hbf Bus", "bhf-bus"), bonnTram(), "09:39", "09:41"),
                     ride(TransitMode.BUS, "608", "608", bonnTram(), bonnTrain(), "09:45", "09:55"),
                     walk(bonnTrain(), bonnTrain(), "09:55", "09:57"),
                     ride(TransitMode.HIGHSPEED_RAIL, "ICE", "ice", bonnTrain(), koeln(), "10:10", "10:30"));
  }

  @Test
  public void statusThresholds()
  {
    final LegAlternatives.Ride ride = LegAlternatives.ride(connection(), 1);
    final TransitLeg leg = ride.getLeg();
    final List<TransitLeg> legs = Collections.singletonList(leg);
    assertEquals(LegAlternatives.Status.TIGHT, new LegAlternatives.Option(legs, leg, at("09:40"), 0L, false).getStatus());
    assertEquals(LegAlternatives.Status.TIGHT,
                 new LegAlternatives.Option(legs, leg, at("09:40"), LegAlternatives.TIGHT_SECONDS, false).getStatus());
    assertEquals(
        LegAlternatives.Status.COMFORTABLE,
        new LegAlternatives.Option(legs, leg, at("09:40"), LegAlternatives.TIGHT_SECONDS + 1, false).getStatus());
    assertEquals(LegAlternatives.Status.MISSED,
                 new LegAlternatives.Option(legs, leg, at("09:40"), -1L, false).getStatus());
    assertEquals(LegAlternatives.Status.ARRIVAL,
                 new LegAlternatives.Option(legs, leg, at("09:40"), null, false).getStatus());
  }

  @Test
  public void spliceFirstRideKeepsTheConnection()
  {
    final TransitItinerary connection = connection();
    final LegAlternatives.Ride ride = LegAlternatives.ride(connection, 1);
    final LegAlternatives.Option option = option(ride, firstAlternatives(), "09:22");

    final TransitItinerary changed = LegAlternatives.splice(ride, option);

    assertEquals(5, changed.getLegs().size());
    assertNotEquals(connection.getId(), changed.getId());
    assertNotEquals(TransitItemFactory.key(connection), TransitItemFactory.key(changed));
    // The walk to the stop starts earlier.
    assertEquals(at("09:09"), changed.getStartTime());
    assertEquals("16", changed.getLegs().get(1).getLineName());
    // The transfer walk starts when the tram arrives.
    assertEquals(at("09:32"), changed.getLegs().get(2).getStartTime());
    assertEquals(at("09:39"), changed.getLegs().get(2).getEndTime());
    // The ICE and the walk to the destination stay.
    assertSame(connection.getLegs().get(3), changed.getLegs().get(3));
    assertSame(connection.getLegs().get(4), changed.getLegs().get(4));
    assertEquals(connection.getEndTime(), changed.getEndTime());
    assertEquals(1, changed.getTransfers());
    assertEquals(59 * 60, changed.getDurationSeconds());
    assertEquals(1, LegAlternatives.splicedLegIndex(ride, option));
  }

  @Test
  public void spliceCurrentIsTheConnection()
  {
    final TransitItinerary connection = connection();
    final LegAlternatives.Ride ride = LegAlternatives.ride(connection, 1);
    final LegAlternatives.Option current = LegAlternatives.currentOption(ride);
    assertSame(connection, LegAlternatives.splice(ride, current));
    assertEquals(1, LegAlternatives.splicedLegIndex(ride, current));
  }

  @Test
  public void spliceLastRideMovesTheWalkToTheDestination()
  {
    final TransitItinerary connection = connection();
    final LegAlternatives.Ride ride = LegAlternatives.ride(connection, 3);
    final TransitItinerary re =
        itinerary("re", ride(TransitMode.REGIONAL_RAIL, "RE 5", "re", bonnTrain(), koeln(), "09:52", "10:15"));
    final LegAlternatives.Option option = option(ride, Collections.singletonList(re), "09:52");

    final TransitItinerary changed = LegAlternatives.splice(ride, option);

    assertEquals(5, changed.getLegs().size());
    for (int i = 0; i < 3; ++i)
      assertSame(connection.getLegs().get(i), changed.getLegs().get(i));
    assertEquals("RE 5", changed.getLegs().get(3).getLineName());
    assertEquals(at("10:15"), changed.getLegs().get(4).getStartTime());
    assertEquals(at("10:17"), changed.getEndTime());
    assertEquals(connection.getStartTime(), changed.getStartTime());
    assertEquals(3, LegAlternatives.splicedLegIndex(ride, option));
  }

  @Test
  public void spliceMiddleRide()
  {
    final TransitItinerary connection = threeRides();
    final LegAlternatives.Ride ride = LegAlternatives.ride(connection, 3);
    final TransitItinerary bus =
        itinerary("b", ride(TransitMode.BUS, "610", "b", bonnTram(), bonnTrain(), "09:50", "09:58"));
    final LegAlternatives.Option option = option(ride, Collections.singletonList(bus), "09:50");
    final TransitItinerary changed = LegAlternatives.splice(ride, option);
    assertEquals(6, changed.getLegs().size());
    assertSame(connection.getLegs().get(2), changed.getLegs().get(2));
    assertEquals("610", changed.getLegs().get(3).getLineName());
    assertEquals(at("09:58"), changed.getLegs().get(4).getStartTime());
    assertSame(connection.getLegs().get(5), changed.getLegs().get(5));
    assertEquals(2, changed.getTransfers());
  }

  @Test
  public void missedConnectionIsJoinedWithTheNewRest()
  {
    final TransitItinerary connection = connection();
    final LegAlternatives.Ride ride = LegAlternatives.ride(connection, 1);
    final LegAlternatives.Option option = option(ride, firstAlternatives(), "09:39");
    assertTrue(option.missesNext());

    final TransitPlanRequest request = LegAlternatives.restRequest(ride, option, null, TransitRoutingOptions.defaults());
    assertEquals(TransitLocation.fromStopId("bhf-tram"), request.getFrom());
    // Without the search's destination: where the connection ends.
    assertEquals(TransitLocation.fromCoordinates(50.9430, 6.9589), request.getTo());
    assertEquals(at("09:49"), request.getTime());
    assertFalse(request.isArriveBy());
    final TransitLocation destination = TransitLocation.fromCoordinates(50.94, 6.95);
    assertEquals(destination,
                 LegAlternatives.restRequest(ride, option, destination, TransitRoutingOptions.defaults()).getTo());

    final TransitItinerary rest = rest("10:07");
    final TransitItinerary changed = LegAlternatives.spliceWithRest(ride, option, rest);
    assertEquals(5, changed.getLegs().size());
    assertEquals(at("09:26"), changed.getStartTime());
    assertEquals("63", changed.getLegs().get(1).getLineName());
    assertEquals("RE 5", changed.getLegs().get(3).getLineName());
    assertEquals(at("10:32"), changed.getEndTime());
    assertEquals(1, changed.getTransfers());
    assertEquals(2, LegAlternatives.restLegIndex(ride, option));
    assertTrue(changed.getId().endsWith("|rest"));
  }

  @Test
  public void pickRestTakesTheFirstArrival()
  {
    final TransitItinerary early = rest("10:07");
    final TransitItinerary later = rest("10:17");
    final TransitItinerary tooEarly = rest("09:40");
    assertSame(early, LegAlternatives.pickRest(Arrays.asList(later, tooEarly, early), at("09:49")));
    assertNull(LegAlternatives.pickRest(Collections.singletonList(tooEarly), at("09:49")));
    assertNull(LegAlternatives.pickRest(Collections.emptyList(), at("09:49")));

    // Same arrival: fewer transfers.
    final TransitItinerary twoRides = itinerary(
        "two", ride(TransitMode.BUS, "1", "1", bonnTrain(), bonnTrain(), "10:00", "10:05"),
        ride(TransitMode.REGIONAL_RAIL, "RB", "rb", bonnTrain(), koeln(), "10:06", "10:30"), walk(koeln(), end(), "10:30", "10:32"));
    assertSame(early, LegAlternatives.pickRest(Arrays.asList(twoRides, early), at("09:49")));
  }

  @NonNull
  private static LegAlternatives.Option option(@NonNull LegAlternatives.Ride ride,
                                               @NonNull List<TransitItinerary> found, @NonNull String departure)
  {
    for (LegAlternatives.Option option : LegAlternatives.options(ride, found))
      if (option.getDeparture().equals(at(departure)))
        return option;
    throw new AssertionError("No option at " + departure);
  }
}
