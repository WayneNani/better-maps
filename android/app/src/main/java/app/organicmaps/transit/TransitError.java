package app.organicmaps.transit;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.transit.TransitServiceException;

/** User-facing classes of errors of online public transport requests. */
public enum TransitError
{
  /** No connection or timeout. */
  NETWORK,
  /** The server failed, is overloaded or rate-limits us. */
  SERVER,
  /** The server rejected the request, e.g. a place outside the covered area. */
  REQUEST,
  /** Nothing found, e.g. no stop near the place. */
  NO_RESULTS,
  /** Online public transport is off or not agreed to. */
  DISABLED,
  UNKNOWN;

  @NonNull
  public static TransitError from(@NonNull Throwable error)
  {
    if (!(error instanceof TransitServiceException))
      return UNKNOWN;
    final TransitServiceException e = (TransitServiceException) error;
    if (e.getHttpCode() == 429)
      return SERVER;
    switch (e.getKind())
    {
    case NETWORK: return NETWORK;
    case SERVER_ERROR:
    case INVALID_RESPONSE: return SERVER;
    case CLIENT_ERROR: return REQUEST;
    case NO_RESULTS: return NO_RESULTS;
    case DISABLED: return DISABLED;
    default: return UNKNOWN;
    }
  }

  /** True if offering "try again" makes sense. */
  public boolean isRetryable()
  {
    return this == NETWORK || this == SERVER || this == UNKNOWN;
  }
}
