package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/** The other departures of the open ride of a connection (one ride at a time). */
public final class AlternativesItem
{
  /** One alternative departure. */
  public static final class Chip
  {
    @NonNull
    final String mDeparture;
    final long mDepartureEpoch;
    @NonNull
    final LineBadge mBadge;
    @NonNull
    final LegAlternatives.Status mStatus;
    final long mBufferMinutes;
    @NonNull
    final String mArrival;
    final long mArrivalEpoch;
    @NonNull
    final String mBoarding;
    final boolean mSelected;
    final boolean mPending;
    final boolean mCurrent;

    Chip(@NonNull String departure, long departureEpoch, @NonNull LineBadge badge,
         @NonNull LegAlternatives.Status status, long bufferMinutes, @NonNull String arrival, long arrivalEpoch,
         @NonNull String boarding, boolean selected, boolean pending, boolean current)
    {
      mDeparture = departure;
      mDepartureEpoch = departureEpoch;
      mBadge = badge;
      mStatus = status;
      mBufferMinutes = bufferMinutes;
      mArrival = arrival;
      mArrivalEpoch = arrivalEpoch;
      mBoarding = boarding;
      mSelected = selected;
      mPending = pending;
      mCurrent = current;
    }

    /** Departure of the ride, e.g. "09:29". */
    @NonNull
    public String getDeparture()
    {
      return mDeparture;
    }

    /** Departure of the ride in epoch seconds, e.g. for the time grid. */
    public long getDepartureEpoch()
    {
      return mDepartureEpoch;
    }

    @NonNull
    public LineBadge getBadge()
    {
      return mBadge;
    }

    @NonNull
    public LegAlternatives.Status getStatus()
    {
      return mStatus;
    }

    /** Minutes to spare for the transfer (negative if missed); 0 for the last ride. */
    public long getBufferMinutes()
    {
      return mBufferMinutes;
    }

    /** Arrival at the end of the ride, e.g. "09:39". */
    @NonNull
    public String getArrival()
    {
      return mArrival;
    }

    /** Arrival at the end of the ride in epoch seconds, e.g. for the time grid. */
    public long getArrivalEpoch()
    {
      return mArrivalEpoch;
    }

    /** Stop where the ride starts. */
    @NonNull
    public String getBoarding()
    {
      return mBoarding;
    }

    /** True if the shown connection uses this departure. */
    public boolean isSelected()
    {
      return mSelected;
    }

    /** True while the rest of the connection after this departure is searched. */
    public boolean isPending()
    {
      return mPending;
    }

    /** True for the ride of the connection as it was found. */
    public boolean isCurrent()
    {
      return mCurrent;
    }

    public boolean missesNext()
    {
      return mStatus == LegAlternatives.Status.MISSED;
    }
  }

  final int mConnection;
  final int mLegIndex;
  @NonNull
  final LegAlternatives.Position mPosition;
  final int mRideNumber;
  @Nullable
  final String mNextLine;
  @Nullable
  final String mTransfer;
  final boolean mLoading;
  @Nullable
  final TransitError mError;
  @NonNull
  final List<Chip> mChips;
  @Nullable
  final TransitError mRestError;

  AlternativesItem(int connection, int legIndex, @NonNull LegAlternatives.Position position, int rideNumber,
                   @Nullable String nextLine, @Nullable String transfer, boolean loading, @Nullable TransitError error,
                   @NonNull List<Chip> chips, @Nullable TransitError restError)
  {
    mConnection = connection;
    mLegIndex = legIndex;
    mPosition = position;
    mRideNumber = rideNumber;
    mNextLine = nextLine;
    mTransfer = transfer;
    mLoading = loading;
    mError = error;
    mChips = Collections.unmodifiableList(chips);
    mRestError = restError;
  }

  /** Index of the connection in {@link ConnectionsState#getItems()}. */
  public int getConnection()
  {
    return mConnection;
  }

  /** Index of the ride in the legs of the shown connection ({@link LegItem#getIndex()}). */
  public int getLegIndex()
  {
    return mLegIndex;
  }

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

  /** Line of the next ride to reach, e.g. "ICE 1022"; null for the last ride. */
  @Nullable
  public String getNextLine()
  {
    return mNextLine;
  }

  /** Walk to the next ride, e.g. "7 min"; null if there is none. */
  @Nullable
  public String getTransfer()
  {
    return mTransfer;
  }

  public boolean isLoading()
  {
    return mLoading;
  }

  /** Error of the request for the alternatives. */
  @Nullable
  public TransitError getError()
  {
    return mError;
  }

  /** Departures in time order, including the current one; empty while loading or after an error. */
  @NonNull
  public List<Chip> getChips()
  {
    return mChips;
  }

  /** True if the search found nothing but the current ride. */
  public boolean isEmpty()
  {
    return !mLoading && mError == null && mChips.size() <= 1;
  }

  /** Error of the search for the rest of the connection after a departure that misses the next ride. */
  @Nullable
  public TransitError getRestError()
  {
    return mRestError;
  }

  /** True while the rest of the connection is searched. */
  public boolean isReplanning()
  {
    for (Chip chip : mChips)
      if (chip.isPending())
        return true;
    return false;
  }

  /** Number of departures that reach the next ride. */
  public int getConnectingCount()
  {
    int count = 0;
    for (Chip chip : mChips)
      if (chip.getStatus() == LegAlternatives.Status.COMFORTABLE || chip.getStatus() == LegAlternatives.Status.TIGHT)
        ++count;
    return count;
  }

  /** The departure the shown connection uses, null if unknown. */
  @Nullable
  public Chip getSelected()
  {
    for (Chip chip : mChips)
      if (chip.isSelected())
        return chip;
    return null;
  }
}
