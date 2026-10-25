package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;
import java.util.concurrent.TimeUnit;

/**
 * Connection settings for a MOTIS server. Immutable, create with {@link #builder(String)}.
 * <p>
 * Transitous requires a User-Agent with application name, version and contact (e-mail or website), see
 * https://transitous.org/api/.
 */
public final class TransitServiceConfig
{
  /** Public MOTIS instance of the Transitous project. */
  public static final String TRANSITOUS_URL = "https://api.transitous.org";

  public static final long DEFAULT_CONNECT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);
  public static final long DEFAULT_READ_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30);
  public static final long DEFAULT_CALL_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(45);

  @NonNull
  private final String mBaseUrl;
  @NonNull
  private final String mUserAgent;
  private final long mConnectTimeoutMs;
  private final long mReadTimeoutMs;
  private final long mCallTimeoutMs;

  private TransitServiceConfig(@NonNull Builder b)
  {
    mBaseUrl = b.mBaseUrl;
    mUserAgent = b.mUserAgent;
    mConnectTimeoutMs = b.mConnectTimeoutMs;
    mReadTimeoutMs = b.mReadTimeoutMs;
    mCallTimeoutMs = b.mCallTimeoutMs;
  }

  /**
   * @param userAgent e.g. "CoMaps/2026.10.25 (+https://example.org)": application name, version and contact.
   */
  @NonNull
  public static Builder builder(@NonNull String userAgent)
  {
    return new Builder(userAgent);
  }

  /** Server root without trailing slash, e.g. "https://api.transitous.org". */
  @NonNull
  public String getBaseUrl()
  {
    return mBaseUrl;
  }

  @NonNull
  public String getUserAgent()
  {
    return mUserAgent;
  }

  public long getConnectTimeoutMs()
  {
    return mConnectTimeoutMs;
  }

  public long getReadTimeoutMs()
  {
    return mReadTimeoutMs;
  }

  /** Limit for the whole call including connecting, sending and reading. */
  public long getCallTimeoutMs()
  {
    return mCallTimeoutMs;
  }

  public static final class Builder
  {
    @NonNull
    private String mBaseUrl = TRANSITOUS_URL;
    @NonNull
    private final String mUserAgent;
    private long mConnectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private long mReadTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
    private long mCallTimeoutMs = DEFAULT_CALL_TIMEOUT_MS;

    private Builder(@NonNull String userAgent)
    {
      if (userAgent.trim().isEmpty())
        throw new IllegalArgumentException("A User-Agent is required");
      mUserAgent = userAgent;
    }

    /** @param baseUrl server root, e.g. "https://api.transitous.org" or "http://10.0.2.2:8080". */
    @NonNull
    public Builder setBaseUrl(@NonNull String baseUrl)
    {
      String url = baseUrl.trim();
      while (url.endsWith("/"))
        url = url.substring(0, url.length() - 1);
      if (!url.startsWith("https://") && !url.startsWith("http://"))
        throw new IllegalArgumentException("Base URL must start with http:// or https://: " + baseUrl);
      mBaseUrl = url;
      return this;
    }

    @NonNull
    public Builder setConnectTimeoutMs(long timeoutMs)
    {
      mConnectTimeoutMs = requirePositive(timeoutMs);
      return this;
    }

    @NonNull
    public Builder setReadTimeoutMs(long timeoutMs)
    {
      mReadTimeoutMs = requirePositive(timeoutMs);
      return this;
    }

    @NonNull
    public Builder setCallTimeoutMs(long timeoutMs)
    {
      mCallTimeoutMs = requirePositive(timeoutMs);
      return this;
    }

    @NonNull
    public TransitServiceConfig build()
    {
      return new TransitServiceConfig(this);
    }

    private static long requirePositive(long value)
    {
      if (value <= 0)
        throw new IllegalArgumentException("Timeout must be > 0: " + value);
      return value;
    }
  }
}
