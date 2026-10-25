package app.organicmaps.sdk.crowdtraffic;

import static app.organicmaps.sdk.crowdtraffic.TestGateway.hex;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class BinaryHttpTest
{
  @Test
  public void goResponses()
  {
    final BinaryHttp.Response ok = BinaryHttp.parseResponse(hex(OhttpGoVectors.RESPONSE_ACCEPTED));
    assertEquals(204, ok.status);
    assertNull(ok.contentType);
    assertEquals(0, ok.body.length);

    final BinaryHttp.Response rejected = BinaryHttp.parseResponse(hex(OhttpGoVectors.RESPONSE_REJECTED));
    assertEquals(400, rejected.status);
    assertEquals("application/problem+json", rejected.contentType);
    final String body = new String(rejected.body, StandardCharsets.UTF_8);
    assertEquals(true, body.contains("window_late"));
  }

  @Test
  public void truncatedSectionsPaddingAndInformational()
  {
    // RFC 9292, section 5.1: status only (truncated sections), with padding.
    assertEquals(200, BinaryHttp.parseResponse(hex("0140c8")).status);
    assertEquals(200, BinaryHttp.parseResponse(hex("0140c8000000000000")).status);
    // A 103 informational response with a header section before the final 204.
    final BinaryHttp.Response r = BinaryHttp.parseResponse(hex("01" + "4067" + "00" + "40cc" + "00" + "00"));
    assertEquals(204, r.status);
    // Body without headers.
    final BinaryHttp.Response b = BinaryHttp.parseResponse(hex("01" + "40c8" + "00" + "026869" + "00"));
    assertEquals("hi", new String(b.body, StandardCharsets.US_ASCII));
    assertEquals(400, BinaryHttp.parseResponse(TestGateway.bhttpResponse(400, "{\"title\":\"x\"}")).status);
  }

  @Test
  public void malformed()
  {
    assertThrows(IllegalArgumentException.class, () -> BinaryHttp.parseResponse(new byte[0]));
    // A request, not a response.
    assertThrows(IllegalArgumentException.class, () -> BinaryHttp.parseResponse(hex("00")));
    assertThrows(IllegalArgumentException.class, () -> BinaryHttp.parseResponse(hex("01")));
    assertThrows(IllegalArgumentException.class, () -> BinaryHttp.parseResponse(hex("0140")));
    // Status out of range.
    assertThrows(IllegalArgumentException.class, () -> BinaryHttp.parseResponse(hex("0163")));
    assertThrows(IllegalArgumentException.class, () -> BinaryHttp.parseResponse(hex("014258")));
    // Content length beyond the input.
    assertThrows(IllegalArgumentException.class, () -> BinaryHttp.parseResponse(hex("0140c8" + "00" + "05aa")));
  }
}
