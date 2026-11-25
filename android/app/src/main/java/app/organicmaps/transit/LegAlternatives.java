package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.transit.TransitItineraries;
import app.organicmaps.sdk.transit.TransitItinerary;
import app.organicmaps.sdk.transit.TransitLeg;
import app.organicmaps.sdk.transit.TransitLocation;
import app.organicmaps.sdk.transit.TransitPlace;
import app.organicmaps.sdk.transit.TransitRoutingOptions;
import app.organicmaps.sdk.transit.provider.TransitPlanRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Other departures for one ride (transit leg) of a connection, without Android dependencies.
 * <p>
 * An alternative for ride i keeps the rest of the connection: it must reach the stop where ride i ends early enough
 * for the transfer to the next ride (arrival + transfer walk &le; departure of the next ride). The first ride starts
 * at the start of the search (including the walk to the stop), so other stops and lines may appear; later rides
 * start at their boarding stop, not before the previous ride has arrived and the transfer is done. The last ride has
 * no next ride: its alternatives are earlier and later departures, judged by their arrival.
 * <p>
 * The alternatives are direct trips (no transfer) found with one routing request in a time window around the ride.
 * Choosing one builds a new itinerary from it and the unchanged legs ({@link #splice}); if it misses the next ride,
 * the rest is searched again from the transfer stop and joined ({@link #spliceWithRest}).
 */
public final class LegAlternatives
{
  /** How long before the ride the search window starts. */
  static final long EARLIER_SECONDS = 40 * 60;
  /** How long after the ride the search window ends. */
  static final long LATER_SECONDS = 30 * 60;
  /** The search window is at least this long (e.g. when the previous ride limits its start). */
  static final long MIN_WINDOW_SECONDS = 30 * 60;
  /** A buffer up to this is tight. */
  static final long TIGHT_SECONDS = 5 * 60;
  /** Alternatives that still reach the next ride. */
  static final int MAX_CONNECTING = 5;
  /** Later alternatives that miss the next ride, to show what happens then. */
  static final int MAX_MISSED = 2;
  /** Earlier and later departures around the last ride. */
  static final int MAX_AROUND_LAST = 3;
  /** Minimum number of results MOTIS should find (it widens the window for rare lines). */
  static final int NUM_ITINERARIES = 5;

  /** Where the ride is in its connection. */
  public enum Position
  {
    /** The first of several rides: alternatives start at the start of the search. */
    FIRST,
    /** Between two rides: alternatives from its boarding stop. */
    MIDDLE,
    /** The last of several rides: no next ride to reach. */
    LAST,
    /** The only ride of the connection. */
    ONLY;

    public boolean hasNext()
    {
      return this == FIRST || this == MIDDLE;
    }

    public boolean hasPrevious()
    {
      return this == MIDDLE || this == LAST;
    }
  }

  public enum Status
  {
    /** Reaches the next ride with more than {@link #TIGHT_SECONDS} to spare. */
    COMFORTABLE,
    /** Reaches the next ride with little time to spare. */
    TIGHT,
    /** Arrives too late for the next ride. */
    MISSED,
    /** The last ride: no next ride, the arrival counts. */
    ARRIVAL
  }

  private LegAlternatives() {}

  /** A ride of a connection with the neighbouring rides that limit its alternatives. */
  public static final class Ride
  {
    @NonNull
    private final TransitItinerary mItinerary;
    private final int mLegIndex;
    private final int mPrevious;
    private final int mNext;

    Ride(@NonNull TransitItinerary itinerary, int legIndex, int previous, int next)
    {
      mItinerary = itinerary;
      mLegIndex = legIndex;
      mPrevious = previous;
      mNext = next;
    }

    @NonNull
    public TransitItinerary getItinerary()
    {
      return mItinerary;
    }

    /** Index of the ride in {@link TransitItinerary#getLegs()}. */
    public int getLegIndex()
    {
      return mLegIndex;
    }

    @NonNull
    public TransitLeg getLeg()
    {
      return mItinerary.getLegs().get(mLegIndex);
    }

    @NonNull
    public Position getPosition()
    {
      if (mPrevious < 0)
        return mNext < 0 ? Position.ONLY : Position.FIRST;
      return mNext < 0 ? Position.LAST : Position.MIDDLE;
    }

    /** 1 for the first ride of the connection, 2 for the second etc. */
    public int getNumber()
    {
      int number = 0;
      for (int i = 0; i <= mLegIndex; ++i)
        if (mItinerary.getLegs().get(i).isTransit())
          ++number;
      return number;
    }

    /** The next ride, null for the last one. */
    @Nullable
    public TransitLeg getNextLeg()
    {
      return mNext < 0 ? null : mItinerary.getLegs().get(mNext);
    }

    /** Index of the next ride, -1 for the last one. */
    public int getNextIndex()
    {
      return mNext;
    }

    /** Walking time from this ride to the next one (0 if they stop at the same place or there is no next ride). */
    public long getTransferSeconds()
    {
      return mNext < 0 ? 0 : streetSeconds(mItinerary, mLegIndex + 1, mNext);
    }

    /** The latest arrival at the end of this ride that still reaches the next ride, null for the last ride. */
    @Nullable
    public Instant getLatestArrival()
    {
      final TransitLeg next = getNextLeg();
      return next == null ? null : next.getStartTime().minusSeconds(getTransferSeconds());
    }

    /** The earliest departure after the previous ride and the transfer, null for the first ride. */
    @Nullable
    public Instant getEarliestDeparture()
    {
      if (mPrevious < 0)
        return null;
      final TransitLeg previous = mItinerary.getLegs().get(mPrevious);
      return previous.getEndTime().plusSeconds(streetSeconds(mItinerary, mPrevious + 1, mLegIndex));
    }
  }

  /** An alternative departure for a ride. */
  public static final class Option
  {
    @NonNull
    private final List<TransitLeg> mLegs;
    @NonNull
    private final TransitLeg mRide;
    @NonNull
    private final Instant mArrival;
    @Nullable
    private final Long mBufferSeconds;
    @NonNull
    private final Status mStatus;
    private final boolean mCurrent;
    @NonNull
    private final String mKey;

    Option(@NonNull List<TransitLeg> legs, @NonNull TransitLeg ride, @NonNull Instant arrival,
           @Nullable Long bufferSeconds, boolean current)
    {
      mLegs = Collections.unmodifiableList(new ArrayList<>(legs));
      mRide = ride;
      mArrival = arrival;
      mBufferSeconds = bufferSeconds;
      mCurrent = current;
      mKey = key(ride);
      if (bufferSeconds == null)
        mStatus = Status.ARRIVAL;
      else if (bufferSeconds < 0)
        mStatus = Status.MISSED;
      else if (bufferSeconds <= TIGHT_SECONDS)
        mStatus = Status.TIGHT;
      else
        mStatus = Status.COMFORTABLE;
    }

    /** Legs that replace the ride: the ride, and for the first ride the walk to it from the start. */
    @NonNull
    public List<TransitLeg> getLegs()
    {
      return mLegs;
    }

    @NonNull
    public TransitLeg getRide()
    {
      return mRide;
    }

    /** Expected departure of the ride. */
    @NonNull
    public Instant getDeparture()
    {
      return mRide.getStartTime();
    }

    /** Start of the legs, i.e. the departure from the start for the first ride (including the walk). */
    @NonNull
    public Instant getStart()
    {
      return mLegs.get(0).getStartTime();
    }

    /** Arrival at the stop where the ride ends (where the transfer starts). */
    @NonNull
    public Instant getArrival()
    {
      return mArrival;
    }

    /** Time to spare for the transfer to the next ride (negative if it is missed), null for the last ride. */
    @Nullable
    public Long getBufferSeconds()
    {
      return mBufferSeconds;
    }

    @NonNull
    public Status getStatus()
    {
      return mStatus;
    }

    /** True if this is the ride of the connection as it was found. */
    public boolean isCurrent()
    {
      return mCurrent;
    }

    /** True if the next ride is not reached with it. */
    public boolean missesNext()
    {
      return mStatus == Status.MISSED;
    }

    /** Identifies the trip and departure, e.g. to recognise the chosen option among new ones. */
    @NonNull
    public String getKey()
    {
      return mKey;
    }

    @NonNull
    @Override
    public String toString()
    {
      return mKey + " " + mStatus + (mCurrent ? " (current)" : "");
    }
  }

  /**
   * @return the ride at {@code legIndex}.
   * @throws IllegalArgumentException if that leg is not a ride with public transport.
   */
  @NonNull
  public static Ride ride(@NonNull TransitItinerary itinerary, int legIndex)
  {
    final List<TransitLeg> legs = itinerary.getLegs();
    if (legIndex < 0 || legIndex >= legs.size() || !legs.get(legIndex).isTransit())
      throw new IllegalArgumentException("Leg " + legIndex + " is no ride: " + itinerary);
    int previous = -1;
    for (int i = legIndex - 1; i >= 0 && previous < 0; --i)
      if (legs.get(i).isTransit())
        previous = i;
    int next = -1;
    for (int i = legIndex + 1; i < legs.size() && next < 0; ++i)
      if (legs.get(i).isTransit())
        next = i;
    return new Ride(itinerary, legIndex, previous, next);
  }

  /** True if the leg is a ride with public transport that can be swapped. */
  public static boolean isRide(@NonNull TransitItinerary itinerary, int legIndex)
  {
    return legIndex >= 0 && legIndex < itinerary.getLegs().size() && itinerary.getLegs().get(legIndex).isTransit();
  }

  /**
   * The routing request for the alternatives: direct trips only ({@code maxTransfers=0}), departing in a window
   * around the ride, to the stop where it ends.
   *
   * @param origin start of the search, used for the first ride; if null, the place where the connection starts.
   * @param now no departures before this are searched.
   * @return null if a needed place has neither a stop id nor coordinates.
   */
  @Nullable
  public static TransitPlanRequest request(@NonNull Ride ride, @Nullable TransitLocation origin, @NonNull Instant now,
                                           @NonNull TransitRoutingOptions options)
  {
    final TransitLeg leg = ride.getLeg();
    final boolean fromStart = !ride.getPosition().hasPrevious();
    final TransitLocation from =
        fromStart ? (origin != null ? origin : location(ride.getItinerary().getLegs().get(0).getFrom()))
                  : location(leg.getFrom());
    final TransitLocation to = location(leg.getTo());
    if (from == null || to == null)
      return null;
    // The first ride: departures from the start (with the walk), i.e. around the start of the connection.
    final Instant reference = fromStart ? ride.getItinerary().getStartTime() : leg.getStartTime();
    Instant start = reference.minusSeconds(EARLIER_SECONDS);
    final Instant earliest = ride.getEarliestDeparture();
    if (earliest != null && earliest.isAfter(start))
      start = earliest;
    if (now.isAfter(start))
      start = now;
    Instant end = reference.plusSeconds(LATER_SECONDS);
    if (end.isBefore(start.plusSeconds(MIN_WINDOW_SECONDS)))
      end = start.plusSeconds(MIN_WINDOW_SECONDS);
    final int window = (int) Duration.between(start, end).getSeconds();
    final TransitRoutingOptions direct = options.toBuilder()
                                             .setMaxTransfers(0)
                                             .setNumItineraries(NUM_ITINERARIES)
                                             .setSearchWindowSeconds(window)
                                             .build();
    return new TransitPlanRequest(from, to, start, false, null, direct);
  }

  /**
   * The alternatives to show, in order of departure: the current ride and, if there is a next ride, up to
   * {@link #MAX_CONNECTING} that reach it (the latest ones, they leave the most time before) plus up to
   * {@link #MAX_MISSED} later ones that miss it; for the last ride up to {@link #MAX_AROUND_LAST} earlier and later ones.
   *
   * @param found the itineraries of the request from {@link #request}.
   */
  @NonNull
  public static List<Option> options(@NonNull Ride ride, @NonNull List<TransitItinerary> found)
  {
    final Option current = currentOption(ride);
    final List<Option> all = new ArrayList<>();
    all.add(current);
    final Set<String> keys = new HashSet<>();
    keys.add(current.getKey());
    final Instant earliest = ride.getEarliestDeparture();
    for (TransitItinerary itinerary : found)
    {
      final TransitLeg alternative = singleRide(itinerary);
      if (alternative == null || alternative.isCancelled())
        continue;
      if (isSameTrip(alternative, ride.getLeg()))
        continue;
      if (earliest != null && itinerary.getStartTime().isBefore(earliest))
        continue;
      final Option option = option(ride, itinerary.getLegs(), alternative, itinerary.getEndTime(), false);
      if (keys.add(option.getKey()))
        all.add(option);
    }
    Collections.sort(all, BY_DEPARTURE);
    return ride.getPosition().hasNext() ? selectConnecting(all, current) : selectAround(all, current);
  }

  /** The ride of the connection itself as an option (it may be late and miss the next ride). */
  @NonNull
  static Option currentOption(@NonNull Ride ride)
  {
    final List<TransitLeg> legs = ride.getItinerary().getLegs();
    final List<TransitLeg> own = ride.getPosition().hasPrevious()
                                   ? Collections.singletonList(ride.getLeg())
                                   : legs.subList(0, ride.getLegIndex() + 1);
    return option(ride, own, ride.getLeg(), ride.getLeg().getEndTime(), true);
  }

  @NonNull
  private static Option option(@NonNull Ride ride, @NonNull List<TransitLeg> legs, @NonNull TransitLeg alternative,
                               @NonNull Instant arrival, boolean current)
  {
    final Instant latest = ride.getLatestArrival();
    final Long buffer = latest == null ? null : Duration.between(arrival, latest).getSeconds();
    return new Option(legs, alternative, arrival, buffer, current);
  }

  @NonNull
  private static List<Option> selectConnecting(@NonNull List<Option> sorted, @NonNull Option current)
  {
    final List<Option> connecting = new ArrayList<>();
    final List<Option> missed = new ArrayList<>();
    for (Option option : sorted)
    {
      if (!option.missesNext())
        connecting.add(option);
      else if (option != current)
        missed.add(option);
    }
    final List<Option> result = new ArrayList<>();
    if (!connecting.isEmpty())
    {
      int from = Math.max(0, connecting.size() - MAX_CONNECTING);
      final int currentIndex = connecting.indexOf(current);
      if (currentIndex >= 0 && currentIndex < from)
        from = currentIndex;
      result.addAll(connecting.subList(from, Math.min(connecting.size(), from + MAX_CONNECTING)));
    }
    result.addAll(missed.subList(0, Math.min(MAX_MISSED, missed.size())));
    if (!result.contains(current))
      result.add(current);
    Collections.sort(result, BY_DEPARTURE);
    return Collections.unmodifiableList(result);
  }

  @NonNull
  private static List<Option> selectAround(@NonNull List<Option> sorted, @NonNull Option current)
  {
    final int index = sorted.indexOf(current);
    final int from = Math.max(0, index - MAX_AROUND_LAST);
    final int to = Math.min(sorted.size(), index + MAX_AROUND_LAST + 1);
    return Collections.unmodifiableList(new ArrayList<>(sorted.subList(from, to)));
  }

  /**
   * The connection with the option instead of the ride. Walks after the ride (to the next ride or to the
   * destination) start when the option arrives; the following rides stay as they are. Use {@link #spliceWithRest}
   * for an option that misses the next ride.
   *
   * @return the itinerary of the ride itself for the current option.
   */
  @NonNull
  public static TransitItinerary splice(@NonNull Ride ride, @NonNull Option option)
  {
    if (option.isCurrent())
      return ride.getItinerary();
    final List<TransitLeg> legs = prefix(ride);
    legs.addAll(option.getLegs());
    final List<TransitLeg> all = ride.getItinerary().getLegs();
    Instant time = option.getArrival();
    final int next = ride.getNextIndex();
    for (int i = ride.getLegIndex() + 1; i < all.size(); ++i)
    {
      final TransitLeg leg = all.get(i);
      if (next >= 0 && i >= next)
        legs.add(leg);
      else
      {
        final TransitLeg moved = TransitItineraries.startingAt(leg, time);
        legs.add(moved);
        time = moved.getEndTime();
      }
    }
    return TransitItineraries.of(id(ride, option), legs);
  }

  /**
   * The connection with the option instead of the ride, continued with {@code rest} (a new search from the stop
   * where the option arrives) instead of the original rides after it.
   */
  @NonNull
  public static TransitItinerary spliceWithRest(@NonNull Ride ride, @NonNull Option option,
                                                @NonNull TransitItinerary rest)
  {
    final List<TransitLeg> legs = prefix(ride);
    legs.addAll(option.getLegs());
    legs.addAll(rest.getLegs());
    return TransitItineraries.of(id(ride, option) + "|rest", legs);
  }

  /** Index of the option's ride in the itinerary made by {@link #splice} or {@link #spliceWithRest}. */
  public static int splicedLegIndex(@NonNull Ride ride, @NonNull Option option)
  {
    if (option.isCurrent())
      return ride.getLegIndex();
    return prefix(ride).size() + option.getLegs().indexOf(option.getRide());
  }

  /** Index of the first leg of the rest in the itinerary made by {@link #spliceWithRest}. */
  public static int restLegIndex(@NonNull Ride ride, @NonNull Option option)
  {
    return prefix(ride).size() + option.getLegs().size();
  }

  /**
   * The request for the rest of the connection after an option that misses the next ride: from the stop where the
   * option arrives to the destination, departing at its arrival, with the user's options.
   *
   * @param destination destination of the search; if null, the place where the connection ends.
   * @return null if a needed place has neither a stop id nor coordinates.
   */
  @Nullable
  public static TransitPlanRequest restRequest(@NonNull Ride ride, @NonNull Option option,
                                               @Nullable TransitLocation destination,
                                               @NonNull TransitRoutingOptions options)
  {
    final List<TransitLeg> optionLegs = option.getLegs();
    TransitLocation from = location(optionLegs.get(optionLegs.size() - 1).getTo());
    if (from == null)
      from = location(ride.getLeg().getTo());
    final List<TransitLeg> legs = ride.getItinerary().getLegs();
    final TransitLocation to = destination != null ? destination : location(legs.get(legs.size() - 1).getTo());
    if (from == null || to == null)
      return null;
    return new TransitPlanRequest(from, to, option.getArrival(), false, null, options);
  }

  /** The rest that arrives first among those that depart after {@code earliest}; null if there is none. */
  @Nullable
  public static TransitItinerary pickRest(@NonNull List<TransitItinerary> found, @NonNull Instant earliest)
  {
    TransitItinerary best = null;
    for (TransitItinerary itinerary : found)
    {
      if (itinerary.getLegs().isEmpty() || itinerary.isCancelled() || itinerary.getStartTime().isBefore(earliest))
        continue;
      if (best == null || itinerary.getEndTime().isBefore(best.getEndTime())
          || (itinerary.getEndTime().equals(best.getEndTime()) && itinerary.getTransfers() < best.getTransfers()))
        best = itinerary;
    }
    return best;
  }

  /** Identifies a trip and its departure. */
  @NonNull
  static String key(@NonNull TransitLeg ride)
  {
    final String trip = ride.getTripId() != null ? ride.getTripId() : String.valueOf(ride.getLineName());
    return trip + "@" + ride.getScheduledStartTime().getEpochSecond() + "@" + ride.getFrom().getName();
  }

  private static boolean isSameTrip(@NonNull TransitLeg a, @NonNull TransitLeg b)
  {
    if (a.getTripId() != null && b.getTripId() != null)
      return a.getTripId().equals(b.getTripId());
    return Objects.equals(a.getLineName(), b.getLineName())
     && a.getScheduledStartTime().equals(b.getScheduledStartTime());
  }

  /** The only ride of an itinerary, null if it has none or several. */
  @Nullable
  private static TransitLeg singleRide(@NonNull TransitItinerary itinerary)
  {
    TransitLeg ride = null;
    for (TransitLeg leg : itinerary.getLegs())
    {
      if (!leg.isTransit())
        continue;
      if (ride != null && !leg.isInterlineWithPreviousLeg())
        return null;
      if (ride == null)
        ride = leg;
    }
    return ride;
  }

  /** The legs before the ride that stay: none for the first ride (the option starts at the start). */
  @NonNull
  private static List<TransitLeg> prefix(@NonNull Ride ride)
  {
    if (!ride.getPosition().hasPrevious())
      return new ArrayList<>();
    return new ArrayList<>(ride.getItinerary().getLegs().subList(0, ride.getLegIndex()));
  }

  @NonNull
  private static String id(@NonNull Ride ride, @NonNull Option option)
  {
    final String base = ride.getItinerary().getId().isEmpty() ? TransitItemFactory.key(ride.getItinerary())
                                                               : ride.getItinerary().getId();
    return base + "|" + ride.getLegIndex() + ":" + option.getKey();
  }

  /** Total duration of the street legs in [from, to). */
  private static long streetSeconds(@NonNull TransitItinerary itinerary, int from, int to)
  {
    long seconds = 0;
    for (int i = from; i < to; ++i)
    {
      final TransitLeg leg = itinerary.getLegs().get(i);
      if (!leg.isTransit())
        seconds += Math.max(0, Duration.between(leg.getStartTime(), leg.getEndTime()).getSeconds());
    }
    return seconds;
  }

  /** The stop id of a place, or its coordinates; null if it has neither. */
  @Nullable
  static TransitLocation location(@NonNull TransitPlace place)
  {
    if (place.getStopId() != null && !place.getStopId().trim().isEmpty())
      return TransitLocation.fromStopId(place.getStopId());
    final double lat = place.getLat();
    final double lon = place.getLon();
    if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180)
      return null;
    return TransitLocation.fromCoordinates(lat, lon);
  }

  private static final Comparator<Option> BY_DEPARTURE = (a, b) -> {
    final int departure = a.getDeparture().compareTo(b.getDeparture());
    return departure != 0 ? departure : a.getArrival().compareTo(b.getArrival());
  };
}
