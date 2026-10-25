package app.organicmaps.search.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.search.online.OnlineSearchException;
import java.util.Collections;
import java.util.List;

/** What the online section of the search list shows. Immutable. */
public final class OnlineSearchState
{
  public enum Status
  {
    /** No online section (not needed, query too short, or declined). */
    HIDDEN,
    /** Online results would help, but the user has not decided yet: offer to enable the online search. */
    OFFER,
    LOADING,
    RESULTS,
    /** Searched online, but nothing (new) was found. */
    EMPTY,
    ERROR
  }

  public enum Error
  {
    NETWORK,
    SERVER,
    /** The server rejected the request (HTTP 4xx). */
    REQUEST,
    /** Online search switched off: no request was sent. */
    DISABLED,
    UNKNOWN;

    @NonNull
    public static Error of(@NonNull Throwable error)
    {
      if (!(error instanceof OnlineSearchException))
        return UNKNOWN;
      final OnlineSearchException e = (OnlineSearchException) error;
      if (e.getHttpCode() == 429)
        return SERVER;
      return switch (e.getKind())
      {
        case NETWORK -> NETWORK;
        case SERVER_ERROR, INVALID_RESPONSE -> SERVER;
        case CLIENT_ERROR -> REQUEST;
        case DISABLED -> DISABLED;
      };
    }
  }

  public static final OnlineSearchState HIDDEN =
      new OnlineSearchState(Status.HIDDEN, Collections.emptyList(), null, "");

  @NonNull
  private final Status mStatus;
  @NonNull
  private final List<OnlineSearchItem> mItems;
  @Nullable
  private final Error mError;
  @NonNull
  private final String mProviderName;

  OnlineSearchState(@NonNull Status status, @NonNull List<OnlineSearchItem> items, @Nullable Error error,
                    @NonNull String providerName)
  {
    mStatus = status;
    mItems = Collections.unmodifiableList(items);
    mError = error;
    mProviderName = providerName;
  }

  @NonNull
  public Status getStatus()
  {
    return mStatus;
  }

  /** Results to show (only with {@link Status#RESULTS}). */
  @NonNull
  public List<OnlineSearchItem> getItems()
  {
    return mItems;
  }

  @Nullable
  public Error getError()
  {
    return mError;
  }

  /** Name of the provider for the section title, e.g. "Photon". */
  @NonNull
  public String getProviderName()
  {
    return mProviderName;
  }

  public boolean isVisible()
  {
    return mStatus != Status.HIDDEN;
  }

  @NonNull
  @Override
  public String toString()
  {
    return mStatus + (mError != null ? " " + mError : "") + " " + mItems;
  }
}
