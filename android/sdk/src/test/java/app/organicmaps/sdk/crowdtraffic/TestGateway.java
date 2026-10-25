package app.organicmaps.sdk.crowdtraffic;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.hpke.HPKE;
import org.bouncycastle.crypto.hpke.HPKEContext;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * Minimal OHTTP gateway for the tests (receiver side of RFC 9458 with Bouncy Castle, ChaCha20-Poly1305 only).
 * The client side is checked against the Go gateway with vectors and in tools/better-maps/crowd-e2e.sh.
 */
final class TestGateway
{
  final int keyId;
  final HPKE hpke = new HPKE(HPKE.mode_base, (short) 0x20, (short) 1, (short) 3);
  final AsymmetricCipherKeyPair keyPair;

  TestGateway(int keyId, byte seed)
  {
    this.keyId = keyId;
    final byte[] ikm = new byte[32];
    java.util.Arrays.fill(ikm, seed);
    keyPair = hpke.deriveKeyPair(ikm);
  }

  byte[] keyConfigs()
  {
    final byte[] pk = hpke.serializePublicKey(keyPair.getPublic());
    final ByteArrayOutputStream cfg = new ByteArrayOutputStream();
    cfg.write(keyId);
    cfg.write(0);
    cfg.write(0x20);
    cfg.write(pk, 0, pk.length);
    cfg.write(0);
    cfg.write(4);
    cfg.write(new byte[] {0, 1, 0, 3}, 0, 4);
    final byte[] c = cfg.toByteArray();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(c.length >> 8);
    out.write(c.length);
    out.write(c, 0, c.length);
    return out.toByteArray();
  }

  /** The decrypted request and the state to encrypt the response. */
  static final class Received
  {
    byte[] request;
    HPKEContext context;
    byte[] enc;
  }

  /** Returns null if the key identifier is unknown. */
  Received open(byte[] message) throws Exception
  {
    if ((message[0] & 0xff) != keyId)
      return null;
    final byte[] hdr = java.util.Arrays.copyOfRange(message, 0, 7);
    final byte[] enc = java.util.Arrays.copyOfRange(message, 7, 39);
    final byte[] ct = java.util.Arrays.copyOfRange(message, 39, message.length);
    final byte[] label = "message/bhttp request".getBytes(StandardCharsets.US_ASCII);
    final byte[] info = new byte[label.length + 1 + hdr.length];
    System.arraycopy(label, 0, info, 0, label.length);
    System.arraycopy(hdr, 0, info, label.length + 1, hdr.length);
    final Received r = new Received();
    r.context = hpke.setupBaseR(enc, keyPair, info);
    r.request = r.context.open(new byte[0], ct);
    r.enc = enc;
    return r;
  }

  static byte[] seal(Received r, byte[] response) throws Exception
  {
    final byte[] nonce = new byte[32];
    new SecureRandom().nextBytes(nonce);
    final byte[] secret = r.context.export("message/bhttp response".getBytes(StandardCharsets.US_ASCII), 32);
    final byte[] salt = new byte[r.enc.length + nonce.length];
    System.arraycopy(r.enc, 0, salt, 0, r.enc.length);
    System.arraycopy(nonce, 0, salt, r.enc.length, nonce.length);
    final byte[] key = hkdf(secret, salt, "key", 32);
    final byte[] aeadNonce = hkdf(secret, salt, "nonce", 12);
    final ChaCha20Poly1305 aead = new ChaCha20Poly1305();
    aead.init(true, new AEADParameters(new KeyParameter(key), 128, aeadNonce));
    final byte[] ct = new byte[aead.getOutputSize(response.length)];
    final int n = aead.processBytes(response, 0, response.length, ct, 0);
    aead.doFinal(ct, n);
    final byte[] out = new byte[nonce.length + ct.length];
    System.arraycopy(nonce, 0, out, 0, nonce.length);
    System.arraycopy(ct, 0, out, nonce.length, ct.length);
    return out;
  }

  private static byte[] hkdf(byte[] secret, byte[] salt, String info, int len)
  {
    final HKDFBytesGenerator g = new HKDFBytesGenerator(new SHA256Digest());
    g.init(new HKDFParameters(secret, salt, info.getBytes(StandardCharsets.US_ASCII)));
    final byte[] out = new byte[len];
    g.generateBytes(out, 0, len);
    return out;
  }

  /** Binary HTTP response with a status and an optional problem body. */
  static byte[] bhttpResponse(int status, String problemJson)
  {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(1);
    out.write(0x40 | (status >> 8));
    out.write(status & 0xff);
    if (problemJson == null)
    {
      out.write(0);
      out.write(0);
      out.write(0);
      return out.toByteArray();
    }
    final byte[] name = "content-type".getBytes(StandardCharsets.US_ASCII);
    final byte[] value = "application/problem+json".getBytes(StandardCharsets.US_ASCII);
    out.write(1 + name.length + 1 + value.length);
    out.write(name.length);
    out.write(name, 0, name.length);
    out.write(value.length);
    out.write(value, 0, value.length);
    final byte[] body = problemJson.getBytes(StandardCharsets.UTF_8);
    out.write(0x40 | (body.length >> 8));
    out.write(body.length & 0xff);
    out.write(body, 0, body.length);
    out.write(0);
    return out.toByteArray();
  }

  static byte[] hex(String s)
  {
    final byte[] b = new byte[s.length() / 2];
    for (int i = 0; i < b.length; ++i)
      b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
    return b;
  }

  static String toHex(byte[] b)
  {
    final StringBuilder sb = new StringBuilder();
    for (byte x : b)
      sb.append(String.format("%02x", x & 0xff));
    return sb.toString();
  }
}
