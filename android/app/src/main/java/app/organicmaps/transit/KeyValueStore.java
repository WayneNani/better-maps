package app.organicmaps.transit;

import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Set;

/** Minimal persistent key-value storage, so that settings logic can be tested without Android. */
public interface KeyValueStore
{
  boolean contains(@NonNull String key);

  boolean getBoolean(@NonNull String key, boolean defaultValue);

  @Nullable
  String getString(@NonNull String key, @Nullable String defaultValue);

  @Nullable
  Set<String> getStringSet(@NonNull String key, @Nullable Set<String> defaultValue);

  int getInt(@NonNull String key, int defaultValue);

  long getLong(@NonNull String key, long defaultValue);

  void putBoolean(@NonNull String key, boolean value);

  void putString(@NonNull String key, @Nullable String value);

  void putInt(@NonNull String key, int value);

  void putLong(@NonNull String key, long value);

  void remove(@NonNull String key);

  @NonNull
  static KeyValueStore of(@NonNull SharedPreferences prefs)
  {
    return new KeyValueStore() {
      @Override
      public boolean contains(@NonNull String key)
      {
        return prefs.contains(key);
      }

      @Override
      public boolean getBoolean(@NonNull String key, boolean defaultValue)
      {
        return prefs.getBoolean(key, defaultValue);
      }

      @Nullable
      @Override
      public String getString(@NonNull String key, @Nullable String defaultValue)
      {
        return prefs.getString(key, defaultValue);
      }

      @Nullable
      @Override
      public Set<String> getStringSet(@NonNull String key, @Nullable Set<String> defaultValue)
      {
        return prefs.getStringSet(key, defaultValue);
      }

      @Override
      public int getInt(@NonNull String key, int defaultValue)
      {
        return prefs.getInt(key, defaultValue);
      }

      @Override
      public long getLong(@NonNull String key, long defaultValue)
      {
        return prefs.getLong(key, defaultValue);
      }

      @Override
      public void putBoolean(@NonNull String key, boolean value)
      {
        prefs.edit().putBoolean(key, value).apply();
      }

      @Override
      public void putString(@NonNull String key, @Nullable String value)
      {
        prefs.edit().putString(key, value).apply();
      }

      @Override
      public void putInt(@NonNull String key, int value)
      {
        prefs.edit().putInt(key, value).apply();
      }

      @Override
      public void putLong(@NonNull String key, long value)
      {
        prefs.edit().putLong(key, value).apply();
      }

      @Override
      public void remove(@NonNull String key)
      {
        prefs.edit().remove(key).apply();
      }
    };
  }
}
