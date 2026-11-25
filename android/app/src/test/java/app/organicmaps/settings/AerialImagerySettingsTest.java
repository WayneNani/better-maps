package app.organicmaps.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.maplayer.aerial.AerialImageryLayer;
import app.organicmaps.transit.KeyValueStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class AerialImagerySettingsTest
{
  private static final String TOKEN = "Zm9vYmFyYmF6cXV4MDEyMzQ1Njc4OWFiY2RlZmdoaWo";

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
  private static final class Sink implements AerialImagerySettings.CoreSink
  {
    final List<Object[]> mCalls = new ArrayList<>();

    @Override
    public void setAerialImagery(boolean enabled, @NonNull String url, @NonNull String token)
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
    final AerialImagerySettings settings = new AerialImagerySettings(new Store());
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
  }

  @Test
  public void sharesTheSwitchWithTheLayersMenu()
  {
    // The layers menu (sdk) and the settings use the same preferences file and key.
    assertEquals(AerialImageryLayer.PREFS_NAME, AerialImagerySettings.PREFS_NAME);
    assertEquals(AerialImageryLayer.KEY_ENABLED, AerialImagerySettings.KEY_ENABLED);
    assertEquals("aerial_imagery", AerialImagerySettings.PREFS_NAME);
  }

  @Test
  public void activeOnlyWithSwitchUrlAndToken()
  {
    final Store store = new Store();
    final AerialImagerySettings settings = new AerialImagerySettings(store);
    settings.setEnabled(true);
    assertFalse(settings.isActive());
    settings.setUrl("  https://luftbild.example.org/  ");
    assertEquals("https://luftbild.example.org/", settings.getUrl());
    assertFalse(settings.isActive());
    settings.setToken("  " + TOKEN + " ");
    assertTrue(settings.hasToken());
    assertEquals(TOKEN, settings.getToken());
    assertTrue(settings.isConfigured());
    assertTrue(settings.isActive());

    final Sink sink = new Sink();
    assertTrue(settings.apply(sink));
    assertEquals(true, sink.last()[0]);
    assertEquals("https://luftbild.example.org/", sink.last()[1]);
    assertEquals(TOKEN, sink.last()[2]);

    // Layer switched off in the menu: the server stays known to the core, the layer is off.
    settings.setEnabled(false);
    assertFalse(settings.apply(sink));
    assertEquals(false, sink.last()[0]);
    assertEquals(TOKEN, sink.last()[2]);
  }

  @Test
  public void insecureOrInvalidUrlTurnsItOff()
  {
    final AerialImagerySettings settings = new AerialImagerySettings(new Store());
    settings.setEnabled(true);
    settings.setToken(TOKEN);
    for (String url : new String[] {"http://luftbild.example.org", "ftp://x", "https://user@x", "https://x?a=b", "x"})
    {
      settings.setUrl(url);
      assertFalse(url, settings.isConfigured());
      final Sink sink = new Sink();
      assertFalse(url, settings.apply(sink));
      assertEquals(url, "", sink.last()[1]);
      assertEquals(url, "", sink.last()[2]);
    }
    // Plain http is fine for local addresses (emulator, LAN).
    for (String url : new String[] {"http://10.0.2.2:18200", "http://192.168.1.5:18200", "http://localhost:18200"})
    {
      settings.setUrl(url);
      assertTrue(url, settings.isActive());
    }
  }

  @Test
  public void emptyTokenRemovesIt()
  {
    final Store store = new Store();
    final AerialImagerySettings settings = new AerialImagerySettings(store);
    settings.setToken(TOKEN);
    settings.setToken("   ");
    assertFalse(settings.hasToken());
    assertFalse(store.contains(AerialImagerySettings.KEY_TOKEN));
    settings.setToken(TOKEN);
    settings.setToken(null);
    assertFalse(settings.hasToken());
  }

  @Test
  public void baseUrl()
  {
    assertEquals("https://x", AerialImagerySettings.baseUrl("https://x"));
    assertEquals("https://x", AerialImagerySettings.baseUrl(" https://x// "));
    assertEquals("http://10.0.2.2:18200/p", AerialImagerySettings.baseUrl("http://10.0.2.2:18200/p/"));
  }
}
