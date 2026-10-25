package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Arrays;

/** Decoder for Google encoded polylines as returned by MOTIS (precision 6 for API v2 and later). */
public final class PolylineDecoder
{
  private PolylineDecoder() {}

  /**
   * @param encoded encoded polyline, may be null or empty.
   * @param precision number of decimal places, e.g. 5 (Google default) or 6 (MOTIS).
   * @return interleaved coordinates {lat0, lon0, lat1, lon1, ...}.
   * @throws IllegalArgumentException if the polyline is malformed.
   */
  @NonNull
  public static double[] decode(@Nullable String encoded, int precision)
  {
    if (encoded == null || encoded.isEmpty())
      return new double[0];
    if (precision < 0 || precision > 9)
      throw new IllegalArgumentException("Unsupported polyline precision: " + precision);

    final double factor = Math.pow(10, precision);
    double[] result = new double[Math.max(4, encoded.length() / 2)];
    int count = 0;
    int index = 0;
    long lat = 0;
    long lon = 0;
    final int length = encoded.length();
    while (index < length)
    {
      final long[] latDelta = readValue(encoded, index);
      index = (int) latDelta[1];
      if (index >= length)
        throw new IllegalArgumentException("Truncated polyline");
      final long[] lonDelta = readValue(encoded, index);
      index = (int) lonDelta[1];
      lat += latDelta[0];
      lon += lonDelta[0];
      if (count + 2 > result.length)
        result = Arrays.copyOf(result, result.length * 2);
      result[count++] = lat / factor;
      result[count++] = lon / factor;
    }
    return Arrays.copyOf(result, count);
  }

  /** @return {decoded value, index after the value}. */
  @NonNull
  private static long[] readValue(@NonNull String encoded, int index)
  {
    long result = 0;
    int shift = 0;
    int b;
    do
    {
      if (index >= encoded.length())
        throw new IllegalArgumentException("Truncated polyline");
      b = encoded.charAt(index++) - 63;
      if (b < 0 || b > 63)
        throw new IllegalArgumentException("Invalid polyline character at " + (index - 1));
      result |= (long) (b & 0x1f) << shift;
      shift += 5;
      if (shift > 60)
        throw new IllegalArgumentException("Polyline value too long");
    }
    while (b >= 0x20);
    final long value = (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
    return new long[] {value, index};
  }
}
