package app.organicmaps.sdk.crowdtraffic;

import static app.organicmaps.sdk.crowdtraffic.TestGateway.hex;
import static app.organicmaps.sdk.crowdtraffic.TestGateway.toHex;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.hpke.HPKE;
import org.junit.Test;

public class OhttpTest
{
  // RFC 9458, appendix A.
  private static final String RFC_KEY_CONFIG = "01002031e1f05a740102115220e9af918f738674aec95f54db6e04eb705aae8e"
                                             + "79815500080001000100010003";
  private static final String RFC_EPHEMERAL_SK = "bc51d5e930bda26589890ac7032f70ad12e4ecb37abb1b65b1256c9c48999c73";
  private static final String RFC_EPHEMERAL_PK = "4b28f881333e7c164ffc499ad9796f877f4e1051ee6d31bad19dec96c208b472";
  private static final String RFC_REQUEST = "00034745540568747470730b6578616d706c652e636f6d012f";
  private static final String RFC_ENC_REQUEST = "010020000100014b28f881333e7c164ffc499ad9796f877f4e1051ee6d31bad1"
                                              + "9dec96c208b4726374e469135906992e1268c594d2a10c695d858c40a026e796"
                                              + "5e7d86b83dd440b2c0185204b4d63525";
  private static final String RFC_ENC_RESPONSE = "c789e7151fcba46158ca84b04464910d86f9013e404feea014e7be4a441f234f"
                                               + "857fbd";
  private static final String RFC_RESPONSE = "0140c8";

  private static byte[] withLength(String config)
  {
    final byte[] c = hex(config);
    final byte[] res = new byte[c.length + 2];
    res[0] = (byte) (c.length >> 8);
    res[1] = (byte) c.length;
    System.arraycopy(c, 0, res, 2, c.length);
    return res;
  }

  private static AsymmetricCipherKeyPair ephemeral(Ohttp.KeyConfig cfg, String sk, String pk)
  {
    final HPKE hpke = new HPKE(HPKE.mode_base, (short) cfg.kemId, (short) cfg.kdfId, (short) cfg.aeadId);
    return hpke.deserializePrivateKey(hex(sk), hex(pk));
  }

  @Test
  public void rfc9458Example() throws Exception
  {
    final Ohttp.KeyConfig cfg = Ohttp.selectKeyConfig(withLength(RFC_KEY_CONFIG));
    assertEquals(1, cfg.keyId);
    assertEquals(Ohttp.KEM_X25519_HKDF_SHA256, cfg.kemId);
    assertEquals(Ohttp.KDF_HKDF_SHA256, cfg.kdfId);
    // The first supported pair of the configuration: AES-128-GCM.
    assertEquals(Ohttp.AEAD_AES_128_GCM, cfg.aeadId);

    final Ohttp.EncapsulatedRequest req = Ohttp.encapsulateRequest(
        cfg, hex(RFC_REQUEST), ephemeral(cfg, RFC_EPHEMERAL_SK, RFC_EPHEMERAL_PK));
    assertEquals(RFC_ENC_REQUEST, toHex(req.message));
    assertEquals(RFC_RESPONSE, toHex(Ohttp.decapsulateResponse(req, hex(RFC_ENC_RESPONSE))));

    // A modified response is rejected.
    final byte[] bad = hex(RFC_ENC_RESPONSE);
    bad[bad.length - 1] ^= 1;
    assertThrows(Ohttp.OhttpException.class, () -> Ohttp.decapsulateResponse(req, bad));
    assertThrows(Ohttp.OhttpException.class, () -> Ohttp.decapsulateResponse(req, new byte[10]));
  }

  @Test
  public void goGatewayChaCha20Poly1305() throws Exception
  {
    final Ohttp.KeyConfig cfg = Ohttp.selectKeyConfig(hex(OhttpGoVectors.KEY_CONFIGS));
    assertEquals(7, cfg.keyId);
    assertEquals(Ohttp.AEAD_CHACHA20_POLY1305, cfg.aeadId);
    final Ohttp.EncapsulatedRequest req = Ohttp.encapsulateRequest(
        cfg, hex(OhttpGoVectors.REQUEST), ephemeral(cfg, OhttpGoVectors.EPHEMERAL_SK, OhttpGoVectors.EPHEMERAL_PK));
    assertEquals(OhttpGoVectors.ENCAPSULATED_REQUEST, toHex(req.message));
    final byte[] response = Ohttp.decapsulateResponse(req, hex(OhttpGoVectors.ENCAPSULATED_RESPONSE_ACCEPTED));
    assertEquals(OhttpGoVectors.RESPONSE_ACCEPTED, toHex(response));
    assertEquals(204, BinaryHttp.parseResponse(response).status);
  }

  @Test
  public void freshEphemeralKeysAndRoundTrip() throws Exception
  {
    final TestGateway gw = new TestGateway(9, (byte) 5);
    final Ohttp.KeyConfig cfg = Ohttp.selectKeyConfig(gw.keyConfigs());
    final byte[] request = hex(OhttpGoVectors.REQUEST);
    final Ohttp.EncapsulatedRequest a = Ohttp.encapsulateRequest(cfg, request);
    final Ohttp.EncapsulatedRequest b = Ohttp.encapsulateRequest(cfg, request);
    // Same size (padding), different ciphertexts (fresh ephemeral key per request).
    assertEquals(a.message.length, b.message.length);
    assertEquals(7 + 32 + 4096 + 16, a.message.length);
    assertEquals(false, toHex(a.message).equals(toHex(b.message)));

    final TestGateway.Received r = gw.open(a.message);
    assertArrayEquals(request, r.request);
    final byte[] resp = TestGateway.bhttpResponse(204, null);
    assertArrayEquals(resp, Ohttp.decapsulateResponse(a, TestGateway.seal(r, resp)));
    // The response to another request cannot be decrypted.
    assertThrows(Ohttp.OhttpException.class, () -> Ohttp.decapsulateResponse(b, TestGateway.seal(r, resp)));
    assertNull(new TestGateway(3, (byte) 5).open(a.message));
  }

  @Test
  public void keyConfigLists() throws Exception
  {
    final String x25519 = "0700201a239249ea74403babc01f32df9931a16f71ac8972c461d69fed15640e310639";
    // An unknown KEM (0x0010, P-256) before a supported configuration is skipped.
    final byte[] list = concat(withLength("0500100102030004" + "00010001"), withLength(x25519 + "000400010003"));
    assertEquals(7, Ohttp.selectKeyConfig(list).keyId);
    // Unsupported symmetric algorithms only.
    assertThrows(Ohttp.OhttpException.class, () -> Ohttp.selectKeyConfig(withLength(x25519 + "000400020001")));
    // First supported suite of a configuration with several.
    assertEquals(Ohttp.AEAD_CHACHA20_POLY1305,
                 Ohttp.selectKeyConfig(withLength(x25519 + "0008" + "00010002" + "00010003")).aeadId);
    // Malformed lists are rejected as a whole.
    assertThrows(Ohttp.OhttpException.class, () -> Ohttp.selectKeyConfig(new byte[0]));
    assertThrows(Ohttp.OhttpException.class, () -> Ohttp.selectKeyConfig(new byte[] {0}));
    assertThrows(Ohttp.OhttpException.class, () -> Ohttp.selectKeyConfig(hex("0030" + x25519)));
    assertThrows(Ohttp.OhttpException.class, () -> Ohttp.selectKeyConfig(withLength(x25519 + "0003000100")));
    assertThrows(Ohttp.OhttpException.class, () -> Ohttp.selectKeyConfig(withLength(x25519 + "0000")));
    assertThrows(Ohttp.OhttpException.class, () -> Ohttp.selectKeyConfig(withLength("070020aabb")));
    assertThrows(Ohttp.OhttpException.class,
                 () -> Ohttp.selectKeyConfig(concat(withLength(x25519 + "000400010003"), new byte[] {0, 9, 1})));
  }

  private static byte[] concat(byte[] a, byte[] b)
  {
    final byte[] r = new byte[a.length + b.length];
    System.arraycopy(a, 0, r, 0, a.length);
    System.arraycopy(b, 0, r, a.length, b.length);
    return r;
  }
}
