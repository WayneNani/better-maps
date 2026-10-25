package app.organicmaps.sdk.search.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Converts Photon responses (GeoJSON FeatureCollection) into {@link OnlineSearchResult}s.
 * <p>
 * Each feature has a point geometry ([lon, lat]) and properties: name, osm_type/osm_id, osm_key/osm_value, type
 * (house, street, locality, district, city, county, state, country, other), address parts and an optional extent
 * [minLon, maxLat, maxLon, minLat]. Features without a valid point are skipped.
 */
final class PhotonMapper
{
  private PhotonMapper() {}

  /** @throws JsonParseException if the body is not a FeatureCollection. */
  @NonNull
  static List<OnlineSearchResult> toResults(@NonNull String body)
  {
    final JsonElement root = JsonParser.parseString(body);
    if (!root.isJsonObject())
      throw new JsonParseException("Not a JSON object");
    final JsonObject object = root.getAsJsonObject();
    final JsonElement features = object.get("features");
    if (features == null || !features.isJsonArray())
      throw new JsonParseException("No features array");
    final JsonArray array = features.getAsJsonArray();
    if (array.isEmpty())
      return Collections.emptyList();
    final List<OnlineSearchResult> results = new ArrayList<>(array.size());
    for (JsonElement feature : array)
    {
      final OnlineSearchResult result = toResult(feature);
      if (result != null)
        results.add(result);
    }
    return results;
  }

  @Nullable
  static OnlineSearchResult toResult(@Nullable JsonElement element)
  {
    if (element == null || !element.isJsonObject())
      return null;
    final JsonObject feature = element.getAsJsonObject();
    final double[] lonLat = point(feature.get("geometry"));
    if (lonLat == null)
      return null;
    final JsonObject p = object(feature.get("properties"));
    final OnlineSearchResult.Builder b;
    try
    {
      b = OnlineSearchResult.builder(lonLat[1], lonLat[0]);
    }
    catch (IllegalArgumentException e)
    {
      return null;
    }
    if (p == null)
      return b.build();
    b.setName(string(p, "name"))
        .setOsm(string(p, "osm_type"), number(p, "osm_id"))
        .setTag(string(p, "osm_key"), string(p, "osm_value"))
        .setPlaceType(OnlineSearchResult.PlaceType.parse(string(p, "type")))
        .setStreet(string(p, "street"), string(p, "housenumber"))
        .setPostcode(string(p, "postcode"))
        .setLocality(string(p, "locality"))
        .setDistrict(string(p, "district"))
        .setCity(string(p, "city"))
        .setCounty(string(p, "county"))
        .setState(string(p, "state"))
        .setCountry(string(p, "country"), string(p, "countrycode"))
        .setExtent(extent(p.get("extent")));
    return b.build();
  }

  /**
   * Extracts a readable message from an error response. Photon answers e.g. {"message": "..."} or, for invalid
   * parameters, {"lang": [{"message": "Language is not supported. ..."}]}.
   */
  @Nullable
  static String parseServerMessage(@Nullable String body)
  {
    if (body == null || body.isEmpty())
      return null;
    try
    {
      final JsonElement json = JsonParser.parseString(body);
      if (!json.isJsonObject())
        return null;
      final JsonObject object = json.getAsJsonObject();
      final String message = string(object, "message");
      if (message != null)
        return message;
      for (Map.Entry<String, JsonElement> entry : object.entrySet())
      {
        if (!entry.getValue().isJsonArray())
          continue;
        for (JsonElement item : entry.getValue().getAsJsonArray())
        {
          final JsonObject itemObject = object(item);
          final String itemMessage = itemObject != null ? string(itemObject, "message") : null;
          if (itemMessage != null)
            return entry.getKey() + ": " + itemMessage;
        }
      }
    }
    catch (RuntimeException ignored)
    {
      // Not JSON, e.g. an HTML error page of a proxy.
    }
    return null;
  }

  @Nullable
  private static double[] point(@Nullable JsonElement geometry)
  {
    final JsonObject object = object(geometry);
    if (object == null)
      return null;
    final JsonElement coordinates = object.get("coordinates");
    if (coordinates == null || !coordinates.isJsonArray() || coordinates.getAsJsonArray().size() < 2)
      return null;
    final Double lon = asDouble(coordinates.getAsJsonArray().get(0));
    final Double lat = asDouble(coordinates.getAsJsonArray().get(1));
    if (lon == null || lat == null)
      return null;
    return new double[] {lon, lat};
  }

  @Nullable
  private static OnlineSearchResult.BoundingBox extent(@Nullable JsonElement element)
  {
    if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 4)
      return null;
    final JsonArray a = element.getAsJsonArray();
    final Double lon1 = asDouble(a.get(0));
    final Double lat1 = asDouble(a.get(1));
    final Double lon2 = asDouble(a.get(2));
    final Double lat2 = asDouble(a.get(3));
    if (lon1 == null || lat1 == null || lon2 == null || lat2 == null)
      return null;
    return new OnlineSearchResult.BoundingBox(lat1, lon1, lat2, lon2);
  }

  @Nullable
  private static JsonObject object(@Nullable JsonElement element)
  {
    return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
  }

  @Nullable
  private static String string(@NonNull JsonObject object, @NonNull String key)
  {
    final JsonElement value = object.get(key);
    if (value == null || !value.isJsonPrimitive())
      return null;
    return value.getAsString();
  }

  private static long number(@NonNull JsonObject object, @NonNull String key)
  {
    final JsonElement value = object.get(key);
    if (value == null || !value.isJsonPrimitive())
      return 0;
    try
    {
      return value.getAsLong();
    }
    catch (NumberFormatException e)
    {
      return 0;
    }
  }

  @Nullable
  private static Double asDouble(@Nullable JsonElement value)
  {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      return null;
    final double d = value.getAsDouble();
    return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
  }
}
