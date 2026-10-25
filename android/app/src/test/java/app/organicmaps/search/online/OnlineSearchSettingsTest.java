package app.organicmaps.search.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OnlineSearchSettingsTest
{
  @Test
  public void defaultServer()
  {
    final OnlineSearchSettings settings = new OnlineSearchSettings(new MapStore());
    assertEquals("https://photon.komoot.io", settings.getServerUrl());
    assertTrue(settings.isDefaultServer());
  }

  @Test
  public void ownServer()
  {
    final OnlineSearchSettings settings = new OnlineSearchSettings(
        new MapStore().with(OnlineSearchSettings.KEY_SERVER_URL, " http://192.168.1.5:2322/api/ "));
    assertEquals("http://192.168.1.5:2322", settings.getServerUrl());
    assertFalse(settings.isDefaultServer());
  }

  @Test
  public void invalidServerFallsBackToDefault()
  {
    for (String url : new String[] {"", "  ", "photon.example.org", "ftp://example.org", "https://", "https:///x",
                                    "https://exa mple.org"})
    {
      final OnlineSearchSettings settings =
          new OnlineSearchSettings(new MapStore().with(OnlineSearchSettings.KEY_SERVER_URL, url));
      assertEquals(url, "https://photon.komoot.io", settings.getServerUrl());
    }
  }

  @Test
  public void validation()
  {
    assertTrue(OnlineSearchSettings.isValidServerUrl("https://photon.example.org"));
    assertTrue(OnlineSearchSettings.isValidServerUrl("HTTP://localhost:2322"));
    assertFalse(OnlineSearchSettings.isValidServerUrl(null));
    assertFalse(OnlineSearchSettings.isValidServerUrl("localhost:2322"));
  }

  @Test
  public void normalization()
  {
    assertEquals("https://example.org/photon",
                 OnlineSearchSettings.normalizeServerUrl("https://example.org/photon/api//"));
    assertEquals("https://example.org", OnlineSearchSettings.normalizeServerUrl("https://example.org///"));
  }
}
