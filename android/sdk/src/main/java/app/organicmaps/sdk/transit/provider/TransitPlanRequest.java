package app.organicmaps.sdk.transit.provider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.transit.TransitLocation;
import app.organicmaps.sdk.transit.TransitRoutingOptions;
import java.time.Instant;
import java.util.Objects;

/** An immutable public transport routing query. Equal requests are answered from the cache. */
public final class TransitPlanRequest
{
  @NonNull
  private final TransitLocation mFrom;
  @NonNull
  private final TransitLocation mTo;
  @Nullable
  private final Instant mTime;
  private final boolean mArriveBy;
  @Nullable
  private final String mPageCursor;
  @NonNull
  private final TransitRoutingOptions mOptions;

  /**
   * @param time departure time, or arrival time if {@code arriveBy}; null for now.
   * @param pageCursor cursor of a previous result of the same provider, or null for the first page.
   */
  public TransitPlanRequest(@NonNull TransitLocation from, @NonNull TransitLocation to, @Nullable Instant time,
                            boolean arriveBy, @Nullable String pageCursor, @NonNull TransitRoutingOptions options)
  {
    mFrom = from;
    mTo = to;
    mTime = time;
    mArriveBy = arriveBy;
    mPageCursor = pageCursor;
    mOptions = options;
  }

  @NonNull
  public TransitLocation getFrom()
  {
    return mFrom;
  }

  @NonNull
  public TransitLocation getTo()
  {
    return mTo;
  }

  @Nullable
  public Instant getTime()
  {
    return mTime;
  }

  public boolean isArriveBy()
  {
    return mArriveBy;
  }

  @Nullable
  public String getPageCursor()
  {
    return mPageCursor;
  }

  @NonNull
  public TransitRoutingOptions getOptions()
  {
    return mOptions;
  }

  /** The same query for another page. */
  @NonNull
  public TransitPlanRequest withPageCursor(@Nullable String pageCursor)
  {
    return new TransitPlanRequest(mFrom, mTo, mTime, mArriveBy, pageCursor, mOptions);
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o)
      return true;
    if (!(o instanceof TransitPlanRequest))
      return false;
    final TransitPlanRequest that = (TransitPlanRequest) o;
    return mArriveBy == that.mArriveBy && mFrom.equals(that.mFrom) && mTo.equals(that.mTo)
 && Objects.equals(mTime, that.mTime) && Objects.equals(mPageCursor, that.mPageCursor)
 && mOptions.equals(that.mOptions);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(mFrom, mTo, mTime, mArriveBy, mPageCursor, mOptions);
  }

  @NonNull
  @Override
  public String toString()
  {
    return mFrom + " -> " + mTo + (mTime != null ? (mArriveBy ? " arrive " : " depart ") + mTime : " now")
  + (mPageCursor != null ? " page" : "");
  }
}
