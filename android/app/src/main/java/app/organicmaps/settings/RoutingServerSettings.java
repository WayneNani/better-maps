package app.organicmaps.settings;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.util.log.Logger;
import app.organicmaps.transit.KeyValueStore;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Server-assisted car routing (better-maps, services/route-server): long car routes follow the way of a routing
 * server across the maps and are only refined on the phone. Off by default; the URL and the device token are entered
 * by the user. Values live in the app-private "routing_server" SharedPreferences, which are not part of the backup
 * (see backup_content.xml). The token is never logged.
 */
public final class RoutingServerSettings
{
  private static final String TAG = RoutingServerSettings.class.getSimpleName();

  /** Name of the SharedPreferences file. */
  public static final String PREFS_NAME = "routing_server";

  public static final String KEY_ENABLED = "routing_server_enabled";
  public static final String KEY_URL = "routing_server_url";
  public static final String KEY_TOKEN = "routing_server_token";

  /** Why a URL can't be used. */
  public enum UrlProblem
  {
    EMPTY,
    /** Not an http(s) URL with a host, or with user info, query or fragment. */
    INVALID,
    /** http:// to a public host would send the token in plain text. */
    INSECURE
  }

  /** Where the settings go to (the core in the app, a fake in tests). */
  public interface CoreSink
  {
    void setRoutingServer(@NonNull String url, @NonNull String token);
  }

  @NonNull
  private final KeyValueStore mStore;

  public RoutingServerSettings(@NonNull KeyValueStore store)
  {
    mStore = store;
  }

  @NonNull
  public static RoutingServerSettings get(@NonNull Context context)
  {
    return new RoutingServerSettings(
        KeyValueStore.of(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)));
  }

  /** Passes the current settings to the core. Call after the framework is initialized and after each change. */
  public static void applyToCore(@NonNull Context context)
  {
    final boolean active = get(context).apply(Framework::nativeSetRoutingServer);
    Logger.i(TAG, "Server-assisted routing " + (active ? "on" : "off"));
  }

  public boolean isEnabled()
  {
    return mStore.getBoolean(KEY_ENABLED, false);
  }

  public void setEnabled(boolean enabled)
  {
    mStore.putBoolean(KEY_ENABLED, enabled);
  }

  @NonNull
  public String getUrl()
  {
    final String url = mStore.getString(KEY_URL, null);
    return url == null ? "" : url.trim();
  }

  public void setUrl(@Nullable String url)
  {
    mStore.putString(KEY_URL, url == null ? "" : url.trim());
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

  /** True if switched on with a usable URL and a token. */
  public boolean isActive()
  {
    return isEnabled() && checkUrl(getUrl()) == null && hasToken();
  }

  /**
   * Passes the settings to |sink|: URL and token if active, else empty strings (off).
   *
   * @return whether server-assisted routing is on.
   */
  public boolean apply(@NonNull CoreSink sink)
  {
    final boolean active = isActive();
    if (active)
      sink.setRoutingServer(getUrl(), getToken());
    else
      sink.setRoutingServer("", "");
    return active;
  }

  /** @return null if |url| can be used, else the problem. */
  @Nullable
  public static UrlProblem checkUrl(@Nullable String url)
  {
    if (url == null || url.trim().isEmpty())
      return UrlProblem.EMPTY;
    final URI uri;
    try
    {
      uri = new URI(url.trim());
    }
    catch (URISyntaxException e)
    {
      return UrlProblem.INVALID;
    }
    final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    final String host = uri.getHost();
    if ((!scheme.equals("https") && !scheme.equals("http")) || host == null || host.isEmpty()
        || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
      return UrlProblem.INVALID;
    if (scheme.equals("http") && !isLocalHost(host))
      return UrlProblem.INSECURE;
    return null;
  }

  /** Hosts for which plain http is accepted: loopback, the emulator's host, private IPv4 networks, mDNS names. */
  static boolean isLocalHost(@NonNull String host)
  {
    final String h = host.toLowerCase(Locale.ROOT);
    if (h.equals("localhost") || h.endsWith(".local") || h.equals("[::1]") || h.equals("::1"))
      return true;
    final String[] parts = h.split("\\.", -1);
    if (parts.length != 4)
      return false;
    final int[] octets = new int[4];
    for (int i = 0; i < 4; ++i)
    {
      if (parts[i].isEmpty() || parts[i].length() > 3 || !parts[i].chars().allMatch(Character::isDigit))
        return false;
      octets[i] = Integer.parseInt(parts[i]);
      if (octets[i] > 255)
        return false;
    }
    return octets[0] == 127 || octets[0] == 10 || (octets[0] == 192 && octets[1] == 168)
        || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31);
  }
}
