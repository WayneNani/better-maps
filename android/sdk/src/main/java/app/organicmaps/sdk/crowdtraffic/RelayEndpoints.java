package app.organicmaps.sdk.crowdtraffic;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * URLs of the OHTTP relay (docs/CROWD_TRAFFIC_PRIVACY.md, 13.1): the relay resource that receives the
 * encapsulated requests and the key configuration, which is also fetched through the relay.
 * <p>
 * The setting accepts either the base URL of the relay ("http://10.0.2.2:8090"; requests go to
 * "/relay", keys to "/ohttp-keys", as served by services/crowd-traffic/cmd/ohttp-relay) or the full
 * URL of the relay resource ("https://relay.example/crowd/relay"; keys next to it,
 * "https://relay.example/crowd/ohttp-keys").
 */
public final class RelayEndpoints
{
  @NonNull
  public final String relayUrl;
  @NonNull
  public final String keysUrl;

  private RelayEndpoints(@NonNull String relayUrl, @NonNull String keysUrl)
  {
    this.relayUrl = relayUrl;
    this.keysUrl = keysUrl;
  }

  /** Returns the endpoints for a configured URL, or null if it is empty or not an http(s) URL with a host. */
  @Nullable
  public static RelayEndpoints parse(@Nullable String configured)
  {
    if (configured == null)
      return null;
    final String s = configured.trim();
    if (s.isEmpty())
      return null;
    final URI uri;
    try
    {
      uri = new URI(s);
    }
    catch (URISyntaxException e)
    {
      return null;
    }
    final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https"))
      return null;
    if (uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null
        || uri.getRawUserInfo() != null)
      return null;
    final String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    final String origin = scheme + "://" + uri.getRawAuthority();
    if (path.isEmpty() || path.equals("/"))
      return new RelayEndpoints(origin + "/relay", origin + "/ohttp-keys");
    if (path.endsWith("/"))
      return new RelayEndpoints(origin + path + "relay", origin + path + "ohttp-keys");
    final String dir = path.substring(0, path.lastIndexOf('/') + 1);
    return new RelayEndpoints(origin + path, origin + dir + "ohttp-keys");
  }
}
