package app.organicmaps.sdk.search.online;

import androidx.annotation.NonNull;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Connection settings for a Photon server (https://github.com/komoot/photon). Immutable, create with
 * {@link #builder(String)}.
 * <p>
 * The public instance photon.komoot.io may be used in reasonable amounts only (fair use, no guarantees); heavier use
 * needs an own server. Requests carry a User-Agent with application name, version and contact.
 */
public final class PhotonConfig
{
  /** Public demo instance of komoot. */
  public static final String DEFAULT_URL = "https://photon.komoot.io";

  /** Languages of the public instance (besides "default", the local names). */
  public static final Set<String> DEFAULT_LANGUAGES =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("de", "en", "fr")));

  public static final long DEFAULT_CONNECT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);
  public static final long DEFAULT_READ_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);
  public static final long DEFAULT_CALL_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(15);

  @NonNull
  private final String mBaseUrl;
  @NonNull
  private final String mUserAgent;
  @NonNull
  private final Set<String> mLanguages;
  private final long mConnectTimeoutMs;
  private final long mReadTimeoutMs;
  private final long mCallTimeoutMs;

  private PhotonConfig(@NonNull Builder b)
  {
    mBaseUrl = b.mBaseUrl;
    mUserAgent = b.mUserAgent;
    mLanguages = b.mLanguages;
    mConnectTimeoutMs = b.mConnectTimeoutMs;
    mReadTimeoutMs = b.mReadTimeoutMs;
    mCallTimeoutMs = b.mCallTimeoutMs;
  }

  /** @param userAgent application name, version and contact, e.g. "better-maps/1.0 (+https://example.org)". */
  @NonNull
  public static Builder builder(@NonNull String userAgent)
  {
    return new Builder(userAgent);
  }

  /** Server root without trailing slash; the search endpoint is {@code <baseUrl>/api}. */
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

  /** Languages the server supports for "lang"; other languages are not sent (the server then uses local names). */
  @NonNull
  public Set<String> getLanguages()
  {
    return mLanguages;
  }

  public long getConnectTimeoutMs()
  {
    return mConnectTimeoutMs;
  }

  public long getReadTimeoutMs()
  {
    return mReadTimeoutMs;
  }

  public long getCallTimeoutMs()
  {
    return mCallTimeoutMs;
  }

  public static final class Builder
  {
    @NonNull
    private String mBaseUrl = DEFAULT_URL;
    @NonNull
    private final String mUserAgent;
    @NonNull
    private Set<String> mLanguages = DEFAULT_LANGUAGES;
    private long mConnectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private long mReadTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
    private long mCallTimeoutMs = DEFAULT_CALL_TIMEOUT_MS;

    private Builder(@NonNull String userAgent)
    {
      if (userAgent.trim().isEmpty())
        throw new IllegalArgumentException("A User-Agent is required");
      mUserAgent = userAgent;
    }

    /** @param baseUrl server root, e.g. "https://photon.komoot.io" or "http://10.0.2.2:2322". */
    @NonNull
    public Builder setBaseUrl(@NonNull String baseUrl)
    {
      String url = baseUrl.trim();
      while (url.endsWith("/"))
        url = url.substring(0, url.length() - 1);
      if (url.endsWith("/api"))
        url = url.substring(0, url.length() - 4);
      final String lower = url.toLowerCase(Locale.ROOT);
      if (!lower.startsWith("https://") && !lower.startsWith("http://"))
        throw new IllegalArgumentException("Base URL must start with http:// or https://: " + baseUrl);
      if (lower.length() <= "https://".length() || lower.endsWith("://"))
        throw new IllegalArgumentException("Base URL without host: " + baseUrl);
      mBaseUrl = url;
      return this;
    }

    @NonNull
    public Builder setLanguages(@NonNull Set<String> languages)
    {
      final Set<String> normalized = new HashSet<>();
      for (String language : languages)
      {
        final String lang = OnlineSearchRequest.normalizeLanguage(language);
        if (lang != null)
          normalized.add(lang);
      }
      mLanguages = Collections.unmodifiableSet(normalized);
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
    public PhotonConfig build()
    {
      return new PhotonConfig(this);
    }

    private static long requirePositive(long value)
    {
      if (value <= 0)
        throw new IllegalArgumentException("Timeout must be > 0: " + value);
      return value;
    }
  }
}
