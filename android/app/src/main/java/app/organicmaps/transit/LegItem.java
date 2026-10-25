package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;

/** One leg in the detail view of a connection. */
public final class LegItem
{
  public enum Type
  {
    TRANSIT,
    WALK,
    /** Other street modes, e.g. bike or car. */
    OTHER
  }

  /** An intermediate stop of a transit leg. */
  public static final class Stop
  {
    @NonNull
    final String mName;
    @NonNull
    final String mTime;
    @NonNull
    final TransitFormatter.Delay mDelay;
    final boolean mCancelled;

    Stop(@NonNull String name, @NonNull String time, @NonNull TransitFormatter.Delay delay, boolean cancelled)
    {
      mName = name;
      mTime = time;
      mDelay = delay;
      mCancelled = cancelled;
    }

    @NonNull
    public String getName()
    {
      return mName;
    }

    @NonNull
    public String getTime()
    {
      return mTime;
    }

    @NonNull
    public TransitFormatter.Delay getDelay()
    {
      return mDelay;
    }

    public boolean isCancelled()
    {
      return mCancelled;
    }
  }

  final int mIndex;
  @NonNull
  final Type mType;
  @Nullable
  final LineBadge mBadge;
  @Nullable
  final String mDirection;
  @NonNull
  final String mFromName;
  @NonNull
  final String mToName;
  @NonNull
  final String mDeparture;
  @NonNull
  final TransitFormatter.Delay mDepartureDelay;
  @NonNull
  final String mArrival;
  @NonNull
  final TransitFormatter.Delay mArrivalDelay;
  @Nullable
  final String mFromTrack;
  final boolean mFromTrackChanged;
  @Nullable
  final String mToTrack;
  final boolean mToTrackChanged;
  @NonNull
  final String mSummary;
  @NonNull
  final List<Stop> mStops;
  final boolean mExpanded;
  final boolean mCancelled;
  @NonNull
  final List<String> mAlerts;
  @Nullable
  final String mAgency;

  LegItem(int index, @NonNull Type type, @Nullable LineBadge badge, @Nullable String direction,
          @NonNull String fromName, @NonNull String toName, @NonNull String departure,
          @NonNull TransitFormatter.Delay departureDelay, @NonNull String arrival,
          @NonNull TransitFormatter.Delay arrivalDelay, @Nullable String fromTrack, boolean fromTrackChanged,
          @Nullable String toTrack, boolean toTrackChanged, @NonNull String summary, @NonNull List<Stop> stops,
          boolean expanded, boolean cancelled, @NonNull List<String> alerts, @Nullable String agency)
  {
    mIndex = index;
    mType = type;
    mBadge = badge;
    mDirection = direction;
    mFromName = fromName;
    mToName = toName;
    mDeparture = departure;
    mDepartureDelay = departureDelay;
    mArrival = arrival;
    mArrivalDelay = arrivalDelay;
    mFromTrack = fromTrack;
    mFromTrackChanged = fromTrackChanged;
    mToTrack = toTrack;
    mToTrackChanged = toTrackChanged;
    mSummary = summary;
    mStops = stops;
    mExpanded = expanded;
    mCancelled = cancelled;
    mAlerts = alerts;
    mAgency = agency;
  }

  /** Index of the leg in the itinerary; used to expand its stops. */
  public int getIndex()
  {
    return mIndex;
  }

  @NonNull
  public Type getType()
  {
    return mType;
  }

  @Nullable
  public LineBadge getBadge()
  {
    return mBadge;
  }

  @Nullable
  public String getDirection()
  {
    return mDirection;
  }

  @NonNull
  public String getFromName()
  {
    return mFromName;
  }

  @NonNull
  public String getToName()
  {
    return mToName;
  }

  @NonNull
  public String getDeparture()
  {
    return mDeparture;
  }

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

  @NonNull
  public TransitFormatter.Delay getArrivalDelay()
  {
    return mArrivalDelay;
  }

  /** E.g. "Platform 15", null if unknown. */
  @Nullable
  public String getFromTrack()
  {
    return mFromTrack;
  }

  public boolean isFromTrackChanged()
  {
    return mFromTrackChanged;
  }

  @Nullable
  public String getToTrack()
  {
    return mToTrack;
  }

  public boolean isToTrackChanged()
  {
    return mToTrackChanged;
  }

  /** Transit: "12 min · 3 stops"; walk: "Walk 5 min (350 m)". */
  @NonNull
  public String getSummary()
  {
    return mSummary;
  }

  /** Intermediate stops; empty for walking legs. */
  @NonNull
  public List<Stop> getStops()
  {
    return mStops;
  }

  public boolean hasStops()
  {
    return !mStops.isEmpty();
  }

  /** True if the intermediate stops are shown. */
  public boolean isExpanded()
  {
    return mExpanded;
  }

  public boolean isCancelled()
  {
    return mCancelled;
  }

  @NonNull
  public List<String> getAlerts()
  {
    return mAlerts;
  }

  @Nullable
  public String getAgency()
  {
    return mAgency;
  }
}
