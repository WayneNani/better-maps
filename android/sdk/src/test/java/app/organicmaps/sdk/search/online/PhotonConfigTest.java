package app.organicmaps.sdk.search.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.Set;
import org.junit.Test;

public class PhotonConfigTest
{
  @Test
  public void defaults()
  {
    final PhotonConfig config = PhotonConfig.builder("ua").build();
    assertEquals("https://photon.komoot.io", config.getBaseUrl());
    assertEquals("ua", config.getUserAgent());
    assertEquals(Set.of("de", "en", "fr"), config.getLanguages());
    assertEquals(PhotonConfig.DEFAULT_CONNECT_TIMEOUT_MS, config.getConnectTimeoutMs());
    assertEquals(PhotonConfig.DEFAULT_READ_TIMEOUT_MS, config.getReadTimeoutMs());
    assertEquals(PhotonConfig.DEFAULT_CALL_TIMEOUT_MS, config.getCallTimeoutMs());
  }

  @Test
  public void baseUrlIsNormalized()
  {
    assertEquals("http://10.0.2.2:2322",
                 PhotonConfig.builder("ua").setBaseUrl(" http://10.0.2.2:2322/ ").build().getBaseUrl());
    assertEquals("https://example.org/photon",
                 PhotonConfig.builder("ua").setBaseUrl("https://example.org/photon/api/").build().getBaseUrl());
  }

  @Test
  public void invalidValues()
  {
    assertThrows(IllegalArgumentException.class, () -> PhotonConfig.builder(" "));
    assertThrows(IllegalArgumentException.class, () -> PhotonConfig.builder("ua").setBaseUrl("photon.komoot.io"));
    assertThrows(IllegalArgumentException.class, () -> PhotonConfig.builder("ua").setBaseUrl("ftp://example.org"));
    assertThrows(IllegalArgumentException.class, () -> PhotonConfig.builder("ua").setBaseUrl("https://"));
    assertThrows(IllegalArgumentException.class, () -> PhotonConfig.builder("ua").setReadTimeoutMs(0));
    assertThrows(IllegalArgumentException.class, () -> PhotonConfig.builder("ua").setConnectTimeoutMs(-1));
    assertThrows(IllegalArgumentException.class, () -> PhotonConfig.builder("ua").setCallTimeoutMs(0));
  }

  @Test
  public void languagesAreNormalized()
  {
    assertEquals(Set.of("it", "pl"),
                 PhotonConfig.builder("ua").setLanguages(Set.of("IT", "pl-PL", "default", "x")).build().getLanguages());
  }
}
