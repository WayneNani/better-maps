package app.organicmaps.ev;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.junit.Test;

public class EvFormatterTest
{
  // 2026-11-09 10:30 UTC.
  private static final long NOW = 1794220200L;

  private final EvFormatter mFormatter = new EvFormatter(new FakeEvTexts(), ZoneId.of("UTC"));
  private final EvPlanResult mResult = EvJson.parseResult(EvTestData.RESULT_JSON);

  @Test
  public void durations()
  {
    assertEquals("0 min", EvFormatter.duration(0));
    assertEquals("27 min", EvFormatter.duration(27 * 60));
    assertEquals("59 min", EvFormatter.duration(59 * 60 + 10));
    assertEquals("1:00 h", EvFormatter.duration(3599));
    assertEquals("4:58 h", EvFormatter.duration(4 * 3600 + 58 * 60));
    assertEquals(24, EvFormatter.soc(23.6));
    assertEquals(100, EvFormatter.soc(104));
    assertEquals(0, EvFormatter.soc(-3));
  }

  @Test
  public void distances()
  {
    assertEquals("90 m", EvFormatter.distance(88, Locale.ROOT));
    assertEquals("1.2 km", EvFormatter.distance(1234, Locale.ROOT));
    assertEquals("1,2 km", EvFormatter.distance(1234, Locale.GERMANY));
    assertEquals("278 km", EvFormatter.distance(278400, Locale.ROOT));
  }

  @Test
  public void summaryOfThePlan()
  {
    final EvPlanResult.Plan plan = mResult.plan;
    assertEquals("4:48 h", mFormatter.total(plan));
    assertEquals("412 km · 1 charging stop", mFormatter.distanceAndStops(plan));
    // Departure 10:30 UTC + 4:48 h.
    assertEquals("4:21 h driving · 27 min charging · arrival 15:18 with 24 %", mFormatter.summary(plan, NOW));
  }

  @Test
  public void stop()
  {
    final EvPlanResult.Stop stop = mResult.plan.stops.get(0);
    assertEquals("EnBW Aurach", stop.title);
    assertEquals("CCS up to 300 kW · 8 charging points · EnBW", mFormatter.stopDetails(stop));
    assertEquals("arrive 16 % → 70 %", mFormatter.arriveAndCharge(stop));
    assertEquals("27 min", mFormatter.stopDuration(stop));
    assertEquals("Charge 16 % → 70 %", mFormatter.charge(stop));
    assertEquals("Charge longer → 80 % (+9 min), arrival with 34 %", mFormatter.longer(stop));
    assertEquals("Charging station · EnBW · 24/7", mFormatter.stationHeader(stop));

    final List<String[]> sockets = mFormatter.socketRows(stop);
    assertEquals(3, sockets.size());
    assertEquals("CCS", sockets.get(0)[0]);
    assertEquals("4 × 300 kW", sockets.get(0)[1]);
    assertEquals("Type 2", sockets.get(2)[0]);
    assertEquals("2 × 22 kW", sockets.get(2)[1]);

    final List<EvPlanResult.Amenity> nearest = EvFormatter.nearestByType(stop);
    assertEquals(4, nearest.size());
    assertEquals("playground", nearest.get(0).type);
    assertEquals("playground 90 m", mFormatter.amenity(nearest.get(0)));
    assertEquals("toilets (services) 60 m", mFormatter.amenity(nearest.get(1)));
    assertEquals("food · open on arrival", mFormatter.amenityWithOpening(nearest.get(2)));
    assertEquals("shopping", mFormatter.amenityWithOpening(nearest.get(3)));

    // With a following stop: it charges less.
    stop.longer.laterStopsSavedSec = 190;
    stop.longer.arrivalSocPct = 24;
    assertEquals("Charge longer → 80 % (+9 min), later stops 3 min shorter, arrival with 24 %",
                 mFormatter.longer(stop));

    // The following stop is not needed any more.
    final EvPlanResult.Plan alternative = mResult.alternatives.get(0);
    final EvPlanResult.Stop first = alternative.stops.get(0);
    first.longer.droppedStops.add(1);
    first.longer.arrivalSocPct = 25;
    assertEquals("Charge longer → 85 % (+9 min), stop at EnBW Aurach not needed, arrival with 25 %",
                 mFormatter.longer(first, alternative));
    // Without the plan the names are unknown.
    assertEquals("Charge longer → 85 % (+9 min), 1 later stop not needed, arrival with 25 %",
                 mFormatter.longer(first));
    first.longer.droppedStops.add(2);
    assertEquals("Charge longer → 85 % (+9 min), 2 later stops not needed, arrival with 25 %",
                 mFormatter.longer(first, alternative));

    stop.longer = null;
    assertEquals("", mFormatter.longer(stop));
  }

  @Test
  public void alternative()
  {
    final EvPlanResult.Plan alternative = mResult.alternatives.get(0);
    assertEquals("Alternative: Ionity Montabaur + EnBW Aurach", mFormatter.alternativeTitle(alternative));
    assertEquals("2 shorter stops, arrival 4 min later, with 41 %",
                 mFormatter.alternativeDescription(alternative, mResult.plan));
  }

  @Test
  public void alternativeOfAnEvaluatedPlan()
  {
    // The main plan on the real route takes 20 min longer and arrives with 1 % less (e.g. a longer detour than
    // planned). The alternative is compared with the planned numbers: 4 min later, not 16 min earlier.
    final EvPlanResult.Plan main = mResult.plan;
    final EvPlanResult.Plan evaluated = EvJson.parsePlan(EvTestData.EVALUATED_JSON);
    assertNotNull(evaluated);
    evaluated.totalTimeSec = main.totalTimeSec + 20 * 60;
    main.takeEvaluation(evaluated);
    assertTrue(main.evaluated);
    assertEquals(17280, main.plannedTotalTimeSec, 1e-9);
    assertEquals(24, main.plannedArrivalSocPct, 1e-9);
    final EvPlanResult.Plan alternative = mResult.alternatives.get(0);
    assertEquals("2 shorter stops, arrival 4 min later, with 40 %",
                 mFormatter.alternativeDescription(alternative, main));
  }

  @Test
  public void earlierOnlyWithRealDetours()
  {
    // The planner says 10 min earlier.
    final EvPlanResult.Plan main = mResult.plan;
    final EvPlanResult.Plan alternative = mResult.alternatives.get(0);
    alternative.plannedTotalTimeSec = main.plannedTotalTimeSec - 600;
    alternative.totalTimeSec = alternative.plannedTotalTimeSec;
    // With estimated detours that may be wrong (e.g. services on the other side of the motorway): not earlier.
    assertFalse(alternative.hasCheckedDetours());
    assertEquals("2 shorter stops, same arrival, with 41 %", mFormatter.alternativeDescription(alternative, main));
    // With the real detours from the router: earlier.
    for (EvPlanResult.Stop stop : alternative.stops)
      stop.detourChecked = true;
    assertTrue(alternative.hasCheckedDetours());
    assertEquals("2 shorter stops, arrival 10 min earlier, with 41 %",
                 mFormatter.alternativeDescription(alternative, main));
  }

  @Test
  public void alternativeThatWasEvaluated()
  {
    // The user chose the alternative and back: both evaluated with the real route, their real numbers count.
    final EvPlanResult.Plan main = mResult.plan;
    final EvPlanResult.Plan alternative = mResult.alternatives.get(0);
    final EvPlanResult.Plan mainReal = EvJson.parsePlan(EvTestData.EVALUATED_JSON);
    assertNotNull(mainReal);
    main.takeEvaluation(mainReal);
    final EvPlanResult.Plan alternativeReal = EvJson.parsePlan(EvTestData.EVALUATED_JSON);
    assertNotNull(alternativeReal);
    alternativeReal.totalTimeSec = mainReal.totalTimeSec - 7 * 60;
    alternativeReal.arrivalSocPct = 33;
    alternative.takeEvaluation(alternativeReal);
    assertEquals("2 shorter stops, arrival 7 min earlier, with 33 %",
                 mFormatter.alternativeDescription(alternative, main));
  }

  @Test
  public void errors()
  {
    assertEquals("unreachable between 120 km and 380 km",
                 mFormatter.error(EvJson.parseResult(EvTestData.UNREACHABLE_JSON)));
    assertEquals("no route", mFormatter.error(EvJson.parseResult("{\"status\":\"no_route\"}")));
    assertEquals("error", mFormatter.error(EvJson.parseResult("not json")));
  }

  @Test
  public void noStopNeeded()
  {
    final EvPlanResult result = EvJson.parseResult(
        "{\"status\":\"ok\",\"plan\":{\"stops\":[],\"distanceM\":95000,\"driveTimeSec\":3600,\"stopTimeSec\":0,"
        + "\"totalTimeSec\":3600,\"arrivalSocPct\":61.6}}");
    assertTrue(result.isOk());
    assertEquals("95 km · no charging stop needed", mFormatter.distanceAndStops(result.plan));
    assertEquals("1:00 h driving · 0 min charging · arrival 11:30 with 62 %", mFormatter.summary(result.plan, NOW));
  }

  @Test
  public void bar()
  {
    final List<EvBarLayout.Piece> pieces = EvBarLayout.layout(mResult.plan);
    assertEquals(3, pieces.size());
    assertEquals(EvBarLayout.Kind.DRIVE, pieces.get(0).kind);
    assertEquals(10080.0 / 17280, pieces.get(0).fraction, 1e-9);
    assertEquals(EvBarLayout.Kind.CHARGE, pieces.get(1).kind);
    assertEquals(1620.0 / 17280, pieces.get(1).fraction, 1e-9);
    assertEquals(EvBarLayout.Kind.DRIVE, pieces.get(2).kind);
    double sum = 0;
    for (EvBarLayout.Piece p : pieces)
      sum += p.fraction;
    assertEquals(1, sum, 1e-9);

    // Two stops: drive, charge, drive, charge, drive.
    assertEquals(5, EvBarLayout.layout(mResult.alternatives.get(0)).size());
    assertTrue(EvBarLayout.layout(new EvPlanResult.Plan()).isEmpty());
  }

  @Test
  public void jsonOfTheCore()
  {
    assertTrue(mResult.isOk());
    assertEquals(147, mResult.candidates);
    assertEquals(3, mResult.providers.size());
    assertEquals("Ionity", mResult.providers.get(1).name);
    final EvPlanResult.Stop stop = mResult.plan.stops.get(0);
    assertEquals(Boolean.TRUE, stop.amenities.get(2).openAtArrival);
    assertNull(stop.amenities.get(0).openAtArrival);
    assertEquals(List.of("playground"), stop.matchedAmenities);

    final EvPlanResult.Plan evaluated = EvJson.parsePlan(EvTestData.EVALUATED_JSON);
    assertNotNull(evaluated);
    assertNull(EvJson.parsePlan("{\"status\":\"no_route\"}"));
    assertNull(EvJson.parsePlan("x"));

    // The stops for the evaluation.
    final String stops = EvJson.stopsToJson(mResult.plan.stops);
    assertEquals("[{\"key\":\"Germany:1\",\"title\":\"EnBW Aurach\",\"lat\":49.9,\"lon\":10.1,\"powerKw\":300.0,"
                     + "\"dc\":true,\"departureSoc\":70.0}]",
                 stops);
  }
}
