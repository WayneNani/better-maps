package app.organicmaps.maplayer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Attribution of the aerial imagery layer (better-maps): which sources are visible in the map view, as answered by
 * the imagery server (GET /v1/attribution?bbox=minLon,minLat,maxLon,maxLat&z=zoom, see services/imagery-proxy). Pure
 * logic; the networking and the view are in {@link AerialAttributionController}. Parsed with Gson's tree API (no
 * reflection, nothing for R8 to strip).
 */
public final class AerialAttribution
{
  public static final class Source
  {
    @NonNull
    public final String id;
    @NonNull
    public final String name;
    @NonNull
    public final String attribution;
    @NonNull
    public final String license;
    @NonNull
    public final String licenseUrl;

    public Source(@NonNull String id, @NonNull String name, @NonNull String attribution, @NonNull String license,
                  @NonNull String licenseUrl)
    {
      this.id = id;
      this.name = name;
      this.attribution = attribution;
      this.license = license;
      this.licenseUrl = licenseUrl;
    }
  }

  private AerialAttribution() {}

  /** The view rounded so that small movements don't cause new requests; null if the view is invalid. */
  @Nullable
  public static String viewKey(@Nullable double[] rect, int zoom)
  {
    if (rect == null || rect.length != 4)
      return null;
    for (double v : rect)
      if (Double.isNaN(v) || Double.isInfinite(v))
        return null;
    if (rect[0] >= rect[2] || rect[1] >= rect[3])
      return null;
    // Step: a tenth of the view size, at least 0.001 degrees.
    final double step = Math.max(0.001, Math.min(rect[2] - rect[0], rect[3] - rect[1]) / 10);
    final StringBuilder sb = new StringBuilder();
    for (double v : rect)
      sb.append(Math.round(v / step)).append(',');
    return sb.append(zoom).toString();
  }

  /** The request URL for the view {minLon, minLat, maxLon, maxLat} at |zoom|, clamped to the valid range. */
  @NonNull
  public static String requestUrl(@NonNull String baseUrl, @NonNull double[] rect, int zoom)
  {
    final double minLon = clamp(rect[0], -180, 180);
    final double minLat = clamp(rect[1], -85.06, 85.06);
    final double maxLon = clamp(rect[2], -180, 180);
    final double maxLat = clamp(rect[3], -85.06, 85.06);
    return String.format(Locale.ROOT, "%s/v1/attribution?bbox=%.5f,%.5f,%.5f,%.5f&z=%d", baseUrl, minLon, minLat,
                         maxLon, maxLat, Math.max(0, Math.min(22, zoom)));
  }

  private static double clamp(double v, double min, double max)
  {
    return Math.max(min, Math.min(max, v));
  }

  /** Parses the server's answer; an empty list if it is broken. */
  @NonNull
  public static List<Source> parse(@Nullable String json)
  {
    if (json == null || json.isEmpty())
      return Collections.emptyList();
    try
    {
      final JsonElement root = JsonParser.parseString(json);
      if (!root.isJsonObject())
        return Collections.emptyList();
      final JsonElement sources = root.getAsJsonObject().get("sources");
      if (sources == null || !sources.isJsonArray())
        return Collections.emptyList();
      final JsonArray array = sources.getAsJsonArray();
      final List<Source> result = new ArrayList<>(array.size());
      for (JsonElement e : array)
      {
        if (!e.isJsonObject())
          continue;
        final JsonObject o = e.getAsJsonObject();
        final String attribution = string(o, "attribution");
        if (attribution.isEmpty())
          continue;
        result.add(new Source(string(o, "id"), string(o, "name"), attribution, string(o, "license"),
                              string(o, "license_url")));
      }
      return result;
    }
    catch (RuntimeException e)
    {
      return Collections.emptyList();
    }
  }

  @NonNull
  private static String string(@NonNull JsonObject o, @NonNull String key)
  {
    final JsonElement e = o.get(key);
    return e != null && e.isJsonPrimitive() ? e.getAsString().trim() : "";
  }

  /** The text shown on the map: the attributions, separated by " | ". */
  @NonNull
  public static String shortText(@NonNull List<Source> sources)
  {
    final StringBuilder sb = new StringBuilder();
    for (Source s : sources)
    {
      if (sb.length() > 0)
        sb.append(" | ");
      sb.append(s.attribution);
    }
    return sb.toString();
  }
}
