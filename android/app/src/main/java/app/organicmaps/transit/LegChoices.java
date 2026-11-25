package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLeg;
import app.organicmaps.sdk.transit.TransitLocation;
import app.organicmaps.sdk.transit.TransitPlan;
import app.organicmaps.sdk.transit.provider.TransitPlanRequest;
import app.organicmaps.sdk.transit.provider.TransitProviderRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Alternative departures for single rides of the found connections and the user's choices, without Android
 * dependencies. Used by {@link TransitConnectionsPresenter}; all methods must be called on one thread (the main
 * thread), provider results are expected on it.
 * <p>
 * At most one ride is "open" (its alternatives are shown). Opening a ride sends one request (nothing is requested
 * before), unless its alternatives are cached. Choosing an alternative replaces the connection by a changed one
 * ({@link #current}); the found connection is kept for {@link #undo}. Results that arrive after the ride was closed,
 * another one was opened or everything was reset are dropped.
 */
public final class LegChoices
{
  /** Cached alternatives (connection and ride); the oldest are dropped first. */
  static final int MAX_CACHE_ENTRIES = 24;

  public interface Host
  {
    /** Id of the provider that found the connections (stop ids are only valid for it), null for any. */
    @Nullable
    String providerId();

    /** Start of the search, null if unknown. */
    @Nullable
    TransitLocation origin();

    /** Destination of the search, null if unknown. */
    @Nullable
    TransitLocation destination();

    /** The shown alternatives or the connection {@code key} (of the found connection) changed. */
    void onChanged(@NonNull String key);
  }

  /** The user's change of a found connection. */
  public static final class Change
  {
    @NonNull
    final TransitItinerary mOriginal;
    @NonNull
    final LegAlternatives.Ride mRide;
    @NonNull
    final LegAlternatives.Option mOption;
    @NonNull
    final TransitItinerary mCurrent;
    @Nullable
    final Change mPrevious;
    // The rest after the ride was searched again because the option misses the next ride.
    final boolean mReplanned;

    Change(@NonNull TransitItinerary original, @NonNull LegAlternatives.Ride ride,
           @NonNull LegAlternatives.Option option, @NonNull TransitItinerary current, @Nullable Change previous,
           boolean replanned)
    {
      mOriginal = original;
      mRide = ride;
      mOption = option;
      mCurrent = current;
      mPrevious = previous;
      mReplanned = replanned;
    }

    /** The connection as it was found. */
    @NonNull
    public TransitItinerary getOriginal()
    {
      return mOriginal;
    }

    /** The changed ride, in the connection before this change. */
    @NonNull
    public LegAlternatives.Ride getRide()
    {
      return mRide;
    }

    @NonNull
    public LegAlternatives.Option getOption()
    {
      return mOption;
    }

    /** The changed connection. */
    @NonNull
    public TransitItinerary getCurrent()
    {
      return mCurrent;
    }

    /** Index of the chosen ride in {@link #getCurrent()}. */
    public int getLegIndex()
    {
      return LegAlternatives.splicedLegIndex(mRide, mOption);
    }

    /** True if the chosen ride misses the next one and the rest was searched again. */
    public boolean isReplanned()
    {
      return mReplanned;
    }

    /** Index of the first leg of the new rest in {@link #getCurrent()}, -1 if not replanned. */
    public int getRestLegIndex()
    {
      return mReplanned ? LegAlternatives.restLegIndex(mRide, mOption) : -1;
    }

    /** The ride that is not reached any more (replanned only). */
    @Nullable
    public TransitLeg getMissedLeg()
    {
      return mReplanned ? mRide.getNextLeg() : null;
    }
  }

  /** The open ride and its alternatives. */
  public static final class Session
  {
    @NonNull
    final String mKey;
    @NonNull
    final LegAlternatives.Ride mRide;
    final int mGeneration;
    boolean mLoading;
    @Nullable
    TransitError mError;
    @NonNull
    List<LegAlternatives.Option> mOptions = new ArrayList<>();
    // The option whose rest is being searched.
    @Nullable
    String mPendingKey;
    @Nullable
    TransitError mRestError;
    int mRestGeneration;

    Session(@NonNull String key, @NonNull LegAlternatives.Ride ride, int generation)
    {
      mKey = key;
      mRide = ride;
      mGeneration = generation;
    }

    /** Key of the found connection. */
    @NonNull
    public String getKey()
    {
      return mKey;
    }

    /** The ride whose alternatives are shown, in the connection they were searched for. */
    @NonNull
    public LegAlternatives.Ride getRide()
    {
      return mRide;
    }

    public boolean isLoading()
    {
      return mLoading;
    }

    @Nullable
    public TransitError getError()
    {
      return mError;
    }

    @NonNull
    public List<LegAlternatives.Option> getOptions()
    {
      return mOptions;
    }

    /** Key of the option whose rest is being searched, null if none. */
    @Nullable
    public String getPendingKey()
    {
      return mPendingKey;
    }

    /** Error of the search for the rest. */
    @Nullable
    public TransitError getRestError()
    {
      return mRestError;
    }
  }

  @NonNull
  private final TransitProviderRegistry mRegistry;
  @NonNull
  private final TransitConnectionsPresenter.OptionsSource mOptions;
  @NonNull
  private final TransitConnectionsPresenter.Clock mClock;
  @NonNull
  private final Host mHost;

  // Key of the found connection -> its change.
  private final Map<String, Change> mChanges = new HashMap<>();
  private final LinkedHashMap<String, List<LegAlternatives.Option>> mCache = new LinkedHashMap<>(16, 0.75f, true);
  @Nullable
  private Session mSession;
  private int mGeneration;
  private int mRequestCount;

  public LegChoices(@NonNull TransitProviderRegistry registry, @NonNull TransitConnectionsPresenter.OptionsSource options,
                    @NonNull TransitConnectionsPresenter.Clock clock, @NonNull Host host)
  {
    mRegistry = registry;
    mOptions = options;
    mClock = clock;
    mHost = host;
  }

  /** The connection as shown: changed by the user, or as found. */
  @NonNull
  public TransitItinerary current(@NonNull TransitItinerary original)
  {
    final Change change = mChanges.get(TransitItemFactory.key(original));
    return change != null ? change.mCurrent : original;
  }

  /** The user's change of a found connection, null if unchanged. */
  @Nullable
  public Change getChange(@NonNull TransitItinerary original)
  {
    return mChanges.get(TransitItemFactory.key(original));
  }

  @Nullable
  public Session getSession()
  {
    return mSession;
  }

  /** Number of requests sent (alternatives and rests), for tests. */
  int getRequestCount()
  {
    return mRequestCount;
  }

  /**
   * Index of the open ride in the shown connection {@code original}, -1 if none of its rides is open.
   */
  public int openLegIndex(@NonNull TransitItinerary original)
  {
    final Session session = mSession;
    if (session == null || !session.mKey.equals(TransitItemFactory.key(original)))
      return -1;
    final Change change = mChanges.get(session.mKey);
    if (change != null && change.mRide.getItinerary() == session.mRide.getItinerary()
        && change.mRide.getLegIndex() == session.mRide.getLegIndex())
      return change.getLegIndex();
    return session.mRide.getLegIndex();
  }

  /** The option of the open ride that the shown connection uses, null if none. */
  @Nullable
  public LegAlternatives.Option selectedOption()
  {
    final Session session = mSession;
    if (session == null)
      return null;
    final Change change = mChanges.get(session.mKey);
    String key = null;
    if (change != null && change.mRide.getItinerary() == session.mRide.getItinerary()
        && change.mRide.getLegIndex() == session.mRide.getLegIndex())
      key = change.mOption.getKey();
    for (LegAlternatives.Option option : session.mOptions)
      if (key != null ? option.getKey().equals(key) : option.isCurrent())
        return option;
    return null;
  }

  /**
   * Opens the alternatives of a ride, or closes them if they are open.
   *
   * @param original the found connection.
   * @param legIndex index of the ride in the shown connection ({@link #current}).
   */
  public void toggle(@NonNull TransitItinerary original, int legIndex)
  {
    final String key = TransitItemFactory.key(original);
    if (mSession != null && openLegIndex(original) == legIndex)
    {
      close(true);
      return;
    }
    final TransitItinerary shown = current(original);
    if (!LegAlternatives.isRide(shown, legIndex))
      return;
    final Change change = mChanges.get(key);
    // The changed ride again: its alternatives were searched in the connection before the change.
    final LegAlternatives.Ride ride = change != null && change.getLegIndex() == legIndex
                                        ? change.mRide
                                        : LegAlternatives.ride(shown, legIndex);
    final Session session = new Session(key, ride, ++mGeneration);
    mSession = session;
    final String cacheKey = cacheKey(ride);
    final List<LegAlternatives.Option> cached = mCache.get(cacheKey);
    if (cached != null)
    {
      session.mOptions = cached;
      mHost.onChanged(key);
      return;
    }
    final TransitPlanRequest request =
        LegAlternatives.request(ride, mHost.origin(), mClock.now(), mOptions.getOptions());
    if (request == null)
    {
      session.mError = TransitError.NO_RESULTS;
      mHost.onChanged(key);
      return;
    }
    session.mLoading = true;
    mHost.onChanged(key);
    ++mRequestCount;
    mRegistry.plan(request, mHost.providerId())
        .onSuccess(result -> {
          if (mSession != session || session.mGeneration != mGeneration)
            return;
          final TransitPlan plan = result.getValue();
          session.mLoading = false;
          session.mOptions = LegAlternatives.options(ride, plan.getItineraries());
          mCache.put(cacheKey, session.mOptions);
          while (mCache.size() > MAX_CACHE_ENTRIES)
            mCache.remove(mCache.keySet().iterator().next());
          mHost.onChanged(key);
        })
        .onFailure(error -> {
          if (mSession != session || session.mGeneration != mGeneration)
            return;
          session.mLoading = false;
          session.mError = TransitError.from(error);
          mHost.onChanged(key);
        });
  }

  /**
   * Uses an alternative of the open ride. If it misses the next ride, the rest of the connection is searched again
   * from the transfer stop first; the change is made when it is found.
   */
  public void choose(int optionIndex)
  {
    final Session session = mSession;
    if (session == null || optionIndex < 0 || optionIndex >= session.mOptions.size())
      return;
    final LegAlternatives.Option option = session.mOptions.get(optionIndex);
    final LegAlternatives.Ride ride = session.mRide;
    // A running search for the rest of another option is ignored.
    final int restGeneration = ++session.mRestGeneration;
    session.mPendingKey = null;
    session.mRestError = null;
    if (!option.missesNext())
    {
      apply(session.mKey, ride, option, LegAlternatives.splice(ride, option), false);
      return;
    }
    final TransitPlanRequest request =
        LegAlternatives.restRequest(ride, option, mHost.destination(), mOptions.getOptions());
    if (request == null)
    {
      session.mRestError = TransitError.NO_RESULTS;
      mHost.onChanged(session.mKey);
      return;
    }
    session.mPendingKey = option.getKey();
    mHost.onChanged(session.mKey);
    ++mRequestCount;
    mRegistry.plan(request, mHost.providerId())
        .onSuccess(result -> {
          if (mSession != session || session.mRestGeneration != restGeneration)
            return;
          session.mPendingKey = null;
          final List<TransitItinerary> found = new ArrayList<>(result.getValue().getItineraries());
          found.addAll(result.getValue().getDirect());
          final TransitItinerary rest = LegAlternatives.pickRest(found, option.getArrival());
          if (rest == null)
          {
            session.mRestError = TransitError.NO_RESULTS;
            mHost.onChanged(session.mKey);
            return;
          }
          apply(session.mKey, ride, option, LegAlternatives.spliceWithRest(ride, option, rest), true);
        })
        .onFailure(error -> {
          if (mSession != session || session.mRestGeneration != restGeneration)
            return;
          session.mPendingKey = null;
          session.mRestError = TransitError.from(error);
          mHost.onChanged(session.mKey);
        });
  }

  private void apply(@NonNull String key, @NonNull LegAlternatives.Ride ride, @NonNull LegAlternatives.Option option,
                     @NonNull TransitItinerary current, boolean replanned)
  {
    final Change change = mChanges.get(key);
    // The ride of the change was opened again: replace the change, not build on it.
    final boolean sameRide = change != null && change.mRide.getItinerary() == ride.getItinerary()
                          && change.mRide.getLegIndex() == ride.getLegIndex();
    final Change previous = sameRide ? change.mPrevious : change;
    final TransitItinerary original = change != null ? change.mOriginal : ride.getItinerary();
    if (option.isCurrent())
    {
      // Back to the connection before this change.
      if (previous == null)
        mChanges.remove(key);
      else
        mChanges.put(key, previous);
    }
    else
      mChanges.put(key, new Change(original, ride, option, current, previous, replanned));
    mHost.onChanged(key);
  }

  /** Back to the connection as it was found. The alternatives stay open if they belong to it. */
  public void undo(@NonNull TransitItinerary original)
  {
    final String key = TransitItemFactory.key(original);
    final Change change = mChanges.remove(key);
    if (change == null)
      return;
    if (mSession != null && mSession.mKey.equals(key))
    {
      if (mSession.mRide.getItinerary() == change.mOriginal)
      {
        ++mSession.mRestGeneration;
        mSession.mPendingKey = null;
        mSession.mRestError = null;
      }
      else
      {
        mSession = null;
        ++mGeneration;
      }
    }
    mHost.onChanged(key);
  }

  /**
   * Closes the open ride; a running request is ignored.
   *
   * @param notify false if the caller shows the new state itself.
   */
  public void close(boolean notify)
  {
    if (mSession == null)
      return;
    final String key = mSession.mKey;
    mSession = null;
    ++mGeneration;
    if (notify)
      mHost.onChanged(key);
  }

  /** Forgets everything, e.g. for a new search. No notification. */
  public void reset()
  {
    mSession = null;
    ++mGeneration;
    mChanges.clear();
    mCache.clear();
  }

  @NonNull
  private static String cacheKey(@NonNull LegAlternatives.Ride ride)
  {
    return TransitItemFactory.key(ride.getItinerary()) + "#" + ride.getLegIndex();
  }
}
