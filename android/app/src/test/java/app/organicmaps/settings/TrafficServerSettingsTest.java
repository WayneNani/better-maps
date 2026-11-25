package app.organicmaps.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.transit.KeyValueStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class TrafficServerSettingsTest
{
  private static final String TOKEN = "dHJhZmYtdG9rZW4tMDEyMzQ1Njc4OWFiY2RlZmdoaWpr";

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
  private static final class Sink implements TrafficServerSettings.CoreSink
  {
    final List<String> mTokens = new ArrayList<>();

    @Override
    public void setTrafficHttpToken(@NonNull String token)
    {
      mTokens.add(token);
    }

    String last()
    {
      return mTokens.get(mTokens.size() - 1);
    }
  }

  @Test
  public void noTokenByDefault()
  {
    final TrafficServerSettings settings = new TrafficServerSettings(new Store());
    assertFalse(settings.hasToken());
    assertEquals("", settings.getToken());
    final Sink sink = new Sink();
    assertFalse(settings.apply("https://traff.example.org/", sink));
    // The core is told explicitly that there is no token.
    assertEquals(1, sink.mTokens.size());
    assertEquals("", sink.last());
    assertEquals("traffic_server", TrafficServerSettings.PREFS_NAME);
  }

  @Test
  public void tokenIsStoredTrimmedAndSentToHttpsUrl()
  {
    final Store store = new Store();
    final TrafficServerSettings settings = new TrafficServerSettings(store);
    settings.setToken("  " + TOKEN + " ");
    assertTrue(settings.hasToken());
    assertEquals(TOKEN, store.getString(TrafficServerSettings.KEY_TOKEN, null));
    final Sink sink = new Sink();
    assertTrue(settings.apply(" https://traff.example.org/ ", sink));
    assertEquals(TOKEN, sink.last());
    assertTrue(settings.apply("https://traff.example.org:8443/traff", sink));
    assertEquals(TOKEN, sink.last());
  }

  @Test
  public void tokenNeverGoesOverPlainHttpToPublicHostsOrBadUrls()
  {
    final TrafficServerSettings settings = new TrafficServerSettings(new Store());
    settings.setToken(TOKEN);
    for (String url : new String[] {"http://traff.example.org/", "http://93.184.216.34:8080/", "ftp://traff.example.org",
                                    "https://user:pw@traff.example.org", "https://traff.example.org/?a=b",
                                    "traff.example.org", "", "   ", null})
    {
      final Sink sink = new Sink();
      assertFalse(String.valueOf(url), settings.apply(url, sink));
      assertEquals(String.valueOf(url), "", sink.last());
      assertEquals(String.valueOf(url), "", settings.tokenFor(url));
    }
    // Plain http is fine for local addresses (emulator, LAN, the machine itself).
    for (String url : new String[] {"http://10.0.2.2:18400/", "http://192.168.1.5:18400", "http://localhost:18400/",
                                    "http://127.0.0.1:18400", "http://traff.local/"})
    {
      final Sink sink = new Sink();
      assertTrue(url, settings.apply(url, sink));
      assertEquals(url, TOKEN, sink.last());
    }
  }

  @Test
  public void emptyTokenRemovesIt()
  {
    final Store store = new Store();
    final TrafficServerSettings settings = new TrafficServerSettings(store);
    settings.setToken(TOKEN);
    settings.setToken("   ");
    assertFalse(settings.hasToken());
    assertFalse(store.contains(TrafficServerSettings.KEY_TOKEN));
    settings.setToken(TOKEN);
    settings.setToken(null);
    assertFalse(settings.hasToken());
    final Sink sink = new Sink();
    assertFalse(settings.apply("https://traff.example.org/", sink));
    assertEquals("", sink.last());
  }

  /** Reads a resource file of the app module (the tests run in android/app). */
  @NonNull
  private static String readRes(@NonNull String path) throws IOException
  {
    for (String base : new String[] {"src/main/res/", "app/src/main/res/", "android/app/src/main/res/"})
    {
      final Path p = Paths.get(base + path);
      if (Files.exists(p))
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
    throw new IOException("not found: " + path + " (working directory " + Paths.get("").toAbsolutePath() + ")");
  }

  @Test
  public void tokenIsNotBackedUpAndThePreferenceKeepsNoCopy() throws IOException
  {
    // Backups contain settings.ini (with the TraFF URL) and bookmarks, but no SharedPreferences.
    for (String file : new String[] {"xml/backup_content.xml", "xml/backup_content_v31.xml"})
    {
      final String xml = readRes(file);
      assertFalse(file, xml.contains("sharedpref"));
      assertFalse(file, xml.contains(TrafficServerSettings.PREFS_NAME));
    }
    // The token preference in Settings → Traffic is not persistent: only TrafficServerSettings stores the token.
    final String prefs = readRes("xml/prefs_traffic.xml");
    final int at = prefs.indexOf("@string/pref_traffic_http_token");
    assertTrue(at > 0);
    final int end = prefs.indexOf("/>", at);
    assertTrue(prefs.substring(at, end).contains("android:persistent=\"false\""));
  }
}
