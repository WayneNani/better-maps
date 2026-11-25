package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** How the user changed a found connection by choosing another departure for one ride. */
public final class ChangeItem
{
  @NonNull
  final LegAlternatives.Position mPosition;
  final int mRideNumber;
  final boolean mReplanned;
  @Nullable
  final String mMissedLine;
  @NonNull
  final String mArrival;
  final long mDelayMinutes;
  final int mRestLegIndex;

  ChangeItem(@NonNull LegAlternatives.Position position, int rideNumber, boolean replanned,
             @Nullable String missedLine, @NonNull String arrival, long delayMinutes, int restLegIndex)
  {
    mPosition = position;
    mRideNumber = rideNumber;
    mReplanned = replanned;
    mMissedLine = missedLine;
    mArrival = arrival;
    mDelayMinutes = delayMinutes;
    mRestLegIndex = restLegIndex;
  }

  /** Where the changed ride is in the connection, e.g. the first ride. */
  @NonNull
  public LegAlternatives.Position getPosition()
  {
    return mPosition;
  }

  /** 1 for the first ride of the connection, 2 for the second etc. */
  public int getRideNumber()
  {
    return mRideNumber;
  }

  /** True if the new ride misses the next one and the rest of the connection was searched again. */
  public boolean isReplanned()
  {
    return mReplanned;
  }

  /** Line that is not reached any more, e.g. "ICE 1022" (replanned only). */
  @Nullable
  public String getMissedLine()
  {
    return mMissedLine;
  }

  /** New arrival at the destination, e.g. "10:30". */
  @NonNull
  public String getArrival()
  {
    return mArrival;
  }

  /** Minutes the new arrival is later (negative: earlier) than the found connection's. */
  public long getDelayMinutes()
  {
    return mDelayMinutes;
  }

  /** Index of the first leg of the new rest in the connection, -1 if not replanned. */
  public int getRestLegIndex()
  {
    return mRestLegIndex;
  }
}
