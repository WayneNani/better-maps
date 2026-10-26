package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The next saved connection for the card on the map, without Android dependencies: which one it is and when it
 * departs ("in 25 min", "now", "on the way").
 * <p>
 * A connection is upcoming until it arrives: before its departure and while the user is on the way. Past ones and
 * those the user has hidden on the map are left out.
 */
public final class UpcomingConnection
{
  /** Longer before the departure, the date is shown instead of the time left. */
  static final Duration RELATIVE_LIMIT = Duration.ofHours(24);

  @NonNull
  private final SavedConnection mSaved;
  @NonNull
  private final ItineraryItem mItem;
  @NonNull
  private final String mWhen;
  private final boolean mOnTheWay;

  private UpcomingConnection(@NonNull SavedConnection saved, @NonNull ItineraryItem item, @NonNull String when,
                             boolean onTheWay)
  {
    mSaved = saved;
    mItem = item;
    mWhen = when;
    mOnTheWay = onTheWay;
  }

  /**
   * @param hidden keys of connections the user hid on the map.
   * @return the upcoming connection that departs first (one on the way comes first), or null if there is none.
   */
  @Nullable
  public static SavedConnection next(@NonNull List<SavedConnection> saved, @NonNull Instant now,
                                     @NonNull Collection<String> hidden)
  {
    SavedConnection next = null;
    for (SavedConnection connection : saved)
    {
      if (!isUpcoming(connection, now) || hidden.contains(connection.getKey()))
        continue;
      if (next == null || connection.getItinerary().getStartTime().isBefore(next.getItinerary().getStartTime()))
        next = connection;
    }
    return next;
  }

  /** True before the arrival of the connection. */
  public static boolean isUpcoming(@NonNull SavedConnection saved, @NonNull Instant now)
  {
    return now.isBefore(saved.getItinerary().getEndTime());
  }

  /** The card for the next connection, or null if there is none. */
  @Nullable
  public static UpcomingConnection find(@NonNull List<SavedConnection> saved, @NonNull Instant now,
                                        @NonNull Collection<String> hidden, @NonNull TransitItemFactory items)
  {
    final SavedConnection next = next(saved, now, hidden);
    if (next == null)
      return null;
    final ItineraryItem item = items.itinerary(next.getItinerary());
    final Instant start = next.getItinerary().getStartTime();
    final TransitFormatter formatter = items.getFormatter();
    final TransitTexts texts = formatter.getTexts();
    if (!now.isBefore(start))
      return new UpcomingConnection(next, item, texts.onTheWay(), true);
    final long seconds = Duration.between(now, start).getSeconds();
    // Rounded up: "in 1 min" until the departure, never "in 0 min".
    final long minutes = (seconds + 59) / 60;
    final String when;
    if (minutes == 0)
      when = texts.departingNow();
    else if (Duration.ofMinutes(minutes).compareTo(RELATIVE_LIMIT) < 0)
      when = texts.inTime(formatter.formatDuration(minutes * 60));
    else
      when = formatter.formatDate(start, zoneOf(next));
    return new UpcomingConnection(next, item, when, false);
  }

  /** Time until the text of the card changes next (at most a minute), for refreshing it. */
  @NonNull
  public static Duration untilNextChange(@NonNull SavedConnection saved, @NonNull Instant now)
  {
    final Instant start = saved.getItinerary().getStartTime();
    final Instant end = saved.getItinerary().getEndTime();
    final Duration minute = Duration.ofMinutes(1);
    if (!now.isBefore(end))
      return Duration.ZERO;
    if (!now.isBefore(start))
    {
      final Duration left = Duration.between(now, end);
      return left.compareTo(minute) < 0 ? left : minute;
    }
    // "in N min" changes whenever a whole number of minutes is left until the departure.
    final long millis = Duration.between(now, start).toMillis() % minute.toMillis();
    return millis == 0 ? minute : Duration.ofMillis(millis);
  }

  @Nullable
  private static String zoneOf(@NonNull SavedConnection saved)
  {
    return saved.getItinerary().getLegs().isEmpty() ? null
                                                    : saved.getItinerary().getLegs().get(0).getFrom().getTimeZone();
  }

  @NonNull
  public SavedConnection getSaved()
  {
    return mSaved;
  }

  /** Times and lines of the connection. */
  @NonNull
  public ItineraryItem getItem()
  {
    return mItem;
  }

  /** E.g. "in 25 min", "now", "on the way" or a date further ahead. */
  @NonNull
  public String getWhen()
  {
    return mWhen;
  }

  public boolean isOnTheWay()
  {
    return mOnTheWay;
  }
}
