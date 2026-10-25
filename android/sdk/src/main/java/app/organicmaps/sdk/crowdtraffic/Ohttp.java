package app.organicmaps.sdk.crowdtraffic;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.hpke.HPKE;
import org.bouncycastle.crypto.hpke.HPKEContext;
import org.bouncycastle.crypto.hpke.HPKEContextWithEncapsulation;
import org.bouncycastle.crypto.modes.AEADCipher;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * Oblivious HTTP (RFC 9458) client side: key configurations, request encapsulation and response
 * decapsulation.
 * <p>
 * This is a thin framing layer without own cryptography: HPKE (RFC 9180), HKDF and the AEADs come from
 * Bouncy Castle. Supported: DHKEM(X25519, HKDF-SHA256) with HKDF-SHA256 and AES-128-GCM or
 * ChaCha20-Poly1305, the algorithms of the crowd-traffic gateway (docs/CROWD_TRAFFIC_PRIVACY.md, section 8).
 * Checked against the example of RFC 9458, appendix A, and vectors of the Go gateway.
 */
public final class Ohttp
{
  public static final String CONTENT_TYPE_KEYS = "application/ohttp-keys";
  public static final String CONTENT_TYPE_REQUEST = "message/ohttp-req";
  public static final String CONTENT_TYPE_RESPONSE = "message/ohttp-res";

  static final int KEM_X25519_HKDF_SHA256 = 0x0020;
  static final int KDF_HKDF_SHA256 = 0x0001;
  static final int AEAD_AES_128_GCM = 0x0001;
  static final int AEAD_CHACHA20_POLY1305 = 0x0003;

  private static final int X25519_PUBLIC_KEY_SIZE = 32;
  private static final int AEAD_NONCE_SIZE = 12;
  private static final int AEAD_TAG_SIZE = 16;
  private static final byte[] LABEL_REQUEST = "message/bhttp request".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] LABEL_RESPONSE = "message/bhttp response".getBytes(StandardCharsets.US_ASCII);

  private Ohttp() {}

  /** Malformed or unsupported OHTTP data, or a failed decryption. */
  public static final class OhttpException extends Exception
  {
    public OhttpException(@NonNull String message)
    {
      super(message);
    }
  }

  /** A gateway key configuration with the symmetric algorithms chosen by this client. */
  public static final class KeyConfig
  {
    public final int keyId;
    public final int kemId;
    public final int kdfId;
    public final int aeadId;
    @NonNull
    final byte[] publicKey;

    KeyConfig(int keyId, int kemId, int kdfId, int aeadId, @NonNull byte[] publicKey)
    {
      this.keyId = keyId;
      this.kemId = kemId;
      this.kdfId = kdfId;
      this.aeadId = aeadId;
      this.publicKey = publicKey;
    }
  }

  private static int u16(@NonNull byte[] b, int off)
  {
    return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
  }

  static boolean isSupportedAead(int aead)
  {
    return aead == AEAD_AES_128_GCM || aead == AEAD_CHACHA20_POLY1305;
  }

  /**
   * Parses an application/ohttp-keys list (RFC 9458, section 3.2) and returns the first configuration with
   * DHKEM(X25519, HKDF-SHA256) and a supported KDF/AEAD pair. Configurations with other KEMs are skipped. A
   * malformed list is rejected as a whole.
   */
  @NonNull
  public static KeyConfig selectKeyConfig(@NonNull byte[] keys) throws OhttpException
  {
    KeyConfig selected = null;
    int off = 0;
    if (keys.length == 0)
      throw new OhttpException("empty key configuration list");
    while (off < keys.length)
    {
      if (keys.length - off < 2)
        throw new OhttpException("truncated key configuration list");
      final int len = u16(keys, off);
      off += 2;
      if (len > keys.length - off || len < 3)
        throw new OhttpException("truncated key configuration");
      final int end = off + len;
      final int keyId = keys[off] & 0xff;
      final int kem = u16(keys, off + 1);
      int p = off + 3;
      off = end;
      if (kem != KEM_X25519_HKDF_SHA256)
        continue; // A KEM we do not implement; the length prefix lets us skip it.
      if (end - p < X25519_PUBLIC_KEY_SIZE + 2)
        throw new OhttpException("key configuration too short");
      final byte[] pk = new byte[X25519_PUBLIC_KEY_SIZE];
      System.arraycopy(keys, p, pk, 0, pk.length);
      p += pk.length;
      final int symLen = u16(keys, p);
      p += 2;
      if (symLen == 0 || symLen % 4 != 0 || symLen != end - p)
        throw new OhttpException("invalid symmetric algorithms length");
      for (int i = p; i < end && selected == null; i += 4)
      {
        final int kdf = u16(keys, i);
        final int aead = u16(keys, i + 2);
        if (kdf == KDF_HKDF_SHA256 && isSupportedAead(aead))
          selected = new KeyConfig(keyId, kem, kdf, aead, pk);
      }
      // Keep parsing: the whole list must be well-formed.
    }
    if (selected == null)
      throw new OhttpException("no supported key configuration");
    return selected;
  }

  /** An encapsulated request together with the state needed to decrypt its response. */
  public static final class EncapsulatedRequest
  {
    @NonNull
    public final byte[] message;
    @NonNull
    final HPKEContext context;
    @NonNull
    final byte[] enc;
    final int aeadId;

    EncapsulatedRequest(@NonNull byte[] message, @NonNull HPKEContext context, @NonNull byte[] enc, int aeadId)
    {
      this.message = message;
      this.context = context;
      this.enc = enc;
      this.aeadId = aeadId;
    }
  }

  @NonNull
  private static byte[] header(@NonNull KeyConfig cfg)
  {
    return new byte[] {(byte) cfg.keyId,          (byte) (cfg.kemId >> 8), (byte) cfg.kemId,
                       (byte) (cfg.kdfId >> 8),   (byte) cfg.kdfId,        (byte) (cfg.aeadId >> 8),
                       (byte) cfg.aeadId};
  }

  /** Encrypts a binary HTTP request for the gateway (RFC 9458, section 4.3), with a fresh ephemeral key. */
  @NonNull
  public static EncapsulatedRequest encapsulateRequest(@NonNull KeyConfig cfg, @NonNull byte[] request)
      throws OhttpException
  {
    return encapsulateRequest(cfg, request, null);
  }

  /** As above; tests pass a fixed ephemeral key pair to reproduce test vectors. */
  @NonNull
  static EncapsulatedRequest encapsulateRequest(@NonNull KeyConfig cfg, @NonNull byte[] request,
                                                @Nullable AsymmetricCipherKeyPair ephemeral) throws OhttpException
  {
    final byte[] hdr = header(cfg);
    final byte[] info = new byte[LABEL_REQUEST.length + 1 + hdr.length];
    System.arraycopy(LABEL_REQUEST, 0, info, 0, LABEL_REQUEST.length);
    System.arraycopy(hdr, 0, info, LABEL_REQUEST.length + 1, hdr.length);
    try
    {
      final HPKE hpke = new HPKE(HPKE.mode_base, (short) cfg.kemId, (short) cfg.kdfId, (short) cfg.aeadId);
      final AsymmetricKeyParameter pkR = hpke.deserializePublicKey(cfg.publicKey);
      final HPKEContextWithEncapsulation ctx =
          ephemeral == null ? hpke.setupBaseS(pkR, info) : hpke.setupBaseS(pkR, info, ephemeral);
      final byte[] enc = ctx.getEncapsulation();
      final byte[] ct = ctx.seal(new byte[0], request);
      final byte[] out = new byte[hdr.length + enc.length + ct.length];
      System.arraycopy(hdr, 0, out, 0, hdr.length);
      System.arraycopy(enc, 0, out, hdr.length, enc.length);
      System.arraycopy(ct, 0, out, hdr.length + enc.length, ct.length);
      return new EncapsulatedRequest(out, ctx, enc, cfg.aeadId);
    }
    catch (InvalidCipherTextException | IllegalArgumentException e)
    {
      throw new OhttpException("encapsulation failed: " + e.getMessage());
    }
  }

  private static int keySize(int aead)
  {
    return aead == AEAD_AES_128_GCM ? 16 : 32;
  }

  @NonNull
  private static byte[] hkdf(@NonNull byte[] secret, @NonNull byte[] salt, @NonNull String info, int length)
  {
    final HKDFBytesGenerator g = new HKDFBytesGenerator(new SHA256Digest());
    g.init(new HKDFParameters(secret, salt, info.getBytes(StandardCharsets.US_ASCII)));
    final byte[] out = new byte[length];
    g.generateBytes(out, 0, length);
    return out;
  }

  /** Decrypts the encapsulated response to a request (RFC 9458, section 4.4). */
  @NonNull
  public static byte[] decapsulateResponse(@NonNull EncapsulatedRequest request, @NonNull byte[] response)
      throws OhttpException
  {
    final int nk = keySize(request.aeadId);
    final int nonceLen = Math.max(nk, AEAD_NONCE_SIZE);
    if (response.length < nonceLen + AEAD_TAG_SIZE)
      throw new OhttpException("response too short");
    final byte[] secret = request.context.export(LABEL_RESPONSE, nk);
    final byte[] salt = new byte[request.enc.length + nonceLen];
    System.arraycopy(request.enc, 0, salt, 0, request.enc.length);
    System.arraycopy(response, 0, salt, request.enc.length, nonceLen);
    final byte[] key = hkdf(secret, salt, "key", nk);
    final byte[] nonce = hkdf(secret, salt, "nonce", AEAD_NONCE_SIZE);

    final AEADCipher cipher = request.aeadId == AEAD_AES_128_GCM ? GCMBlockCipher.newInstance(AESEngine.newInstance())
                                                                 : new ChaCha20Poly1305();
    cipher.init(false, new AEADParameters(new KeyParameter(key), AEAD_TAG_SIZE * 8, nonce));
    final int ctLen = response.length - nonceLen;
    final byte[] out = new byte[cipher.getOutputSize(ctLen)];
    try
    {
      int n = cipher.processBytes(response, nonceLen, ctLen, out, 0);
      n += cipher.doFinal(out, n);
      if (n == out.length)
        return out;
      final byte[] res = new byte[n];
      System.arraycopy(out, 0, res, 0, n);
      return res;
    }
    catch (InvalidCipherTextException | IllegalStateException e)
    {
      throw new OhttpException("cannot decrypt response");
    }
  }
}
