package app.organicmaps.ev;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.transit.KeyValueStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** In-memory {@link KeyValueStore} for tests. */
class EvTestStore implements KeyValueStore
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

  @SuppressWarnings("unchecked")
  @Nullable
  @Override
  public Set<String> getStringSet(@NonNull String key, @Nullable Set<String> defaultValue)
  {
    return mValues.get(key) instanceof Set ? (Set<String>) mValues.get(key) : defaultValue;
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
