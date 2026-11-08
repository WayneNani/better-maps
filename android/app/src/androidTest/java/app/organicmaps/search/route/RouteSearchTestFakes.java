package app.organicmaps.search.route;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.bookmarks.data.FeatureId;
import app.organicmaps.sdk.search.OpeningHoursFilter;
import app.organicmaps.sdk.search.Popularity;
import app.organicmaps.sdk.search.RoutePosition;
import app.organicmaps.sdk.search.SearchResult;
import app.organicmaps.sdk.util.Distance;
import app.organicmaps.transit.KeyValueStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Search along the route without the native search and route, for the tests of its views. */
final class RouteSearchTestFakes
{
  private RouteSearchTestFakes() {}

  static class Engine implements RouteSearchPresenter.Engine
  {
    final List<String> mQueries = new ArrayList<>();
    final List<RouteSearchMapArea> mAreas = new ArrayList<>();
    long mTimestamp;
    boolean mStarted;
    boolean mFinished;

    @Override
    public boolean search(@NonNull String query, boolean isCategory, long timestamp, int halfWidthMeters,
                          @NonNull OpeningHoursFilter filter)
    {
      mQueries.add(query);
      mTimestamp = timestamp;
      return true;
    }

    @Override
    public void cancel()
    {}

    @Override
    public void showResult(int index)
    {}

    @Override
    public void showResultsOnMap(@NonNull int[] hiddenIndices)
    {}

    @Override
    public void showOnMap(@NonNull RouteSearchMapArea area)
    {
      mAreas.add(area);
    }

    @Override
    public void onStart()
    {
      mStarted = true;
    }

    @Override
    public void onFinish()
    {
      mFinished = true;
    }
  }

  /** The stops of the route: what was added. */
  static class Stops implements RouteSearchPresenter.Stops
  {
    final List<SearchResult> mAdded = new ArrayList<>();

    @Override
    public boolean canAddStop()
    {
      return true;
    }

    @Override
    public boolean isStop(@NonNull SearchResult result)
    {
      return mAdded.contains(result);
    }

    @Override
    public void addStop(@NonNull SearchResult result)
    {
      mAdded.add(result);
    }
  }

  static class Store implements KeyValueStore
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

    @Override
    public String getString(@NonNull String key, String defaultValue)
    {
      return mValues.get(key) instanceof String s ? s : defaultValue;
    }

    @Override
    public Set<String> getStringSet(@NonNull String key, Set<String> defaultValue)
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
    public void putString(@NonNull String key, String value)
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

  @NonNull
  static RouteSearchPresenter presenter(@NonNull Engine engine, @NonNull Stops stops)
  {
    return new RouteSearchPresenter(Router.Vehicle, new RouteSearchSettings(new Store()), engine, stops,
                                    System::nanoTime);
  }

  /** A place |km| ahead with a detour of |detourSec|, open on arrival. */
  @NonNull
  static SearchResult result(@NonNull String name, int featureIndex, double km, int detourSec)
  {
    final SearchResult.Description description =
        new SearchResult.Description(new FeatureId("Germany_Berlin", 261018, featureIndex), "Gas Station", "Berlin",
                                     Distance.EMPTY, "", SearchResult.OPEN_NOW_YES, 0, 0, false);
    final RoutePosition position =
        new RoutePosition(new Distance(km * 1000, String.valueOf((int) km), (byte) 1 /* km */), km * 1000, 300,
                          (int) (km * 60), detourSec);
    return new SearchResult(name, description, 52.5 + featureIndex * 0.001, 13.3, new int[0], new int[0],
                            Popularity.defaultInstance(), position);
  }
}
