package app.organicmaps.sdk.search.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Locale;
import java.util.Objects;

/**
 * A free-text query for an online search provider. Immutable; equal requests are answered from the cache.
 * <p>
 * Privacy: the location bias is rounded to {@link #BIAS_DECIMALS} decimal places (about 1 km), so only an
 * approximate position leaves the device. Rounding also makes requests from nearby positions equal for the cache.
 */
public final class OnlineSearchRequest
{
  public static final int DEFAULT_LIMIT = 10;
  public static final int MAX_LIMIT = 50;
  /** Decimal places of the location bias: 2 = 0.01° ≈ 1.1 km. */
  public static final int BIAS_DECIMALS = 2;

  @NonNull
  private final String mQuery;
  @Nullable
  private final String mLanguage;
  private final int mLimit;
  @Nullable
  private final Double mBiasLat;
  @Nullable
  private final Double mBiasLon;

  private OnlineSearchRequest(@NonNull Builder b)
  {
    mQuery = b.mQuery;
    mLanguage = b.mLanguage;
    mLimit = b.mLimit;
    mBiasLat = b.mBiasLat;
    mBiasLon = b.mBiasLon;
  }

  @NonNull
  public static Builder builder(@NonNull String query)
  {
    return new Builder(query);
  }

  /** The query, trimmed and with collapsed white space. */
  @NonNull
  public String getQuery()
  {
    return mQuery;
  }

  /** Two-letter language code in lower case (e.g. "de"), or null for the provider's default. */
  @Nullable
  public String getLanguage()
  {
    return mLanguage;
  }

  public int getLimit()
  {
    return mLimit;
  }

  public boolean hasLocationBias()
  {
    return mBiasLat != null && mBiasLon != null;
  }

  /** Rounded latitude of the location bias, or null. */
  @Nullable
  public Double getBiasLat()
  {
    return mBiasLat;
  }

  @Nullable
  public Double getBiasLon()
  {
    return mBiasLon;
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o)
      return true;
    if (!(o instanceof OnlineSearchRequest))
      return false;
    final OnlineSearchRequest that = (OnlineSearchRequest) o;
    return mLimit == that.mLimit && mQuery.equals(that.mQuery) && Objects.equals(mLanguage, that.mLanguage)
 && Objects.equals(mBiasLat, that.mBiasLat) && Objects.equals(mBiasLon, that.mBiasLon);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(mQuery, mLanguage, mLimit, mBiasLat, mBiasLon);
  }

  @NonNull
  @Override
  public String toString()
  {
    return "\"" + mQuery + "\" lang=" + mLanguage + " limit=" + mLimit
  + (hasLocationBias() ? " near " + mBiasLat + "," + mBiasLon : "");
  }

  /** Normalizes a locale tag like "de-DE", "de_AT" or "zh-Hans" to the language ("de", "zh"); null if invalid. */
  @Nullable
  public static String normalizeLanguage(@Nullable String localeTag)
  {
    if (localeTag == null)
      return null;
    String lang = localeTag.trim();
    final int sep = indexOfSeparator(lang);
    if (sep >= 0)
      lang = lang.substring(0, sep);
    lang = lang.toLowerCase(Locale.ROOT);
    if (lang.length() < 2 || lang.length() > 3)
      return null;
    for (int i = 0; i < lang.length(); ++i)
      if (lang.charAt(i) < 'a' || lang.charAt(i) > 'z')
        return null;
    return lang;
  }

  private static int indexOfSeparator(@NonNull String s)
  {
    for (int i = 0; i < s.length(); ++i)
      if (s.charAt(i) == '-' || s.charAt(i) == '_')
        return i;
    return -1;
  }

  static double round(double value)
  {
    final double factor = Math.pow(10, BIAS_DECIMALS);
    return Math.round(value * factor) / factor;
  }

  public static final class Builder
  {
    @NonNull
    private final String mQuery;
    @Nullable
    private String mLanguage;
    private int mLimit = DEFAULT_LIMIT;
    @Nullable
    private Double mBiasLat;
    @Nullable
    private Double mBiasLon;

    private Builder(@NonNull String query)
    {
      final String normalized = query.trim().replaceAll("\\s+", " ");
      if (normalized.isEmpty())
        throw new IllegalArgumentException("Empty query");
      mQuery = normalized;
    }

    /** @param localeTag e.g. "de-DE" or "en"; null or an invalid tag uses the provider's default. */
    @NonNull
    public Builder setLanguage(@Nullable String localeTag)
    {
      mLanguage = normalizeLanguage(localeTag);
      return this;
    }

    @NonNull
    public Builder setLimit(int limit)
    {
      if (limit < 1 || limit > MAX_LIMIT)
        throw new IllegalArgumentException("limit must be in 1.." + MAX_LIMIT + ": " + limit);
      mLimit = limit;
      return this;
    }

    /** Prefer results near this point (e.g. the map center); it is rounded, see {@link #BIAS_DECIMALS}. */
    @NonNull
    public Builder setLocationBias(double lat, double lon)
    {
      if (Double.isNaN(lat) || Double.isNaN(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180)
        throw new IllegalArgumentException("Invalid coordinate: " + lat + ", " + lon);
      // + 0.0 turns -0.0 into 0.0, so that equal requests stay equal.
      mBiasLat = round(lat) + 0.0;
      mBiasLon = round(lon) + 0.0;
      return this;
    }

    @NonNull
    public Builder clearLocationBias()
    {
      mBiasLat = null;
      mBiasLon = null;
      return this;
    }

    @NonNull
    public OnlineSearchRequest build()
    {
      return new OnlineSearchRequest(this);
    }
  }
}
