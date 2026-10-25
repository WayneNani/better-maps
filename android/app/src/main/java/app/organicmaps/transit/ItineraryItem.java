package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;

/** One connection in the list: times, duration, transfers and line badges, already formatted. */
public final class ItineraryItem
{
  @NonNull
  final String mKey;
  @NonNull
  final String mDeparture;
  @NonNull
  final TransitFormatter.Delay mDepartureDelay;
  @NonNull
  final String mArrival;
  @NonNull
  final TransitFormatter.Delay mArrivalDelay;
  final long mArrivalDayOffset;
  @NonNull
  final String mDuration;
  @NonNull
  final String mTransfers;
  @NonNull
  final List<LineBadge> mBadges;
  @Nullable
  final String mBoarding;
  final boolean mCancelled;
  final boolean mWalkOnly;
  final boolean mRealTime;

  ItineraryItem(@NonNull String key, @NonNull String departure, @NonNull TransitFormatter.Delay departureDelay,
                @NonNull String arrival, @NonNull TransitFormatter.Delay arrivalDelay, long arrivalDayOffset,
                @NonNull String duration, @NonNull String transfers, @NonNull List<LineBadge> badges,
                @Nullable String boarding, boolean cancelled, boolean walkOnly, boolean realTime)
  {
    mKey = key;
    mDeparture = departure;
    mDepartureDelay = departureDelay;
    mArrival = arrival;
    mArrivalDelay = arrivalDelay;
    mArrivalDayOffset = arrivalDayOffset;
    mDuration = duration;
    mTransfers = transfers;
    mBadges = badges;
    mBoarding = boarding;
    mCancelled = cancelled;
    mWalkOnly = walkOnly;
    mRealTime = realTime;
  }

  /** Stable identity of the connection (server id), for list diffing. */
  @NonNull
  public String getKey()
  {
    return mKey;
  }

  /** Expected departure at the start (including the walk to the first stop). */
  @NonNull
  public String getDeparture()
  {
    return mDeparture;
  }

  /** Delay of the first vehicle. */
  @NonNull
  public TransitFormatter.Delay getDepartureDelay()
  {
    return mDepartureDelay;
  }

  @NonNull
  public String getArrival()
  {
    return mArrival;
  }

  /** Delay of the last vehicle. */
  @NonNull
  public TransitFormatter.Delay getArrivalDelay()
  {
    return mArrivalDelay;
  }

  /** Days between departure and arrival (e.g. 1 after midnight), 0 on the same day. */
  public long getArrivalDayOffset()
  {
    return mArrivalDayOffset;
  }

  @NonNull
  public String getDuration()
  {
    return mDuration;
  }

  @NonNull
  public String getTransfers()
  {
    return mTransfers;
  }

  /** Lines in travel order (without walking legs). */
  @NonNull
  public List<LineBadge> getBadges()
  {
    return mBadges;
  }

  /** First stop with transit and its track, e.g. "S+U Berlin Hauptbahnhof · Platform 15". */
  @Nullable
  public String getBoarding()
  {
    return mBoarding;
  }

  /** True if a vehicle of this connection is cancelled, i.e. it is not possible as planned. */
  public boolean isCancelled()
  {
    return mCancelled;
  }

  /** True for a connection without public transport (walking the whole way). */
  public boolean isWalkOnly()
  {
    return mWalkOnly;
  }

  public boolean isRealTime()
  {
    return mRealTime;
  }
}
