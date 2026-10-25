package app.organicmaps.sdk.search.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Failure of an online search request, delivered via {@code AsyncResult.onFailure}. */
public class OnlineSearchException extends Exception
{
  public enum Kind
  {
    /** No connection, timeout, TLS error, ... Retrying later may help. */
    NETWORK,
    /** HTTP 4xx: the server rejected the request, e.g. an unsupported language or parameter. */
    CLIENT_ERROR,
    /** HTTP 5xx: the server failed or is overloaded. */
    SERVER_ERROR,
    /** The response could not be parsed. */
    INVALID_RESPONSE,
    /** No request was sent: online search is switched off or the user has not agreed to it. */
    DISABLED
  }

  @NonNull
  private final Kind mKind;
  private final int mHttpCode;
  @Nullable
  private final String mServerMessage;

  public OnlineSearchException(@NonNull Kind kind, int httpCode, @Nullable String serverMessage,
                               @NonNull String message, @Nullable Throwable cause)
  {
    super(message, cause);
    mKind = kind;
    mHttpCode = httpCode;
    mServerMessage = serverMessage;
  }

  @NonNull
  public static OnlineSearchException disabled(@NonNull String message)
  {
    return new OnlineSearchException(Kind.DISABLED, 0, null, message, null);
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

  /** Error text from the server's response, if any. */
  @Nullable
  public String getServerMessage()
  {
    return mServerMessage;
  }

  /** True if retrying the same request later may succeed (network, HTTP 5xx, HTTP 429). */
  public boolean isRetryable()
  {
    return mKind == Kind.NETWORK || mKind == Kind.SERVER_ERROR || mHttpCode == 429;
  }
}
