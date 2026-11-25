package app.organicmaps.settings;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.sdk.util.log.Logger;
import app.organicmaps.transit.KeyValueStore;

/**
 * Access token for the TraFF server (better-maps, services/traff-server), entered by the user under Settings → Traffic
 * next to the server URL. The URL itself stays in the core settings (TrafficHttpUrl); the token lives in the
 * app-private "traffic_server" SharedPreferences, which are not part of the backup (see backup_content.xml), and in
 * the core's memory. Nothing is compiled into the APK; the token is never logged. It is only passed on (and sent as
 * "Authorization: Bearer" with every TraFF request) if the URL is https://, or http:// to a local address (same
 * rules as {@link RoutingServerSettings#checkUrl}).
 */
public final class TrafficServerSettings
{
  private static final String TAG = TrafficServerSettings.class.getSimpleName();

  public static final String PREFS_NAME = "traffic_server";
  public static final String KEY_TOKEN = "traffic_server_token";

  /** Where the token goes to (the core in the app, a fake in tests). */
  public interface CoreSink
  {
    void setTrafficHttpToken(@NonNull String token);
  }

  @NonNull
  private final KeyValueStore mStore;

  public TrafficServerSettings(@NonNull KeyValueStore store)
  {
    mStore = store;
  }

  @NonNull
  public static TrafficServerSettings get(@NonNull Context context)
  {
    return new TrafficServerSettings(
        KeyValueStore.of(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)));
  }

  /**
   * Passes the token for the current TraFF URL to the core. Call after the framework is initialized and after each
   * change of the token.
   */
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

  public void setToken(@Nullable String token)
  {
    if (token == null || token.trim().isEmpty())
      mStore.remove(KEY_TOKEN);
    else
      mStore.putString(KEY_TOKEN, token.trim());
  }

  /** @return the token to send to |url|: the stored one if |url| is usable (https or local), else "". */
  @NonNull
  public String tokenFor(@Nullable String url)
  {
    return RoutingServerSettings.checkUrl(url) == null ? getToken() : "";
  }

  /**
   * Passes the token for |url| to |sink| ("" if there is none or |url| is not usable).
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
