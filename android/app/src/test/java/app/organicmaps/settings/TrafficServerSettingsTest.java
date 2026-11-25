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

  private static final String DEFAULT_URL = "https://api.dev.traffxml.org";
  private static final String OWN_URL = "https://traff.example.org/";

  private static TrafficServerSettings settings(@NonNull Store store)
  {
    return new TrafficServerSettings(store, DEFAULT_URL);
  }

  @Test
  public void noTokenByDefault()
  {
    final TrafficServerSettings settings = settings(new Store());
    assertFalse(settings.hasToken());
    assertEquals("", settings.getToken());
    final Sink sink = new Sink();
    assertFalse(settings.apply(OWN_URL, sink));
    // The core is told explicitly that there is no token.
    assertEquals(1, sink.mTokens.size());
    assertEquals("", sink.last());
    assertEquals("traffic_server", TrafficServerSettings.PREFS_NAME);
  }

  @Test
  public void tokenIsStoredTrimmedWithItsOriginAndSentThere()
  {
    final Store store = new Store();
    final TrafficServerSettings settings = settings(store);
    assertTrue(settings.setToken("  " + TOKEN + " ", " " + OWN_URL + " "));
    assertTrue(settings.hasToken());
    assertEquals(TOKEN, store.getString(TrafficServerSettings.KEY_TOKEN, null));
    assertEquals("https://traff.example.org:443", settings.getTokenOrigin());
    final Sink sink = new Sink();
    assertTrue(settings.apply(OWN_URL, sink));
    assertEquals(TOKEN, sink.last());
    // Same origin, other path or explicit default port: still the same server.
    for (String url : new String[] {"https://traff.example.org", "https://TRAFF.example.org:443/traff"})
    {
      assertTrue(url, settings.apply(url, sink));
      assertEquals(url, TOKEN, sink.last());
    }
  }

  @Test
  public void tokenIsNeverSentToTheDefaultOrPublicService()
  {
    final TrafficServerSettings settings = settings(new Store());
    // Entering a token while the default service is set is refused.
    for (String url : new String[] {DEFAULT_URL, DEFAULT_URL + "/", "https://API.dev.traffxml.org:443/x"})
    {
      assertFalse(url, settings.acceptsTokenFor(url));
      assertFalse(url, settings.setToken(TOKEN, url));
      assertFalse(url, settings.hasToken());
    }
    // A token entered for the own server does not go to the default service either.
    assertTrue(settings.setToken(TOKEN, OWN_URL));
    final Sink sink = new Sink();
    assertFalse(settings.apply(DEFAULT_URL, sink));
    assertEquals("", sink.last());
    assertEquals("", settings.tokenFor(DEFAULT_URL + "/"));
    // Also when the core's default is another URL, the known public service never gets it.
    final TrafficServerSettings other = new TrafficServerSettings(new Store(), "https://traff.default.example");
    assertFalse(other.setToken(TOKEN, "https://api.dev.traffxml.org/"));
    assertFalse(other.setToken(TOKEN, "https://traff.default.example/"));
  }

  @Test
  public void tokenIsNotSentToAnotherOrigin()
  {
    final TrafficServerSettings settings = settings(new Store());
    assertTrue(settings.setToken(TOKEN, OWN_URL));
    for (String url : new String[] {"https://other.example.org/", "https://traff.example.org:8443/",
                                    "http://traff.example.org/", "https://evil.traff.example.org/",
                                    "http://10.0.2.2:18400/", "", null})
    {
      final Sink sink = new Sink();
      assertFalse(String.valueOf(url), settings.apply(url, sink));
      assertEquals(String.valueOf(url), "", sink.last());
    }
  }

  @Test
  public void originChangeClearsTheToken()
  {
    final Store store = new Store();
    final TrafficServerSettings settings = settings(store);
    assertTrue(settings.setToken(TOKEN, OWN_URL));
    // Another path on the same server keeps it.
    assertFalse(settings.onUrlChanged("https://traff.example.org/traff"));
    assertTrue(settings.hasToken());
    // Another server drops it, also from the store, and nothing is sent anymore, not even back to the old server.
    assertTrue(settings.onUrlChanged("https://other.example.org/"));
    assertFalse(settings.hasToken());
    assertFalse(store.contains(TrafficServerSettings.KEY_TOKEN));
    assertFalse(store.contains(TrafficServerSettings.KEY_TOKEN_ORIGIN));
    final Sink sink = new Sink();
    assertFalse(settings.apply(OWN_URL, sink));
    assertEquals("", sink.last());
    // Switching to the default service or an empty URL drops it as well; without a token nothing is reported.
    assertTrue(settings.setToken(TOKEN, OWN_URL));
    assertTrue(settings.onUrlChanged(DEFAULT_URL));
    assertFalse(settings.hasToken());
    assertTrue(settings.setToken(TOKEN, OWN_URL));
    assertTrue(settings.onUrlChanged(""));
    assertFalse(settings.onUrlChanged("https://third.example.org/"));
  }

  @Test
  public void localHttpServersWork()
  {
    final TrafficServerSettings settings = settings(new Store());
    for (String url : new String[] {"http://10.0.2.2:18400/", "http://192.168.1.5:18400", "http://localhost:18400/",
                                    "http://127.0.0.1:18400", "http://traff.local/"})
    {
      assertTrue(url, settings.setToken(TOKEN, url));
      final Sink sink = new Sink();
      assertTrue(url, settings.apply(url, sink));
      assertEquals(url, TOKEN, sink.last());
    }
    // Plain http to public hosts and broken URLs never get one.
    for (String url : new String[] {"http://traff.example.org/", "http://93.184.216.34:8080/", "ftp://traff.example.org",
                                    "https://user:pw@traff.example.org", "https://traff.example.org/?a=b",
                                    "traff.example.org", "", "   ", null})
    {
      assertFalse(String.valueOf(url), settings.acceptsTokenFor(url));
      assertEquals(String.valueOf(url), "", settings.tokenFor(url));
    }
  }

  @Test
  public void emptyInputClearsTheToken()
  {
    final Store store = new Store();
    final TrafficServerSettings settings = settings(store);
    assertTrue(settings.setToken(TOKEN, OWN_URL));
    assertTrue(settings.setToken("   ", OWN_URL));
    assertFalse(settings.hasToken());
    assertFalse(store.contains(TrafficServerSettings.KEY_TOKEN));
    assertFalse(store.contains(TrafficServerSettings.KEY_TOKEN_ORIGIN));
    assertTrue(settings.setToken(TOKEN, OWN_URL));
    // Clearing works whatever the URL is (also the default service).
    assertTrue(settings.setToken(null, DEFAULT_URL));
    assertFalse(settings.hasToken());
    final Sink sink = new Sink();
    assertFalse(settings.apply(OWN_URL, sink));
    assertEquals("", sink.last());
  }

  @Test
  public void tokenWithoutOriginIsNotSent()
  {
    // E.g. stored by an earlier build that did not bind tokens to a server.
    final Store store = new Store();
    store.putString(TrafficServerSettings.KEY_TOKEN, TOKEN);
    final TrafficServerSettings settings = settings(store);
    assertEquals("", settings.tokenFor(OWN_URL));
    assertTrue(settings.onUrlChanged(OWN_URL));
    assertFalse(settings.hasToken());
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
