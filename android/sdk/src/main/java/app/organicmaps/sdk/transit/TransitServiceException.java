package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Failure of an online public transport request, delivered via {@code AsyncResult.onFailure}. */
public class TransitServiceException extends Exception
{
  public enum Kind
  {
    /** No connection, timeout, TLS error, ... Retrying later may help. */
    NETWORK,
    /** HTTP 4xx: invalid request, e.g. unknown stop id or invalid parameters. */
    CLIENT_ERROR,
    /** HTTP 5xx: the server failed or is overloaded. */
    SERVER_ERROR,
    /** The response could not be parsed. */
    INVALID_RESPONSE,
    /** The request succeeded, but nothing was found (e.g. no stop near the given point). */
    NO_RESULTS,
    /** No request was sent: online public transport is switched off or the user has not agreed to it. */
    DISABLED
  }

  @NonNull
  private final Kind mKind;
  private final int mHttpCode;
  @Nullable
  private final String mServerMessage;

  public TransitServiceException(@NonNull Kind kind, int httpCode, @Nullable String serverMessage,
                                 @NonNull String message, @Nullable Throwable cause)
  {
    super(message, cause);
    mKind = kind;
    mHttpCode = httpCode;
    mServerMessage = serverMessage;
  }

  @NonNull
  public static TransitServiceException noResults(@NonNull String message)
  {
    return new TransitServiceException(Kind.NO_RESULTS, 0, null, message, null);
  }

  @NonNull
  public static TransitServiceException disabled(@NonNull String message)
  {
    return new TransitServiceException(Kind.DISABLED, 0, null, message, null);
  }

  @NonNull
  public Kind getKind()
  {
    return mKind;
  }

  /** HTTP status code, 0 if there was no HTTP response. */
  public int getHttpCode()
  {
    return mHttpCode;
  }

  /** Error text from the server's JSON error response ({"error": "..."}), if any. */
  @Nullable
  public String getServerMessage()
  {
    return mServerMessage;
  }

  /** True if retrying the same request later may succeed. */
  public boolean isRetryable()
  {
    return mKind == Kind.NETWORK || mKind == Kind.SERVER_ERROR || mHttpCode == 429;
  }
}
