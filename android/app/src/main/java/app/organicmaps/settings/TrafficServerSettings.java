package app.organicmaps.settings;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.sdk.util.log.Logger;
import app.organicmaps.transit.KeyValueStore;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Access token for the TraFF server (better-maps, services/traff-server), entered by the user under Settings → Traffic
 * next to the server URL. The URL itself stays in the core settings (TrafficHttpUrl); the token lives in the
 * app-private "traffic_server" SharedPreferences, which are not part of the backup (see backup_content.xml), and in
 * the core's memory. Nothing is compiled into the APK; the token is never logged.
 *
 * The token is bound to the origin (scheme, host, port) of the URL it was entered for and is only sent there: never
 * to another server, never to the built-in default service, never over plain http to a public host (same URL rules as
 * {@link RoutingServerSettings#checkUrl}). Changing the URL to another origin drops the token.
 */
public final class TrafficServerSettings
{
  private static final String TAG = TrafficServerSettings.class.getSimpleName();

  public static final String PREFS_NAME = "traffic_server";
  public static final String KEY_TOKEN = "traffic_server_token";
  public static final String KEY_TOKEN_ORIGIN = "traffic_server_token_origin";

  /** Public TraFF services that never get a token, in addition to the core's default URL. */
  private static final String[] PUBLIC_SERVICES = {"https://api.dev.traffxml.org"};

  /** Where the token goes to (the core in the app, a fake in tests). */
  public interface CoreSink
  {
    void setTrafficHttpToken(@NonNull String token);
  }

  @NonNull
  private final KeyValueStore mStore;
  /** The core's built-in default URL (used while none is set). */
  @NonNull
  private final String mDefaultUrl;

  public TrafficServerSettings(@NonNull KeyValueStore store, @NonNull String defaultUrl)
  {
    mStore = store;
    mDefaultUrl = defaultUrl;
  }

  @NonNull
  public static TrafficServerSettings get(@NonNull Context context)
  {
    return new TrafficServerSettings(KeyValueStore.of(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
                                     Config.getTrafficHttpUrlDefault());
  }

  /** Passes the token for the current TraFF URL to the core. Call after the framework is initialized. */
  public static void applyToCore(@NonNull Context context)
  {
    applyToCore(context, Config.getTrafficHttpUrl());
  }

  /** Passes the token for |url| to the core; call before the URL itself is changed to |url|. */
  public static void applyToCore(@NonNull Context context, @Nullable String url)
  {
    final boolean sent = get(context).apply(url, Config::setTrafficHttpToken);
    Logger.i(TAG, "TraFF access token " + (sent ? "in use" : "not in use"));
  }

  /**
   * @return "scheme://host:port" (lower case, default port filled in) of a usable URL (https, or http to a local
   *     address), else null.
   */
  @Nullable
  public static String origin(@Nullable String url)
  {
    if (RoutingServerSettings.checkUrl(url) != null)
      return null;
    try
    {
      final URI uri = new URI(url.trim());
      final String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
      int port = uri.getPort();
      if (port == -1)
        port = scheme.equals("https") ? 443 : 80;
      return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }
    catch (URISyntaxException | RuntimeException e)
    {
      return null;
    }
  }

  /** Whether |url| belongs to the built-in default or another public service, which never get a token. */
  public boolean isPublicService(@Nullable String url)
  {
    final String o = origin(url);
    if (o == null)
      return false;
    if (o.equals(origin(mDefaultUrl)))
      return true;
    for (String service : PUBLIC_SERVICES)
      if (o.equals(origin(service)))
        return true;
    return false;
  }

  /** Whether a token can be entered for |url|: a usable URL of the user's own server. */
  public boolean acceptsTokenFor(@Nullable String url)
  {
    return origin(url) != null && !isPublicService(url);
  }

  @NonNull
  public String getToken()
  {
    final String token = mStore.getString(KEY_TOKEN, null);
    return token == null ? "" : token.trim();
  }

  public boolean hasToken()
  {
    return !getToken().isEmpty();
  }

  /** The origin the stored token belongs to, or "" if there is none. */
  @NonNull
  public String getTokenOrigin()
  {
    final String o = mStore.getString(KEY_TOKEN_ORIGIN, null);
    return o == null ? "" : o;
  }

  /**
   * Stores |token| for the server at |url|. An empty token deletes the stored one.
   *
   * @return false (and nothing changed) if the token is not empty and |url| does not accept one (see
   *     {@link #acceptsTokenFor}).
   */
  public boolean setToken(@Nullable String token, @Nullable String url)
  {
    if (token == null || token.trim().isEmpty())
    {
      clearToken();
      return true;
    }
    if (!acceptsTokenFor(url))
      return false;
    mStore.putString(KEY_TOKEN, token.trim());
    mStore.putString(KEY_TOKEN_ORIGIN, origin(url));
    return true;
  }

  public void clearToken()
  {
    mStore.remove(KEY_TOKEN);
    mStore.remove(KEY_TOKEN_ORIGIN);
  }

  /**
   * Call when the traffic URL changes to |newUrl|: drops the token if it belongs to another origin.
   *
   * @return whether a token was dropped (the user has to enter it again).
   */
  public boolean onUrlChanged(@Nullable String newUrl)
  {
    if (!hasToken() && getTokenOrigin().isEmpty())
      return false;
    final String o = origin(newUrl);
    if (o != null && o.equals(getTokenOrigin()) && !isPublicService(newUrl))
      return false;
    final boolean had = hasToken();
    clearToken();
    return had;
  }

  /** @return the token to send to |url|: the stored one if it was entered for the origin of |url|, else "". */
  @NonNull
  public String tokenFor(@Nullable String url)
  {
    final String o = origin(url);
    if (o == null || isPublicService(url) || !o.equals(getTokenOrigin()))
      return "";
    return getToken();
  }

  /**
   * Passes the token for |url| to |sink| ("" if there is none for that origin).
   *
   * @return whether a token is passed on.
   */
  public boolean apply(@Nullable String url, @NonNull CoreSink sink)
  {
    final String token = tokenFor(url);
    sink.setTrafficHttpToken(token);
    return !token.isEmpty();
  }
}
