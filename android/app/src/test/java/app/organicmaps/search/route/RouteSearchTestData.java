package app.organicmaps.search.route;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.bookmarks.data.FeatureId;
import app.organicmaps.sdk.search.Popularity;
import app.organicmaps.sdk.search.RoutePosition;
import app.organicmaps.sdk.search.SearchResult;
import app.organicmaps.sdk.util.Distance;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Results and helpers for the tests of the search along the route. */
final class RouteSearchTestData
{
  private RouteSearchTestData() {}

  /** 12 km ahead ("12 km" with kilometers as units). */
  @NonNull
  static RoutePosition position(double km, int detourSec)
  {
    final Distance distance = km >= 1 ? new Distance(km * 1000, String.valueOf((int) km), (byte) 1)
                                      : new Distance(km * 1000, String.valueOf((int) (km * 1000)), (byte) 0);
    return new RoutePosition(distance, km * 1000, 100, (int) (km * 60), detourSec);
  }

  @NonNull
  static SearchResult result(@NonNull String name, int openNow, @Nullable RoutePosition position)
  {
    final SearchResult.Description description = new SearchResult.Description(
        new FeatureId("Germany_Bonn", 261018, 42), "Gas Station", "Siegburg", Distance.EMPTY, "", openNow, 0, 0, false);
    return new SearchResult(name, description, 50.8, 7.2, new int[0], new int[0], Popularity.defaultInstance(),
                            position);
  }

  /** In-memory store, only what RouteSearchSettings uses. */
  static class Store implements app.organicmaps.transit.KeyValueStore
  {
    final Map<String, Object> mValues = new HashMap<>();

    @Override
    public boolean contains(@NonNull String key)
    {
      return mValues.containsKey(key);
    }

    @Override
    public boolean getBoolean(@NonNull String key, boolean defaultValue)
    {
      return mValues.get(key) instanceof Boolean b ? b : defaultValue;
    }

    @Nullable
    @Override
    public String getString(@NonNull String key, @Nullable String defaultValue)
    {
      return mValues.get(key) instanceof String s ? s : defaultValue;
    }

    @Nullable
    @Override
    public Set<String> getStringSet(@NonNull String key, @Nullable Set<String> defaultValue)
    {
      return defaultValue;
    }

    @Override
    public int getInt(@NonNull String key, int defaultValue)
    {
      return mValues.get(key) instanceof Integer i ? i : defaultValue;
    }

    @Override
    public long getLong(@NonNull String key, long defaultValue)
    {
      return mValues.get(key) instanceof Long l ? l : defaultValue;
    }

    @Override
    public void putBoolean(@NonNull String key, boolean value)
    {
      mValues.put(key, value);
    }

    @Override
    public void putString(@NonNull String key, @Nullable String value)
    {
      mValues.put(key, value);
    }

    @Override
    public void putInt(@NonNull String key, int value)
    {
      mValues.put(key, value);
    }

    @Override
    public void putLong(@NonNull String key, long value)
    {
      mValues.put(key, value);
    }

    @Override
    public void remove(@NonNull String key)
    {
      mValues.remove(key);
    }
  }
}
