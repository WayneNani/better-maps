package app.organicmaps.ev;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Logic of the charging planner in the route planner, without Android: options, planning, the plan with its stops
 * added to the route, alternatives and the details of a stop (screens 2-4 of docs/mockups/ladeplanung.png).
 * <p>
 * A plan is added to the route right away: its stops become intermediate points and the route is built again, then the
 * plan is evaluated with the real route. Planning again removes the stops first, so that the planner sees the route
 * without them.
 */
public class EvPlanPresenter
{
  public enum Screen
  {
    OPTIONS,
    /** Planning or adding the plan to the route. */
    PLANNING,
    RESULT,
    DETAIL,
    ERROR
  }

  /** The native planner. */
  public interface Engine
  {
    interface Callback
    {
      void onResult(@NonNull String json);
    }

    void plan(@NonNull String profileJson, @NonNull String optionsJson, @NonNull Callback callback);

    void cancel();

    /** See EvPlanner.evaluate(): the stops with the current route, null on errors. */
    @Nullable
    String evaluate(@NonNull String profileJson, @NonNull String optionsJson, @NonNull String stopsJson);
  }

  /** The route of the route planner. */
  public interface Route
  {
    /**
     * Replaces the charging stops |oldStops| of the route with |newStops| and builds it again. Calls |onBuilt| once
     * it is built, right away if nothing changes.
     */
    void setChargingStops(@NonNull List<EvPlanResult.Stop> oldStops, @NonNull List<EvPlanResult.Stop> newStops,
                          @NonNull Runnable onBuilt);

    void startNavigation();
  }

  public interface View
  {
    void render();

    void close();
  }

  @NonNull
  private final EvSettings mSettings;
  @NonNull
  private final Engine mEngine;
  @NonNull
  private final Route mRoute;
  @NonNull
  private final EvRouteStops mRouteStops;
  @NonNull
  private final EvVehicleProfile mProfile;
  @Nullable
  private View mView;

  @NonNull
  private Screen mScreen = Screen.OPTIONS;
  @NonNull
  private EvPlanOptions mOptions;
  @Nullable
  private EvPlanResult mResult;
  @NonNull
  private List<EvPlanResult.Provider> mProviders = new ArrayList<>();
  private boolean mProvidersLoading;
  private int mSelectedStop = -1;
  @Nullable
  private EvPlanResult mErrorResult;
  // Ignores results of earlier requests.
  private int mRequest;
  // Plans made again because a stop needed a long detour on the real route.
  private int mDetourRetries;

  /** A stop that makes the real route longer by more than this is avoided, e.g. motorway services on the other side. */
  static final double MAX_DETOUR_M = 8000;
  static final int MAX_DETOUR_RETRIES = 2;

  public EvPlanPresenter(@NonNull EvSettings settings, @NonNull EvVehicleProfile profile, @NonNull Engine engine,
                         @NonNull Route route, @NonNull EvRouteStops routeStops)
  {
    mSettings = settings;
    mProfile = profile;
    mEngine = engine;
    mRoute = route;
    mRouteStops = routeStops;
    mOptions = settings.getOptions();
  }

  public void attach(@NonNull View view)
  {
    mView = view;
    render();
  }

  public void detach()
  {
    mView = null;
  }

  /** Opens the planner: the plan of the route if it has one, otherwise the options. */
  public void start(@Nullable Double carSoc)
  {
    final EvPlanResult.Plan applied = mRouteStops.getPlan();
    if (applied != null && mRouteStops.getOptions() != null)
    {
      mOptions = mRouteStops.getOptions().copy();
      mResult = new EvPlanResult();
      mResult.status = EvPlanResult.STATUS_OK;
      mResult.plan = applied;
      mScreen = Screen.RESULT;
    }
    else
    {
      mScreen = Screen.OPTIONS;
      if (carSoc != null)
        mOptions.startSoc = Math.round(carSoc);
      loadProviders();
    }
    render();
  }

  // Getters for the view.

  @NonNull
  public Screen getScreen()
  {
    return mScreen;
  }

  @NonNull
  public EvPlanOptions getOptions()
  {
    return mOptions;
  }

  @NonNull
  public EvVehicleProfile getProfile()
  {
    return mProfile;
  }

  @Nullable
  public EvPlanResult getResult()
  {
    return mResult;
  }

  @Nullable
  public EvPlanResult.Stop getSelectedStop()
  {
    if (mResult == null || mSelectedStop < 0 || mSelectedStop >= mResult.plan.stops.size())
      return null;
    return mResult.plan.stops.get(mSelectedStop);
  }

  /** Providers in the options: the ones with most stations along the route. */
  static final int MAX_PROVIDERS = 8;

  /**
   * Providers along the route, preferred and excluded ones first, then the others with most stations (at most
   * {@link #MAX_PROVIDERS} in all).
   */
  @NonNull
  public List<EvPlanResult.Provider> getProviders()
  {
    final List<EvPlanResult.Provider> providers = new ArrayList<>(mProviders);
    for (String key : concat(mOptions.preferredProviders, mOptions.excludedProviders))
    {
      boolean known = false;
      for (EvPlanResult.Provider p : providers)
        known |= p.key.equals(key);
      if (!known)
      {
        final EvPlanResult.Provider p = new EvPlanResult.Provider();
        p.key = key;
        p.name = key;
        providers.add(p);
      }
    }
    final List<EvPlanResult.Provider> sorted = new ArrayList<>();
    for (EvPlanResult.Provider p : providers)
      if (isChosen(p.key))
        sorted.add(p);
    for (EvPlanResult.Provider p : providers)
      if (!isChosen(p.key) && sorted.size() < MAX_PROVIDERS)
        sorted.add(p);
    return sorted;
  }

  public boolean isProvidersLoading()
  {
    return mProvidersLoading;
  }

  /** The result of a failed planning, for the error message. */
  @Nullable
  public EvPlanResult getErrorResult()
  {
    return mErrorResult;
  }

  // Options.

  /** Replaces the options, e.g. with those of the plan of the route. */
  public void setOptions(@NonNull EvPlanOptions options)
  {
    mOptions = options.copy();
    render();
  }

  public void setStartSoc(int soc)
  {
    mOptions.startSoc = clamp(soc, 1, 100);
    render();
  }

  public void setMinArrivalSoc(int soc)
  {
    mOptions.minArrivalSoc = clamp(soc, 0, 80);
    render();
  }

  public void setMinStopSoc(int soc)
  {
    mOptions.minStopSoc = clamp(soc, 0, 50);
    render();
  }

  public void setPriority(@NonNull String priority)
  {
    mOptions.priority = priority;
    render();
  }

  public void setMinPowerKw(int kw)
  {
    mOptions.minPowerKw = kw;
    render();
  }

  /** Cycles a provider: neutral → preferred → excluded → neutral. */
  public void toggleProvider(@NonNull String key)
  {
    if (mOptions.preferredProviders.remove(key))
      mOptions.excludedProviders.add(key);
    else if (!mOptions.excludedProviders.remove(key))
      mOptions.preferredProviders.add(key);
    render();
  }

  public boolean isPreferred(@NonNull String key)
  {
    return mOptions.preferredProviders.contains(key);
  }

  public boolean isExcluded(@NonNull String key)
  {
    return mOptions.excludedProviders.contains(key);
  }

  public void setAmenity(@NonNull String type, boolean preferred)
  {
    mOptions.preferredAmenities.remove(type);
    if (preferred)
      mOptions.preferredAmenities.add(type);
    render();
  }

  // Actions.

  /** Plans with the options and adds the plan to the route. */
  public void plan()
  {
    mDetourRetries = 0;
    planAgain();
  }

  private void planAgain()
  {
    mSettings.setOptions(mOptions);
    mScreen = Screen.PLANNING;
    render();
    final int request = ++mRequest;
    final List<EvPlanResult.Stop> applied = new ArrayList<>(mRouteStops.getStops());
    mRouteStops.clear();
    final String options = optionsJson();
    mRoute.setChargingStops(applied, Collections.emptyList(), () -> {
      if (request != mRequest)
        return;
      mEngine.plan(mProfile.toJson(), options, json -> onPlanResult(request, json));
    });
  }

  private void onPlanResult(int request, @NonNull String json)
  {
    if (request != mRequest)
      return;
    final EvPlanResult result = EvJson.parseResult(json);
    if (!result.providers.isEmpty())
      mProviders = result.providers;
    if (!result.isOk())
    {
      mResult = null;
      mErrorResult = result;
      mScreen = Screen.ERROR;
      render();
      return;
    }
    mResult = result;
    apply(result.plan, request);
  }

  /** Adds the stops of |plan| to the route (instead of the current ones) and evaluates it with the new route. */
  private void apply(@NonNull EvPlanResult.Plan plan, int request)
  {
    mScreen = Screen.PLANNING;
    render();
    final List<EvPlanResult.Stop> applied = new ArrayList<>(mRouteStops.getStops());
    mRouteStops.clear();
    mRoute.setChargingStops(applied, plan.stops, () -> {
      if (request != mRequest)
        return;
      final String detourStop = evaluate(plan);
      if (detourStop != null && mDetourRetries < MAX_DETOUR_RETRIES)
      {
        // The station is next to the route, but the real route to it is long, e.g. services on the other side of
        // the motorway: plan again without it.
        ++mDetourRetries;
        if (!mOptions.excludedStations.contains(detourStop))
          mOptions.excludedStations.add(detourStop);
        mRouteStops.set(plan, mProfile.toJson(), mOptions.copy());
        planAgain();
        return;
      }
      mRouteStops.set(plan, mProfile.toJson(), mOptions.copy());
      mScreen = Screen.RESULT;
      render();
    });
  }

  /**
   * Evaluates |plan| with the current route, in which its stops are.
   *
   * @return the key of a stop that needs a detour of more than {@link #MAX_DETOUR_M} on the real route, if any.
   */
  @Nullable
  private String evaluate(@NonNull EvPlanResult.Plan plan)
  {
    if (plan.stops.isEmpty())
      return null;
    final String json = mEngine.evaluate(mProfile.toJson(), optionsJson(), EvJson.stopsToJson(plan.stops));
    final EvPlanResult.Plan evaluated = EvJson.parsePlan(json);
    if (evaluated == null)
      return null;
    final String detourStop = findDetourStop(plan, evaluated);
    plan.takeEvaluation(evaluated);
    return detourStop;
  }

  /**
   * The stop with the longest detour on the real route, if longer than {@link #MAX_DETOUR_M}: the legs to it and from
   * it are longer than planned.
   */
  @Nullable
  static String findDetourStop(@NonNull EvPlanResult.Plan planned, @NonNull EvPlanResult.Plan real)
  {
    final int n = planned.stops.size();
    if (real.stops.size() != n || n == 0)
      return null;
    // Extra length of each leg: to stop 0, ..., to stop n - 1, to the destination.
    final double[] extra = new double[n + 1];
    double plannedPrev = 0;
    double realPrev = 0;
    for (int i = 0; i <= n; ++i)
    {
      final double plannedPos = i < n ? planned.stops.get(i).distanceAlongRouteM : planned.distanceM;
      final double realPos = i < n ? real.stops.get(i).distanceAlongRouteM : real.distanceM;
      extra[i] = (realPos - realPrev) - (plannedPos - plannedPrev);
      plannedPrev = plannedPos;
      realPrev = realPos;
    }
    String worst = null;
    double worstDetour = MAX_DETOUR_M;
    for (int i = 0; i < n; ++i)
    {
      final double detour = extra[i] + extra[i + 1];
      if (detour > worstDetour)
      {
        worstDetour = detour;
        worst = planned.stops.get(i).key;
      }
    }
    return worst;
  }

  public void chooseAlternative(int index)
  {
    if (mResult == null || index < 0 || index >= mResult.alternatives.size())
      return;
    final EvPlanResult.Plan alternative = mResult.alternatives.get(index);
    mResult.alternatives.set(index, mResult.plan);
    mResult.plan = alternative;
    apply(alternative, ++mRequest);
  }

  public void showStop(int index)
  {
    if (mResult == null || index < 0 || index >= mResult.plan.stops.size())
      return;
    mSelectedStop = index;
    mScreen = Screen.DETAIL;
    render();
  }

  /** "Charge longer" at the selected stop. */
  public void chargeLonger()
  {
    final EvPlanResult.Stop stop = getSelectedStop();
    if (mResult == null || stop == null || stop.longer == null)
      return;
    stop.departureSocPct = stop.longer.departureSocPct;
    evaluate(mResult.plan);
    render();
  }

  /** "Choose another stop": plans again without the selected station. */
  public void chooseOtherStop()
  {
    final EvPlanResult.Stop stop = getSelectedStop();
    if (stop == null)
      return;
    if (!mOptions.excludedStations.contains(stop.key))
      mOptions.excludedStations.add(stop.key);
    plan();
  }

  /** "Keep as charging stop": back to the plan. */
  public void keepStop()
  {
    mScreen = Screen.RESULT;
    render();
  }

  public void showOptions()
  {
    mScreen = Screen.OPTIONS;
    if (mProviders.isEmpty())
      loadProviders();
    render();
  }

  public void startNavigation()
  {
    mRoute.startNavigation();
    close();
  }

  /** @return false if the planner should close. */
  public boolean back()
  {
    switch (mScreen)
    {
    case DETAIL:
      mScreen = Screen.RESULT;
      render();
      return true;
    case PLANNING:
      ++mRequest;
      mEngine.cancel();
      mScreen = Screen.OPTIONS;
      render();
      return true;
    case ERROR:
      mScreen = Screen.OPTIONS;
      render();
      return true;
    case OPTIONS:
      if (mResult != null && mRouteStops.getPlan() == mResult.plan)
      {
        mScreen = Screen.RESULT;
        render();
        return true;
      }
      return false;
    default: return false;
    }
  }

  public void close()
  {
    ++mRequest;
    mEngine.cancel();
    if (mView != null)
      mView.close();
  }

  /** The options for the core, also for tests. */
  @NonNull
  public String optionsJson()
  {
    return mOptions.toJson();
  }

  /** A first plan with the options to find the providers along the route, without changing the route. */
  private void loadProviders()
  {
    mProvidersLoading = true;
    final int request = ++mRequest;
    final EvPlanOptions options = mOptions.copy();
    options.preferredProviders.clear();
    options.excludedProviders.clear();
    mEngine.plan(mProfile.toJson(), options.toJson(), json -> {
      if (request != mRequest)
        return;
      mProvidersLoading = false;
      final EvPlanResult result = EvJson.parseResult(json);
      mProviders = result.providers;
      render();
    });
  }

  private void render()
  {
    if (mView != null)
      mView.render();
  }

  private static double clamp(int value, int min, int max)
  {
    return Math.max(min, Math.min(max, value));
  }

  private boolean isChosen(@NonNull String key)
  {
    return isPreferred(key) || isExcluded(key);
  }

  @NonNull
  private static List<String> concat(@NonNull List<String> a, @NonNull List<String> b)
  {
    final List<String> result = new ArrayList<>(a);
    result.addAll(b);
    return result;
  }
}
