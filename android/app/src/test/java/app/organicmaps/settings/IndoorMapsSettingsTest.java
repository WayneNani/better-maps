package app.organicmaps.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.transit.KeyValueStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class IndoorMapsSettingsTest
{
  private static final String TOKEN = "aW5kb29yLXRva2VuLTAxMjM0NTY3ODlhYmNkZWZnaGlq";

  /** In-memory store. */
  private static final class Store implements KeyValueStore
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
      final Object v = mValues.get(key);
      return v instanceof Boolean ? (Boolean) v : defaultValue;
    }

    @Nullable
    @Override
    public String getString(@NonNull String key, @Nullable String defaultValue)
    {
      final Object v = mValues.get(key);
      return v instanceof String ? (String) v : defaultValue;
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
      return defaultValue;
    }

    @Override
    public long getLong(@NonNull String key, long defaultValue)
    {
      return defaultValue;
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

  /** Records what reaches the core. */
  private static final class Sink implements IndoorMapsSettings.CoreSink
  {
    final List<Object[]> mCalls = new ArrayList<>();

    @Override
    public void setIndoorServer(boolean enabled, @NonNull String url, @NonNull String token)
    {
      mCalls.add(new Object[] {enabled, url, token});
    }

    Object[] last()
    {
      return mCalls.get(mCalls.size() - 1);
    }
  }

  @Test
  public void offByDefault()
  {
    final IndoorMapsSettings settings = new IndoorMapsSettings(new Store());
    assertFalse(settings.isEnabled());
    assertFalse(settings.isConfigured());
    assertFalse(settings.isActive());
    assertEquals("", settings.getUrl());
    assertFalse(settings.hasToken());
    final Sink sink = new Sink();
    assertFalse(settings.apply(sink));
    assertEquals(false, sink.last()[0]);
    assertEquals("", sink.last()[1]);
    assertEquals("", sink.last()[2]);
    assertEquals("indoor_maps", IndoorMapsSettings.PREFS_NAME);
  }

  @Test
  public void activeOnlyWithSwitchUrlAndToken()
  {
    final IndoorMapsSettings settings = new IndoorMapsSettings(new Store());
    settings.setUrl("  https://indoor.example.org/ ");
    settings.setToken(" " + TOKEN + "  ");
    assertTrue(settings.isConfigured());
    assertFalse(settings.isActive());
    final Sink sink = new Sink();
    assertFalse(settings.apply(sink));
    // Configured but switched off: the core knows the server and keeps indoor maps off.
    assertEquals(false, sink.last()[0]);
    assertEquals("https://indoor.example.org/", sink.last()[1]);
    assertEquals(TOKEN, sink.last()[2]);

    settings.setEnabled(true);
    assertTrue(settings.isActive());
    assertTrue(settings.apply(sink));
    assertEquals(true, sink.last()[0]);
    assertEquals(TOKEN, sink.last()[2]);
  }

  @Test
  public void insecureOrInvalidUrlTurnsItOff()
  {
    final IndoorMapsSettings settings = new IndoorMapsSettings(new Store());
    settings.setEnabled(true);
    settings.setToken(TOKEN);
    for (String url : new String[] {"http://indoor.example.org", "ftp://x", "https://user@x", "https://x?a=b", "x", ""})
    {
      settings.setUrl(url);
      assertFalse(url, settings.isConfigured());
      final Sink sink = new Sink();
      assertFalse(url, settings.apply(sink));
      assertEquals(url, false, sink.last()[0]);
      assertEquals(url, "", sink.last()[1]);
      assertEquals(url, "", sink.last()[2]);
    }
    // Plain http is fine for local addresses (emulator, LAN).
    for (String url : new String[] {"http://10.0.2.2:18300", "http://192.168.1.5:18300", "http://localhost:18300"})
    {
      settings.setUrl(url);
      assertTrue(url, settings.isActive());
    }
  }

  @Test
  public void emptyTokenRemovesIt()
  {
    final Store store = new Store();
    final IndoorMapsSettings settings = new IndoorMapsSettings(store);
    settings.setToken(TOKEN);
    settings.setToken("   ");
    assertFalse(settings.hasToken());
    assertFalse(store.contains(IndoorMapsSettings.KEY_TOKEN));
    settings.setToken(TOKEN);
    settings.setToken(null);
    assertFalse(settings.hasToken());
  }
}
