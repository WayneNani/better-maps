package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** One departure at a stop, already formatted. */
public final class DepartureItem
{
  @NonNull
  final LineBadge mBadge;
  @Nullable
  final String mDirection;
  @NonNull
  final String mTime;
  @NonNull
  final TransitFormatter.Delay mDelay;
  @Nullable
  final String mTrack;
  final boolean mTrackChanged;
  final boolean mCancelled;

  DepartureItem(@NonNull LineBadge badge, @Nullable String direction, @NonNull String time,
                @NonNull TransitFormatter.Delay delay, @Nullable String track, boolean trackChanged, boolean cancelled)
  {
    mBadge = badge;
    mDirection = direction;
    mTime = time;
    mDelay = delay;
    mTrack = track;
    mTrackChanged = trackChanged;
    mCancelled = cancelled;
  }

  @NonNull
  public LineBadge getBadge()
  {
    return mBadge;
  }

  @Nullable
  public String getDirection()
  {
    return mDirection;
  }

  /** Expected departure time (realtime if available). */
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

  @Nullable
  public String getTrack()
  {
    return mTrack;
  }

  public boolean isTrackChanged()
  {
    return mTrackChanged;
  }

  public boolean isCancelled()
  {
    return mCancelled;
  }
}
