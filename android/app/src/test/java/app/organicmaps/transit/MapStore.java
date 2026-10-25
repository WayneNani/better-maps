package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** In-memory {@link KeyValueStore} for tests. */
class MapStore implements KeyValueStore
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
    final Object value = mValues.get(key);
    return value instanceof Boolean ? (Boolean) value : defaultValue;
  }

  @Nullable
  @Override
  public String getString(@NonNull String key, @Nullable String defaultValue)
  {
    final Object value = mValues.get(key);
    return value instanceof String ? (String) value : defaultValue;
  }

  @SuppressWarnings("unchecked")
  @Nullable
  @Override
  public Set<String> getStringSet(@NonNull String key, @Nullable Set<String> defaultValue)
  {
    final Object value = mValues.get(key);
    return value instanceof Set ? (Set<String>) value : defaultValue;
  }

  @Override
  public int getInt(@NonNull String key, int defaultValue)
  {
    final Object value = mValues.get(key);
    return value instanceof Integer ? (Integer) value : defaultValue;
  }

  @Override
  public long getLong(@NonNull String key, long defaultValue)
  {
    final Object value = mValues.get(key);
    return value instanceof Long ? (Long) value : defaultValue;
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

  MapStore with(@NonNull String key, @NonNull Object value)
  {
    mValues.put(key, value);
    return this;
  }
}
