package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/** Result of a public transport routing query. */
public final class TransitPlan
{
  @NonNull
  TransitPlace mFrom = new TransitPlace();
  @NonNull
  TransitPlace mTo = new TransitPlace();
  @NonNull
  List<TransitItinerary> mItineraries = Collections.emptyList();
  @NonNull
  List<TransitItinerary> mDirect = Collections.emptyList();
  @Nullable
  String mPreviousPageCursor;
  @Nullable
  String mNextPageCursor;

  TransitPlan() {}

  @NonNull
  public TransitPlace getFrom()
  {
    return mFrom;
  }

  @NonNull
  public TransitPlace getTo()
  {
    return mTo;
  }

  /** Itineraries with public transport, sorted by departure. */
  @NonNull
  public List<TransitItinerary> getItineraries()
  {
    return mItineraries;
  }

  /** Alternatives without public transport (e.g. walking the whole way), if the server returned any. */
  @NonNull
  public List<TransitItinerary> getDirect()
  {
    return mDirect;
  }

  /** True if neither transit nor direct itineraries were found. Paging may still find some. */
  public boolean isEmpty()
  {
    return mItineraries.isEmpty() && mDirect.isEmpty();
  }

  /** Cursor for earlier connections: pass it with the otherwise unchanged query. */
  @Nullable
  public String getPreviousPageCursor()
  {
    return mPreviousPageCursor;
  }

  /** Cursor for later connections: pass it with the otherwise unchanged query. */
  @Nullable
  public String getNextPageCursor()
  {
    return mNextPageCursor;
  }
}
