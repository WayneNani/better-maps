package app.organicmaps.sdk.transit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class TransitServiceConfigTest
{
  @Test
  public void defaults()
  {
    final TransitServiceConfig config = TransitServiceConfig.builder("test/1.0 (+https://example.org)").build();
    assertEquals("https://api.transitous.org", config.getBaseUrl());
    assertEquals("test/1.0 (+https://example.org)", config.getUserAgent());
    assertEquals(10_000, config.getConnectTimeoutMs());
    assertEquals(30_000, config.getReadTimeoutMs());
    assertEquals(45_000, config.getCallTimeoutMs());
  }

  @Test
  public void baseUrlIsNormalized()
  {
    assertEquals("https://motis.example.org",
                 TransitServiceConfig.builder("ua").setBaseUrl(" https://motis.example.org// ").build().getBaseUrl());
    assertEquals("http://10.0.2.2:8080",
                 TransitServiceConfig.builder("ua").setBaseUrl("http://10.0.2.2:8080/").build().getBaseUrl());
  }

  @Test
  public void customTimeouts()
  {
    final TransitServiceConfig config =
        TransitServiceConfig.builder("ua").setConnectTimeoutMs(1).setReadTimeoutMs(2).setCallTimeoutMs(3).build();
    assertEquals(1, config.getConnectTimeoutMs());
    assertEquals(2, config.getReadTimeoutMs());
    assertEquals(3, config.getCallTimeoutMs());
  }

  @Test
  public void rejectsInvalidValues()
  {
    assertThrows(IllegalArgumentException.class, () -> TransitServiceConfig.builder(" "));
    assertThrows(IllegalArgumentException.class, () -> TransitServiceConfig.builder("ua").setBaseUrl("ftp://x"));
    assertThrows(IllegalArgumentException.class, () -> TransitServiceConfig.builder("ua").setBaseUrl("api.org"));
    assertThrows(IllegalArgumentException.class, () -> TransitServiceConfig.builder("ua").setReadTimeoutMs(0));
  }
}
