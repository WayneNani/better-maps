package app.organicmaps.sdk.crowdtraffic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class RelayEndpointsTest
{
  @Test
  public void baseUrl()
  {
    RelayEndpoints e = RelayEndpoints.parse("http://10.0.2.2:18090");
    assertEquals("http://10.0.2.2:18090/relay", e.relayUrl);
    assertEquals("http://10.0.2.2:18090/ohttp-keys", e.keysUrl);
    e = RelayEndpoints.parse("  https://relay.example/  ");
    assertEquals("https://relay.example/relay", e.relayUrl);
    assertEquals("https://relay.example/ohttp-keys", e.keysUrl);
    e = RelayEndpoints.parse("https://relay.example/crowd/");
    assertEquals("https://relay.example/crowd/relay", e.relayUrl);
    assertEquals("https://relay.example/crowd/ohttp-keys", e.keysUrl);
  }

  @Test
  public void relayResourceUrl()
  {
    final RelayEndpoints e = RelayEndpoints.parse("HTTPS://ohttp.example.net:8443/v1/crowd-traffic");
    assertEquals("https://ohttp.example.net:8443/v1/crowd-traffic", e.relayUrl);
    assertEquals("https://ohttp.example.net:8443/v1/ohttp-keys", e.keysUrl);
  }

  @Test
  public void invalid()
  {
    assertNull(RelayEndpoints.parse(null));
    assertNull(RelayEndpoints.parse(""));
    assertNull(RelayEndpoints.parse("   "));
    assertNull(RelayEndpoints.parse("10.0.2.2:8090"));
    assertNull(RelayEndpoints.parse("ftp://relay.example/"));
    assertNull(RelayEndpoints.parse("http:///relay"));
    assertNull(RelayEndpoints.parse("http://relay.example/relay?id=1"));
    assertNull(RelayEndpoints.parse("http://user:pw@relay.example/"));
    assertNull(RelayEndpoints.parse("http://relay.example/#x"));
    assertNull(RelayEndpoints.parse("http://exa mple/"));
  }
}
