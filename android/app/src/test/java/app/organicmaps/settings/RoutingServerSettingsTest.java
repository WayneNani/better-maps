package app.organicmaps.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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

public class RoutingServerSettingsTest
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
  private static final class Sink implements RoutingServerSettings.CoreSink
  {
    final List<String[]> mCalls = new ArrayList<>();

    @Override
    public void setRoutingServer(@NonNull String url, @NonNull String token)
    {
      mCalls.add(new String[] {url, token});
    }

    String[] last()
    {
      return mCalls.get(mCalls.size() - 1);
    }
  }

  @Test
  public void offByDefault()
  {
    final RoutingServerSettings settings = new RoutingServerSettings(new Store());
    assertFalse(settings.isEnabled());
    assertFalse(settings.isActive());
    assertEquals("", settings.getUrl());
    assertFalse(settings.hasToken());
    final Sink sink = new Sink();
    assertFalse(settings.apply(sink));
    assertEquals("", sink.last()[0]);
    assertEquals("", sink.last()[1]);
  }

  @Test
  public void activeNeedsSwitchUrlAndToken()
  {
    final Store store = new Store();
    final RoutingServerSettings settings = new RoutingServerSettings(store);
    final Sink sink = new Sink();

    settings.setUrl("  https://route.example.org  ");
    settings.setToken(" " + TOKEN + "\n");
    assertFalse("switch off", settings.apply(sink));
    assertEquals("", sink.last()[0]);

    settings.setEnabled(true);
    assertTrue(settings.apply(sink));
    assertEquals("https://route.example.org", sink.last()[0]);
    assertEquals(TOKEN, sink.last()[1]);
    assertEquals(TOKEN, store.mValues.get(RoutingServerSettings.KEY_TOKEN));

    // Without a token (empty input removes it) or with a bad URL: off again.
    settings.setToken("   ");
    assertFalse(store.mValues.containsKey(RoutingServerSettings.KEY_TOKEN));
    assertFalse(settings.apply(sink));
    assertEquals("", sink.last()[1]);

    settings.setToken(TOKEN);
    settings.setUrl("http://route.example.org");
    assertFalse(settings.apply(sink));
    assertEquals("", sink.last()[0]);

    settings.setUrl("http://10.0.2.2:18100");
    assertTrue(settings.apply(sink));
    assertEquals("http://10.0.2.2:18100", sink.last()[0]);

    settings.setEnabled(false);
    assertFalse(settings.apply(sink));
  }

  @Test
  public void readsWhatThePreferencesWrote()
  {
    // The preference screen writes the switch and the URL with these keys into the same file.
    final Store store = new Store();
    store.putBoolean(RoutingServerSettings.KEY_ENABLED, true);
    store.putString(RoutingServerSettings.KEY_URL, "https://route.example.org/bm/");
    store.putString(RoutingServerSettings.KEY_TOKEN, TOKEN);
    final RoutingServerSettings settings = new RoutingServerSettings(store);
    assertTrue(settings.isActive());
    assertEquals("https://route.example.org/bm/", settings.getUrl());
  }

  @Test
  public void checkUrl()
  {
    assertNull(RoutingServerSettings.checkUrl("https://route.example.org"));
    assertNull(RoutingServerSettings.checkUrl("HTTPS://route.example.org:8443/bm/v1/route"));
    assertNull(RoutingServerSettings.checkUrl("http://localhost:18100"));
    assertNull(RoutingServerSettings.checkUrl("http://10.0.2.2:18100"));
    assertNull(RoutingServerSettings.checkUrl("http://192.168.178.20:18100"));
    assertNull(RoutingServerSettings.checkUrl("http://172.20.0.5"));
    assertNull(RoutingServerSettings.checkUrl("http://homeserver.local:18100"));

    assertEquals(RoutingServerSettings.UrlProblem.EMPTY, RoutingServerSettings.checkUrl(null));
    assertEquals(RoutingServerSettings.UrlProblem.EMPTY, RoutingServerSettings.checkUrl("  "));
    assertEquals(RoutingServerSettings.UrlProblem.INSECURE, RoutingServerSettings.checkUrl("http://route.example.org"));
    assertEquals(RoutingServerSettings.UrlProblem.INSECURE, RoutingServerSettings.checkUrl("http://8.8.8.8"));
    assertEquals(RoutingServerSettings.UrlProblem.INSECURE, RoutingServerSettings.checkUrl("http://172.32.0.1"));
    // Not an IPv4 address and no host name either.
    assertEquals(RoutingServerSettings.UrlProblem.INVALID, RoutingServerSettings.checkUrl("http://192.168.1.300"));
    for (String bad : new String[] {"route.example.org", "ftp://route.example.org", "https://", "https:///v1",
                                    "https://user:pw@route.example.org", "https://route.example.org/?token=x",
                                    "https://route.example.org/#x", "https://route example.org"})
      assertEquals(bad, RoutingServerSettings.UrlProblem.INVALID, RoutingServerSettings.checkUrl(bad));
  }
}
