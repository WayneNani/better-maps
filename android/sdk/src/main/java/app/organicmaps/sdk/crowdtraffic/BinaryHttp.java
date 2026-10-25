package app.organicmaps.sdk.crowdtraffic;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.nio.charset.StandardCharsets;

/**
 * Decoder for known-length Binary HTTP responses (RFC 9292), the payload of an OHTTP response. Requests are
 * encoded by the native client (libs/crowd_traffic/protocol.cpp).
 */
public final class BinaryHttp
{
  private BinaryHttp() {}

  /** A decoded response: status, content type and body (trailers are ignored). */
  public static final class Response
  {
    public final int status;
    @Nullable
    public final String contentType;
    @NonNull
    public final byte[] body;

    Response(int status, @Nullable String contentType, @NonNull byte[] body)
    {
      this.status = status;
      this.contentType = contentType;
      this.body = body;
    }
  }

  private static final class Reader
  {
    @NonNull
    private final byte[] mData;
    private int mOff;

    Reader(@NonNull byte[] data)
    {
      mData = data;
    }

    long varint()
    {
      if (mOff >= mData.length)
        throw new IllegalArgumentException("truncated varint");
      final int n = 1 << ((mData[mOff] & 0xff) >> 6);
      if (mOff + n > mData.length)
        throw new IllegalArgumentException("truncated varint");
      long v = mData[mOff] & 0x3f;
      for (int i = 1; i < n; ++i)
        v = (v << 8) | (mData[mOff + i] & 0xff);
      mOff += n;
      return v;
    }

    @NonNull
    byte[] bytes()
    {
      final long len = varint();
      if (len > mData.length - mOff)
        throw new IllegalArgumentException("length exceeds input");
      final byte[] res = new byte[(int) len];
      System.arraycopy(mData, mOff, res, 0, res.length);
      mOff += res.length;
      return res;
    }

    /** RFC 9292 allows zero padding and truncation of empty trailing sections. */
    boolean paddingOnly()
    {
      for (int i = mOff; i < mData.length; ++i)
        if (mData[i] != 0)
          return false;
      return true;
    }
  }

  /** Returns the value of the content-type field of a field section, or null. */
  @Nullable
  private static String contentType(@NonNull byte[] section)
  {
    final Reader r = new Reader(section);
    String res = null;
    while (r.mOff < section.length)
    {
      final String name = new String(r.bytes(), StandardCharsets.US_ASCII);
      final String value = new String(r.bytes(), StandardCharsets.ISO_8859_1);
      if (res == null && name.equals("content-type"))
        res = value;
    }
    return res;
  }

  /** Decodes a known-length response; informational (1xx) responses are skipped. */
  @NonNull
  public static Response parseResponse(@NonNull byte[] data)
  {
    final Reader r = new Reader(data);
    if (r.varint() != 1)
      throw new IllegalArgumentException("not a known-length response");
    long status;
    while (true)
    {
      status = r.varint();
      if (status < 100 || status > 599)
        throw new IllegalArgumentException("invalid status " + status);
      if (status >= 200)
        break;
      r.bytes(); // Header section of an informational response.
    }
    String type = null;
    byte[] body = new byte[0];
    if (!r.paddingOnly())
    {
      type = contentType(r.bytes());
      if (!r.paddingOnly())
        body = r.bytes();
    }
    return new Response((int) status, type, body);
  }
}
