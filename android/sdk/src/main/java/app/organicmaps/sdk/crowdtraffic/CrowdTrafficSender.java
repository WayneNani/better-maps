package app.organicmaps.sdk.crowdtraffic;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Sends prepared bundles through the OHTTP relay (docs/CROWD_TRAFFIC_PRIVACY.md, 13.4 and 13.5).
 * <ul>
 * <li>The gateway key configuration is fetched through the relay and cached for up to 24 hours.</li>
 * <li>Requests carry no identifying headers: no cookies, no individual User-Agent (a constant one replaces the
 * platform default, which names the device model and build).</li>
 * <li>Inner 2xx: accepted. Inner 4xx: rejected, never repeated. Outer 400 with the problem type
 * "ohttp-key": fetch the keys again and send once more. 429/503, inner 5xx and network errors: retry later
 * (the caller decides until when).</li>
 * </ul>
 * Pure Java (no Android dependencies), so that the end-to-end test can run it on the JVM.
 */
public final class CrowdTrafficSender
{
  public enum Result
  {
    /** The gateway accepted the bundle. */
    ACCEPTED,
    /** The gateway refused the bundle (e.g. window too late); do not send it again. */
    REJECTED,
    /** Temporary problem (network, rate limit); may be retried until the deadline. */
    RETRY,
    /** Permanent problem (malformed keys, unexpected answer); drop the bundle. */
    FAILED
  }

  public interface Clock
  {
    long nowMs();
  }

  public interface Log
  {
    void log(@NonNull String message);
  }

  static final String PROBLEM_OHTTP_KEY = "https://iana.org/assignments/http-problem-types#ohttp-key";
  /** The same for every installation and version. */
  static final String USER_AGENT = "better-maps-crowd-traffic";
  static final long KEY_CACHE_MS = 24L * 60 * 60 * 1000;
  private static final int TIMEOUT_MS = 20000;
  private static final int MAX_RESPONSE_BYTES = 64 * 1024;

  @NonNull
  private final RelayEndpoints mEndpoints;
  @NonNull
  private final Clock mClock;
  @NonNull
  private final Log mLog;
  @Nullable
  private Ohttp.KeyConfig mKeys;
  private long mKeysFetchedMs;

  public CrowdTrafficSender(@NonNull RelayEndpoints endpoints, @NonNull Clock clock, @NonNull Log log)
  {
    mEndpoints = endpoints;
    mClock = clock;
    mLog = log;
  }

  private static final class HttpResult
  {
    final int status;
    @Nullable
    final String contentType;
    @NonNull
    final byte[] body;

    HttpResult(int status, @Nullable String contentType, @NonNull byte[] body)
    {
      this.status = status;
      this.contentType = contentType;
      this.body = body;
    }

    boolean hasType(@NonNull String type)
    {
      if (contentType == null)
        return false;
      final int semicolon = contentType.indexOf(';');
      final String mt = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
      return mt.trim().toLowerCase(Locale.ROOT).equals(type);
    }
  }

  @NonNull
  private static byte[] readAll(@Nullable InputStream in) throws IOException
  {
    if (in == null)
      return new byte[0];
    try (InputStream is = in)
    {
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      final byte[] buf = new byte[4096];
      int n;
      while ((n = is.read(buf)) != -1)
      {
        out.write(buf, 0, n);
        if (out.size() > MAX_RESPONSE_BYTES)
          throw new IOException("response too large");
      }
      return out.toByteArray();
    }
  }

  @NonNull
  private static HttpResult exchange(@NonNull String url, @Nullable byte[] body) throws IOException
  {
    final HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
    try
    {
      c.setConnectTimeout(TIMEOUT_MS);
      c.setReadTimeout(TIMEOUT_MS);
      c.setUseCaches(false);
      c.setInstanceFollowRedirects(false);
      c.setRequestProperty("User-Agent", USER_AGENT);
      if (body != null)
      {
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(body.length);
        c.setRequestProperty("Content-Type", Ohttp.CONTENT_TYPE_REQUEST);
        try (OutputStream out = c.getOutputStream())
        {
          out.write(body);
        }
      }
      final int status = c.getResponseCode();
      final InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
      return new HttpResult(status, c.getContentType(), readAll(in));
    }
    finally
    {
      c.disconnect();
    }
  }

  /** Fetches the key configuration through the relay. Returns null on a temporary error. */
  @Nullable
  private Result fetchKeys()
  {
    final HttpResult r;
    try
    {
      r = exchange(mEndpoints.keysUrl, null);
    }
    catch (IOException e)
    {
      mLog.log("key fetch failed: " + e.getClass().getSimpleName());
      return Result.RETRY;
    }
    if (r.status == 429 || r.status == 503)
      return Result.RETRY;
    if (r.status != 200 || !r.hasType(Ohttp.CONTENT_TYPE_KEYS))
    {
      mLog.log("key fetch: unexpected answer " + r.status);
      return Result.FAILED;
    }
    try
    {
      mKeys = Ohttp.selectKeyConfig(r.body);
      mKeysFetchedMs = mClock.nowMs();
      mLog.log("fetched gateway key " + mKeys.keyId);
      return null;
    }
    catch (Ohttp.OhttpException e)
    {
      mLog.log("key configuration rejected: " + e.getMessage());
      return Result.FAILED;
    }
  }

  /** Forget the cached key configuration (e.g. after a change of the relay). */
  public synchronized void clearKeys()
  {
    mKeys = null;
  }

  /** Encrypts and sends one prepared request. Blocking; call it off the main thread. */
  @NonNull
  public synchronized Result send(@NonNull byte[] request)
  {
    for (int attempt = 0;; ++attempt)
    {
      if (mKeys == null || mClock.nowMs() - mKeysFetchedMs > KEY_CACHE_MS || mClock.nowMs() < mKeysFetchedMs)
      {
        final Result r = fetchKeys();
        if (r != null)
          return r;
      }
      final Ohttp.EncapsulatedRequest enc;
      try
      {
        enc = Ohttp.encapsulateRequest(mKeys, request);
      }
      catch (Ohttp.OhttpException e)
      {
        mLog.log(e.getMessage());
        return Result.FAILED;
      }
      final HttpResult r;
      try
      {
        r = exchange(mEndpoints.relayUrl, enc.message);
      }
      catch (IOException e)
      {
        mLog.log("send failed: " + e.getClass().getSimpleName());
        return Result.RETRY;
      }
      if (r.status == 200 && r.hasType(Ohttp.CONTENT_TYPE_RESPONSE))
        return handleInner(enc, r.body);
      if (r.status == 400 && isStaleKey(r))
      {
        mKeys = null;
        if (attempt == 0)
        {
          mLog.log("gateway does not know the key, fetching keys again");
          continue;
        }
        return Result.FAILED;
      }
      mLog.log("relay or gateway answered " + r.status);
      if (r.status == 429 || r.status == 503)
        return Result.RETRY;
      return Result.FAILED;
    }
  }

  private static boolean isStaleKey(@NonNull HttpResult r)
  {
    // Problem details (RFC 9457); the type is enough, no JSON parser needed for one string field.
    final String body = new String(r.body, StandardCharsets.UTF_8);
    return body.replace(" ", "").contains("\"type\":\"" + PROBLEM_OHTTP_KEY + "\"");
  }

  @NonNull
  private Result handleInner(@NonNull Ohttp.EncapsulatedRequest enc, @NonNull byte[] body)
  {
    final BinaryHttp.Response inner;
    try
    {
      inner = BinaryHttp.parseResponse(Ohttp.decapsulateResponse(enc, body));
    }
    catch (Ohttp.OhttpException | IllegalArgumentException e)
    {
      mLog.log("invalid response: " + e.getMessage());
      return Result.FAILED;
    }
    if (inner.status >= 200 && inner.status < 300)
      return Result.ACCEPTED;
    // Problem type and title (the reason, e.g. "window_late") contain no report data.
    mLog.log("gateway rejected the bundle: " + inner.status + " " + new String(inner.body, StandardCharsets.UTF_8));
    if (inner.status >= 500)
      return Result.RETRY;
    return Result.REJECTED;
  }
}
