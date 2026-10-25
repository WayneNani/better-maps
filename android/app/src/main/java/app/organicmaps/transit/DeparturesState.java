package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/** Immutable snapshot of the departures section of the place page. */
public final class DeparturesState
{
  public enum Status
  {
    /** Not a stop, or the user declined online public transport: the section is hidden. */
    HIDDEN,
    /** The user has not decided yet: offer to enable online departures (opens the opt-in dialog). */
    CONSENT_REQUIRED,
    LOADING,
    CONTENT,
    /** The stop was found, but there are no departures soon. */
    EMPTY,
    ERROR
  }

  public static final DeparturesState HIDDEN =
      new DeparturesState(Status.HIDDEN, null, Collections.emptyList(), null, false, null, null, null);

  @NonNull
  final Status mStatus;
  @Nullable
  final String mStopName;
  @NonNull
  final List<DepartureItem> mItems;
  @Nullable
  final TransitError mError;
  final boolean mRefreshing;
  @Nullable
  final String mUpdatedAt;
  @Nullable
  final String mProviderName;
  @Nullable
  final String mAttributionUrl;

  DeparturesState(@NonNull Status status, @Nullable String stopName, @NonNull List<DepartureItem> items,
                  @Nullable TransitError error, boolean refreshing, @Nullable String updatedAt,
                  @Nullable String providerName, @Nullable String attributionUrl)
  {
    mStatus = status;
    mStopName = stopName;
    mItems = Collections.unmodifiableList(items);
    mError = error;
    mRefreshing = refreshing;
    mUpdatedAt = updatedAt;
    mProviderName = providerName;
    mAttributionUrl = attributionUrl;
  }

  @NonNull
  public Status getStatus()
  {
    return mStatus;
  }

  /** Name of the stop the departures belong to (may differ from the selected map object). */
  @Nullable
  public String getStopName()
  {
    return mStopName;
  }

  @NonNull
  public List<DepartureItem> getItems()
  {
    return mItems;
  }

  @Nullable
  public TransitError getError()
  {
    return mError;
  }

  /** A refresh is running while the previous content is still shown. */
  public boolean isRefreshing()
  {
    return mRefreshing;
  }

  /** Time of the shown data, e.g. "14:05". */
  @Nullable
  public String getUpdatedAt()
  {
    return mUpdatedAt;
  }

  @Nullable
  public String getProviderName()
  {
    return mProviderName;
  }

  @Nullable
  public String getAttributionUrl()
  {
    return mAttributionUrl;
  }
}
