package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/** Departures at a stop. */
public final class TransitDepartures
{
  @NonNull
  TransitPlace mStop = new TransitPlace();
  @NonNull
  List<TransitDeparture> mDepartures = Collections.emptyList();
  @Nullable
  String mPreviousPageCursor;
  @Nullable
  String mNextPageCursor;

  TransitDepartures() {}

  @NonNull
  public TransitPlace getStop()
  {
    return mStop;
  }

  @NonNull
  public List<TransitDeparture> getDepartures()
  {
    return mDepartures;
  }

  @Nullable
  public String getPreviousPageCursor()
  {
    return mPreviousPageCursor;
  }

  @Nullable
  public String getNextPageCursor()
  {
    return mNextPageCursor;
  }
}
