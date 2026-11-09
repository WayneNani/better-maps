package app.organicmaps.ev;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class EvPlanPresenterTest
{
  /** Answers plan() later, like the native planner. */
  static class FakeEngine implements EvPlanPresenter.Engine
  {
    final List<String> mOptions = new ArrayList<>();
    final List<Callback> mCallbacks = new ArrayList<>();
    final List<String> mEvaluatedStops = new ArrayList<>();
    int mCancelled;
    @Nullable
    String mEvaluation = EvTestData.EVALUATED_JSON;

    @Override
    public void plan(@NonNull String profileJson, @NonNull String optionsJson, @NonNull Callback callback)
    {
      mOptions.add(optionsJson);
      mCallbacks.add(callback);
    }

    @Override
    public void cancel()
    {
      ++mCancelled;
    }

    @Nullable
    @Override
    public String evaluate(@NonNull String profileJson, @NonNull String optionsJson, @NonNull String stopsJson)
    {
      mEvaluatedStops.add(stopsJson);
      return mEvaluation;
    }

    void answer(@NonNull String json)
    {
      mCallbacks.remove(mCallbacks.size() - 1).onResult(json);
    }

    @NonNull
    JsonObject lastOptions()
    {
      return JsonParser.parseString(mOptions.get(mOptions.size() - 1)).getAsJsonObject();
    }
  }

  /** Builds the route at once. */
  static class FakeRoute implements EvPlanPresenter.Route
  {
    final List<List<EvPlanResult.Stop>> mRemoved = new ArrayList<>();
    final List<List<EvPlanResult.Stop>> mAdded = new ArrayList<>();
    boolean mStarted;

    @Override
    public void setChargingStops(@NonNull List<EvPlanResult.Stop> oldStops, @NonNull List<EvPlanResult.Stop> newStops,
                                 @NonNull Runnable onBuilt)
    {
      mRemoved.add(new ArrayList<>(oldStops));
      mAdded.add(new ArrayList<>(newStops));
      onBuilt.run();
    }

    @Override
    public void startNavigation()
    {
      mStarted = true;
    }
  }

  static class FakeView implements EvPlanPresenter.View
  {
    int mRenders;
    boolean mClosed;

    @Override
    public void render()
    {
      ++mRenders;
    }

    @Override
    public void close()
    {
      mClosed = true;
    }
  }

  private final EvTestStore mStore = new EvTestStore();
  private final EvSettings mSettings = new EvSettings(mStore);
  private final FakeEngine mEngine = new FakeEngine();
  private final FakeRoute mRoute = new FakeRoute();
  private final FakeView mView = new FakeView();
  private final EvRouteStops mRouteStops = new EvRouteStops();
  private EvPlanPresenter mPresenter;

  @Before
  public void setUp()
  {
    final EvVehicleProfile profile = EvVehicleProfile.fromJson(EvTestData.ELROQ_JSON);
    assertNotNull(profile);
    mPresenter = new EvPlanPresenter(mSettings, profile, mEngine, mRoute, mRouteStops);
    mPresenter.attach(mView);
  }

  @After
  public void tearDown()
  {
    mPresenter.detach();
  }

  @Test
  public void startsWithOptionsAndLooksForProviders()
  {
    mPresenter.start(null);
    assertEquals(EvPlanPresenter.Screen.OPTIONS, mPresenter.getScreen());
    assertTrue(mPresenter.isProvidersLoading());
    assertEquals(1, mEngine.mCallbacks.size());
    // The route stays as it is.
    assertTrue(mRoute.mAdded.isEmpty());

    mEngine.answer(EvTestData.RESULT_JSON);
    assertFalse(mPresenter.isProvidersLoading());
    assertEquals(3, mPresenter.getProviders().size());
    assertEquals("EnBW", mPresenter.getProviders().get(0).name);
    assertEquals(EvPlanPresenter.Screen.OPTIONS, mPresenter.getScreen());
  }

  @Test
  public void batteryLevelFromTheCar()
  {
    mPresenter.start(64.4);
    assertEquals(64, mPresenter.getOptions().startSoc, 1e-9);
  }

  @Test
  public void optionsBecomeParameters()
  {
    mPresenter.start(null);
    mPresenter.setStartSoc(82);
    mPresenter.setMinArrivalSoc(20);
    mPresenter.setMinStopSoc(10);
    mPresenter.setPriority(EvPlanOptions.PRIORITY_FEWEST_STOPS);
    mPresenter.setMinPowerKw(150);
    mPresenter.setAmenity(EvPlanResult.Amenity.TOILETS, true);
    mPresenter.setAmenity(EvPlanResult.Amenity.PLAYGROUND, true);
    mPresenter.setAmenity(EvPlanResult.Amenity.TOILETS, false);
    // Neutral -> preferred -> excluded -> neutral.
    mPresenter.toggleProvider("enbw");
    mPresenter.toggleProvider("ionity");
    mPresenter.toggleProvider("ionity");
    mPresenter.toggleProvider("tesla");
    mPresenter.toggleProvider("tesla");
    mPresenter.toggleProvider("tesla");
    assertTrue(mPresenter.isPreferred("enbw"));
    assertTrue(mPresenter.isExcluded("ionity"));
    assertFalse(mPresenter.isPreferred("tesla") || mPresenter.isExcluded("tesla"));
    // Out of range.
    mPresenter.setStartSoc(140);

    mPresenter.plan();
    final JsonObject o = mEngine.lastOptions();
    assertEquals(100, o.get("startSoc").getAsDouble(), 1e-9);
    assertEquals(20, o.get("minArrivalSoc").getAsDouble(), 1e-9);
    assertEquals(10, o.get("minStopSoc").getAsDouble(), 1e-9);
    assertEquals("fewest_stops", o.get("priority").getAsString());
    assertEquals(150, o.get("minPowerKw").getAsDouble(), 1e-9);
    assertEquals("[\"playground\"]", o.get("preferredAmenities").toString());
    assertEquals("[\"enbw\"]", o.get("preferredProviders").toString());
    assertEquals("[\"ionity\"]", o.get("excludedProviders").toString());
    // Stored for the next time.
    assertEquals(EvPlanOptions.PRIORITY_FEWEST_STOPS, mSettings.getOptions().priority);
    assertEquals(150, mSettings.getOptions().minPowerKw, 1e-9);
  }

  @Test
  public void planIsAddedToTheRouteAndEvaluated()
  {
    mPresenter.start(null);
    mPresenter.plan();
    assertEquals(EvPlanPresenter.Screen.PLANNING, mPresenter.getScreen());
    mEngine.answer(EvTestData.RESULT_JSON);

    assertEquals(EvPlanPresenter.Screen.RESULT, mPresenter.getScreen());
    // The stop is an intermediate point of the route now.
    final List<EvPlanResult.Stop> added = mRoute.mAdded.get(mRoute.mAdded.size() - 1);
    assertEquals(1, added.size());
    assertEquals("EnBW Aurach", added.get(0).title);
    // Evaluated with the real route.
    assertEquals(1, mEngine.mEvaluatedStops.size());
    final EvPlanResult result = mPresenter.getResult();
    assertNotNull(result);
    assertEquals(18, result.plan.stops.get(0).arrivalSocPct, 1e-9);
    assertEquals(17300, result.plan.totalTimeSec, 1e-9);
    assertEquals("EnBW Aurach", result.plan.stops.get(0).title);
    assertEquals(4, result.plan.stops.get(0).amenities.size());
    // The route knows its charging stops.
    assertSame(result.plan, mRouteStops.getPlan());
    assertEquals(EvTestData.ELROQ_JSON.length() > 0, mRouteStops.getProfileJson() != null);

    // Start: the navigation, the planner closes.
    mPresenter.startNavigation();
    assertTrue(mRoute.mStarted);
    assertTrue(mView.mClosed);
  }

  @Test
  public void planningAgainRemovesTheStopsFirst()
  {
    mPresenter.start(null);
    mPresenter.plan();
    mEngine.answer(EvTestData.RESULT_JSON);
    final EvPlanResult.Stop stop = mRouteStops.getStops().get(0);

    mPresenter.showOptions();
    mPresenter.plan();
    // Removed before the planner runs, so that it plans on the route without them.
    assertEquals(List.of(stop), mRoute.mRemoved.get(mRoute.mRemoved.size() - 1));
    assertTrue(mRoute.mAdded.get(mRoute.mAdded.size() - 1).isEmpty());
    assertFalse(mRouteStops.hasStops());
  }

  @Test
  public void alternativeReplacesTheStops()
  {
    mPresenter.start(null);
    mPresenter.plan();
    mEngine.answer(EvTestData.RESULT_JSON);
    mEngine.mEvaluation = null;

    mPresenter.chooseAlternative(0);
    final EvPlanResult result = mPresenter.getResult();
    assertNotNull(result);
    assertEquals(2, result.plan.stops.size());
    assertEquals("Ionity Montabaur", result.plan.stops.get(0).title);
    // The old plan is an alternative now.
    assertEquals("EnBW Aurach", result.alternatives.get(0).stops.get(0).title);
    assertEquals(2, mRoute.mAdded.get(mRoute.mAdded.size() - 1).size());
    assertEquals(1, mRoute.mRemoved.get(mRoute.mRemoved.size() - 1).size());
    assertEquals(2, mRouteStops.getStops().size());
    assertEquals(EvPlanPresenter.Screen.RESULT, mPresenter.getScreen());
  }

  @Test
  public void alternativesComparedOnTheSameBasis()
  {
    // The real route takes 20 min longer than planned.
    mEngine.mEvaluation = EvTestData.EVALUATED_JSON.replace("\"totalTimeSec\":17300", "\"totalTimeSec\":18480");
    mPresenter.start(null);
    mPresenter.plan();
    mEngine.answer(EvTestData.RESULT_JSON);
    final EvPlanResult result = mPresenter.getResult();
    assertNotNull(result);
    final EvPlanResult.Plan main = result.plan;
    assertTrue(main.evaluated);
    assertEquals(18480, main.totalTimeSec, 1e-9);
    assertEquals(17280, main.plannedTotalTimeSec, 1e-9);
    final EvPlanResult.Plan alternative = result.alternatives.get(0);
    assertFalse(alternative.evaluated);
    // Planned 4 min later than the planned main plan: not 16 min earlier than the real one.
    final EvFormatter formatter = new EvFormatter(new FakeEvTexts(), ZoneId.of("UTC"));
    assertEquals("2 shorter stops, arrival 4 min later, with 40 %",
                 formatter.alternativeDescription(alternative, main));

    // Chosen: evaluated with the real route as well. The former main plan is an alternative with its real numbers.
    mEngine.mEvaluation = EvTestData.EVALUATED_JSON.replace("\"totalTimeSec\":17300", "\"totalTimeSec\":18000");
    mPresenter.chooseAlternative(0);
    assertTrue(result.plan.evaluated);
    assertTrue(result.alternatives.get(0).evaluated);
    assertEquals("1 longer stops, arrival 8 min later, with 23 %",
                 formatter.alternativeDescription(result.alternatives.get(0), result.plan));
  }

  @Test
  public void chargeLongerKeepsTheAlternativesComparable()
  {
    mEngine.mEvaluation = EvTestData.EVALUATED_JSON;
    mPresenter.start(null);
    mPresenter.plan();
    mEngine.answer(EvTestData.RESULT_JSON);
    final EvPlanResult result = mPresenter.getResult();
    assertNotNull(result);
    final EvFormatter formatter = new EvFormatter(new FakeEvTexts(), ZoneId.of("UTC"));
    final EvPlanResult.Plan alternative = result.alternatives.get(0);
    assertEquals("2 shorter stops, arrival 4 min later, with 40 %",
                 formatter.alternativeDescription(alternative, result.plan));

    // 10 % more at the only stop: 9 min longer, arrival with 34 % instead of 23 % (the evaluation says so too).
    mPresenter.showStop(0);
    mEngine.mEvaluation = EvTestData.EVALUATED_JSON.replace("\"totalTimeSec\":17300", "\"totalTimeSec\":17840")
                              .replace("\"arrivalSocPct\":23", "\"arrivalSocPct\":34");
    mPresenter.chargeLonger();
    assertEquals(34, result.plan.arrivalSocPct, 1e-9);
    assertEquals(17280 + 540, result.plan.plannedTotalTimeSec, 1e-9);
    assertEquals(24 + 11, result.plan.plannedArrivalSocPct, 1e-9);
    // The alternative keeps its arrival (not 11 % more because of the user's choice) and is 5 min earlier now,
    // which is only claimed with real detours.
    assertEquals("2 shorter stops, same arrival, with 40 %",
                 formatter.alternativeDescription(alternative, result.plan));
    for (EvPlanResult.Stop stop : alternative.stops)
      stop.detourChecked = true;
    assertEquals("2 shorter stops, arrival 5 min earlier, with 40 %",
                 formatter.alternativeDescription(alternative, result.plan));
  }

  @Test
  public void stopDetail()
  {
    mPresenter.start(null);
    mPresenter.plan();
    mEngine.answer(EvTestData.RESULT_JSON);

    mPresenter.showStop(0);
    assertEquals(EvPlanPresenter.Screen.DETAIL, mPresenter.getScreen());
    final EvPlanResult.Stop stop = mPresenter.getSelectedStop();
    assertNotNull(stop);

    // Charge longer: up to the longer state of charge, evaluated again.
    mEngine.mEvaluation = null;
    final int evaluations = mEngine.mEvaluatedStops.size();
    mPresenter.chargeLonger();
    assertEquals(evaluations + 1, mEngine.mEvaluatedStops.size());
    assertTrue(mEngine.mEvaluatedStops.get(evaluations).contains("\"departureSoc\":80.0"));

    // Keep it.
    mPresenter.keepStop();
    assertEquals(EvPlanPresenter.Screen.RESULT, mPresenter.getScreen());

    // Another stop: plans again without this station.
    mPresenter.showStop(0);
    mPresenter.chooseOtherStop();
    assertEquals(EvPlanPresenter.Screen.PLANNING, mPresenter.getScreen());
    assertEquals("[\"Germany:1\"]", mEngine.lastOptions().get("excludedStations").toString());
  }

  @Test
  public void unreachable()
  {
    mPresenter.start(null);
    mPresenter.plan();
    mEngine.answer(EvTestData.UNREACHABLE_JSON);
    assertEquals(EvPlanPresenter.Screen.ERROR, mPresenter.getScreen());
    assertNotNull(mPresenter.getErrorResult());
    assertEquals(120000, mPresenter.getErrorResult().reachableUntilM, 1e-9);
    assertNull(mPresenter.getResult());
    assertFalse(mRouteStops.hasStops());
    // Back to the options.
    assertTrue(mPresenter.back());
    assertEquals(EvPlanPresenter.Screen.OPTIONS, mPresenter.getScreen());
  }

  @Test
  public void backAndCancel()
  {
    mPresenter.start(null);
    // Options without a plan: back closes.
    assertFalse(mPresenter.back());

    mPresenter.plan();
    // Planning: back cancels and shows the options, a late result is ignored.
    assertTrue(mPresenter.back());
    assertEquals(EvPlanPresenter.Screen.OPTIONS, mPresenter.getScreen());
    assertTrue(mEngine.mCancelled > 0);
    mEngine.answer(EvTestData.RESULT_JSON);
    assertEquals(EvPlanPresenter.Screen.OPTIONS, mPresenter.getScreen());
    assertFalse(mRouteStops.hasStops());

    mPresenter.plan();
    mEngine.answer(EvTestData.RESULT_JSON);
    mPresenter.showStop(0);
    assertTrue(mPresenter.back());
    assertEquals(EvPlanPresenter.Screen.RESULT, mPresenter.getScreen());
    mPresenter.showOptions();
    // Options of an applied plan: back to the plan.
    assertTrue(mPresenter.back());
    assertEquals(EvPlanPresenter.Screen.RESULT, mPresenter.getScreen());
    assertFalse(mPresenter.back());
  }

  @Test
  public void reopenedWithThePlanOfTheRoute()
  {
    mPresenter.start(null);
    mPresenter.plan();
    mEngine.answer(EvTestData.RESULT_JSON);

    final EvVehicleProfile profile = EvVehicleProfile.fromJson(EvTestData.ELROQ_JSON);
    assertNotNull(profile);
    final EvPlanPresenter again = new EvPlanPresenter(mSettings, profile, mEngine, mRoute, mRouteStops);
    again.start(null);
    assertEquals(EvPlanPresenter.Screen.RESULT, again.getScreen());
    assertNotNull(again.getResult());
    assertSame(mRouteStops.getPlan(), again.getResult().plan);
  }

  @Test
  public void chargingTimeOfTheStopsAhead()
  {
    final EvPlanResult result = EvJson.parseResult(EvTestData.RESULT_JSON);
    final EvPlanResult.Plan plan = result.alternatives.get(0);
    mRouteStops.set(plan, EvTestData.ELROQ_JSON, new EvPlanOptions());
    final EvPlanResult.Stop first = plan.stops.get(0);
    final EvPlanResult.Stop second = plan.stops.get(1);
    second.lat = 49.5;
    second.lon = 10.5;

    final RouteMarkData start = mark(RouteMarkType.Start, 50.7, 7.1, false);
    final RouteMarkData finish = mark(RouteMarkType.Finish, 49.45, 11.07, false);
    RouteMarkData[] points = {start, mark(RouteMarkType.Intermediate, first.lat, first.lon, false),
                              mark(RouteMarkType.Intermediate, second.lat + 0.0005, second.lon, false), finish};
    assertEquals(900 + 840, mRouteStops.getRemainingStopTimeSec(points), 1e-9);
    assertSame(first, mRouteStops.getNextStop(points));
    assertTrue(mRouteStops.isChargingStop(points[1]));
    assertFalse(mRouteStops.isChargingStop(finish));

    // The first one is passed.
    points[1] = mark(RouteMarkType.Intermediate, first.lat, first.lon, true);
    assertEquals(840, mRouteStops.getRemainingStopTimeSec(points), 1e-9);
    assertSame(second, mRouteStops.getNextStop(points));

    // Another intermediate point of the user is no charging stop.
    points[2] = mark(RouteMarkType.Intermediate, 48.0, 9.0, false);
    assertEquals(0, mRouteStops.getRemainingStopTimeSec(points), 1e-9);
    mRouteStops.clear();
    assertFalse(mRouteStops.hasStops());
  }

  @Test
  public void stopWithALongDetourIsAvoided()
  {
    // The real route to the stop is 20 km longer (services on the other side of the motorway): planned again
    // without it.
    mEngine.mEvaluation =
        EvTestData.EVALUATED_JSON.replace("\"distanceAlongRouteM\":280000.0", "\"distanceAlongRouteM\":298000.0")
            .replace("\"distanceM\":415000", "\"distanceM\":437000");
    mPresenter.start(null);
    mPresenter.plan();
    mEngine.answer(EvTestData.RESULT_JSON);
    assertEquals(EvPlanPresenter.Screen.PLANNING, mPresenter.getScreen());
    assertEquals("[\"Germany:1\"]", mEngine.lastOptions().get("excludedStations").toString());
    // The stop was removed from the route again.
    assertEquals(1, mRoute.mRemoved.get(mRoute.mRemoved.size() - 1).size());

    // The new plan is fine.
    mEngine.mEvaluation = EvTestData.EVALUATED_JSON;
    mEngine.answer(EvTestData.RESULT_JSON);
    assertEquals(EvPlanPresenter.Screen.RESULT, mPresenter.getScreen());
  }

  @Test
  public void detourOfTheRealRoute()
  {
    final EvPlanResult planned = EvJson.parseResult(EvTestData.RESULT_JSON);
    final EvPlanResult.Plan real = EvJson.parsePlan(EvTestData.EVALUATED_JSON);
    assertNotNull(real);
    // 2 km longer: fine.
    assertTrue(EvPlanPresenter.findDetourStops(planned.plan, real).isEmpty());
    // Only the way to the stop is longer, e.g. a station a bit off the motorway: fine.
    real.stops.get(0).distanceAlongRouteM += 10000;
    real.distanceM += 10000;
    assertTrue(EvPlanPresenter.findDetourStops(planned.plan, real).isEmpty());
    // The way to it and from it: services on the other side of the motorway.
    real.distanceM += 10000;
    assertEquals(List.of("Germany:1"), EvPlanPresenter.findDetourStops(planned.plan, real));
  }

  @Test
  public void mostCommonProvidersOnly()
  {
    mPresenter.start(null);
    final StringBuilder providers = new StringBuilder();
    for (int i = 0; i < 12; ++i)
      providers.append(i == 0 ? "" : ",")
          .append("{\"key\":\"p")
          .append(i)
          .append("\",\"name\":\"P")
          .append(i)
          .append("\",\"stations\":")
          .append(20 - i)
          .append("}");
    mEngine.answer("{\"status\":\"ok\",\"plan\":{\"stops\":[]},\"providers\":[" + providers + "]}");
    assertEquals(EvPlanPresenter.MAX_PROVIDERS, mPresenter.getProviders().size());
    // A chosen one is always there, first.
    mPresenter.toggleProvider("p11");
    assertEquals("p11", mPresenter.getProviders().get(0).key);
    assertEquals(EvPlanPresenter.MAX_PROVIDERS, mPresenter.getProviders().size());
  }

  @NonNull
  private static RouteMarkData mark(@NonNull RouteMarkType type, double lat, double lon, boolean passed)
  {
    return new RouteMarkData("", "", type, 0, true, false, passed, lat, lon);
  }
}
