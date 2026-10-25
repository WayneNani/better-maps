package app.organicmaps.sdk.search.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A place found by an online search provider (e.g. Photon). Immutable, create with {@link #builder(double, double)}.
 * <p>
 * Only the fields needed by the search list and the map are kept: name, OSM type and id, the main OSM tag (e.g.
 * amenity=cafe), the kind of place (house, street, city, ...), address parts, coordinate and bounding box.
 */
public final class OnlineSearchResult
{
  /** Kind of place as reported by Photon's "type" (also used as the "layer" filter). */
  public enum PlaceType
  {
    HOUSE,
    STREET,
    LOCALITY,
    DISTRICT,
    CITY,
    COUNTY,
    STATE,
    COUNTRY,
    OTHER;

    @NonNull
    public static PlaceType parse(@Nullable String value)
    {
      if (value == null)
        return OTHER;
      try
      {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
      }
      catch (IllegalArgumentException e)
      {
        return OTHER;
      }
    }
  }

  @Nullable
  private final String mName;
  @Nullable
  private final String mOsmType;
  private final long mOsmId;
  @Nullable
  private final String mOsmKey;
  @Nullable
  private final String mOsmValue;
  @NonNull
  private final PlaceType mPlaceType;
  @Nullable
  private final String mStreet;
  @Nullable
  private final String mHouseNumber;
  @Nullable
  private final String mPostcode;
  @Nullable
  private final String mLocality;
  @Nullable
  private final String mDistrict;
  @Nullable
  private final String mCity;
  @Nullable
  private final String mCounty;
  @Nullable
  private final String mState;
  @Nullable
  private final String mCountry;
  @Nullable
  private final String mCountryCode;
  private final double mLat;
  private final double mLon;
  @Nullable
  private final BoundingBox mExtent;

  private OnlineSearchResult(@NonNull Builder b)
  {
    mName = b.mName;
    mOsmType = b.mOsmType;
    mOsmId = b.mOsmId;
    mOsmKey = b.mOsmKey;
    mOsmValue = b.mOsmValue;
    mPlaceType = b.mPlaceType;
    mStreet = b.mStreet;
    mHouseNumber = b.mHouseNumber;
    mPostcode = b.mPostcode;
    mLocality = b.mLocality;
    mDistrict = b.mDistrict;
    mCity = b.mCity;
    mCounty = b.mCounty;
    mState = b.mState;
    mCountry = b.mCountry;
    mCountryCode = b.mCountryCode;
    mLat = b.mLat;
    mLon = b.mLon;
    mExtent = b.mExtent;
  }

  @NonNull
  public static Builder builder(double lat, double lon)
  {
    return new Builder(lat, lon);
  }

  /** Name of the place; null for plain addresses (house numbers without a name). */
  @Nullable
  public String getName()
  {
    return mName;
  }

  /** "N" (node), "W" (way) or "R" (relation); null if unknown. */
  @Nullable
  public String getOsmType()
  {
    return mOsmType;
  }

  /** OSM id, 0 if unknown. Only unique together with {@link #getOsmType()}. */
  public long getOsmId()
  {
    return mOsmId;
  }

  /** Unique OSM reference like "W259055080", or null if the provider did not report one. */
  @Nullable
  public String getOsmRef()
  {
    if (mOsmType == null || mOsmId <= 0)
      return null;
    return mOsmType + mOsmId;
  }

  /** Key of the main OSM tag, e.g. "amenity", "highway", "place". */
  @Nullable
  public String getOsmKey()
  {
    return mOsmKey;
  }

  /** Value of the main OSM tag, e.g. "cafe", "pedestrian", "city". */
  @Nullable
  public String getOsmValue()
  {
    return mOsmValue;
  }

  /** Category in the form "key-value" as used by the CoMaps type strings (e.g. "amenity-cafe"); null if unknown. */
  @Nullable
  public String getCategory()
  {
    if (isEmpty(mOsmKey) || isEmpty(mOsmValue))
      return null;
    return mOsmKey + "-" + mOsmValue;
  }

  @NonNull
  public PlaceType getPlaceType()
  {
    return mPlaceType;
  }

  @Nullable
  public String getStreet()
  {
    return mStreet;
  }

  @Nullable
  public String getHouseNumber()
  {
    return mHouseNumber;
  }

  @Nullable
  public String getPostcode()
  {
    return mPostcode;
  }

  @Nullable
  public String getLocality()
  {
    return mLocality;
  }

  @Nullable
  public String getDistrict()
  {
    return mDistrict;
  }

  @Nullable
  public String getCity()
  {
    return mCity;
  }

  @Nullable
  public String getCounty()
  {
    return mCounty;
  }

  @Nullable
  public String getState()
  {
    return mState;
  }

  @Nullable
  public String getCountry()
  {
    return mCountry;
  }

  /** ISO 3166-1 alpha-2 code in upper case, e.g. "DE". */
  @Nullable
  public String getCountryCode()
  {
    return mCountryCode;
  }

  public double getLat()
  {
    return mLat;
  }

  public double getLon()
  {
    return mLon;
  }

  /** Bounding box of the place (e.g. a street or city); null for points. */
  @Nullable
  public BoundingBox getExtent()
  {
    return mExtent;
  }

  /** Street with house number in the usual German/European order ("Leopoldstraße 10"). */
  @Nullable
  public String getStreetLine()
  {
    if (isEmpty(mStreet))
      return null;
    return isEmpty(mHouseNumber) ? mStreet : mStreet + " " + mHouseNumber;
  }

  /**
   * Title for the list: the name, otherwise street and house number, otherwise the most specific place name.
   * Never empty.
   */
  @NonNull
  public String getTitle()
  {
    if (!isEmpty(mName))
      return mName;
    final String streetLine = getStreetLine();
    if (streetLine != null)
      return streetLine;
    for (String s : new String[] {mLocality, mDistrict, mCity, mCounty, mState, mCountry})
      if (!isEmpty(s))
        return s;
    return String.format(Locale.ROOT, "%.5f, %.5f", mLat, mLon);
  }

  /**
   * Address without the parts already in the title, e.g. "Altstadt, 80331 München, Deutschland" for the
   * Marienplatz. Empty if nothing is left.
   */
  @NonNull
  public String getAddress()
  {
    final String title = getTitle();
    final List<String> parts = new ArrayList<>();
    final String streetLine = getStreetLine();
    if (streetLine != null && !streetLine.equals(title) && !Objects.equals(mStreet, title))
      parts.add(streetLine);
    final String place = !isEmpty(mCity) ? mCity : (!isEmpty(mLocality) ? mLocality : mCounty);
    if (!isEmpty(mDistrict) && !mDistrict.equals(place) && !mDistrict.equals(title))
      parts.add(mDistrict);
    if (!isEmpty(place) && !place.equals(title))
      parts.add(isEmpty(mPostcode) ? place : mPostcode + " " + place);
    else if (!isEmpty(mPostcode) && mPlaceType != PlaceType.CITY)
      parts.add(mPostcode);
    if (!isEmpty(mState) && parts.isEmpty() && !mState.equals(title))
      parts.add(mState);
    if (!isEmpty(mCountry) && !mCountry.equals(title))
      parts.add(mCountry);
    return String.join(", ", parts);
  }

  private static boolean isEmpty(@Nullable String s)
  {
    return s == null || s.isEmpty();
  }

  @NonNull
  @Override
  public String toString()
  {
    return getTitle() + " [" + getOsmRef() + " " + mOsmKey + "=" + mOsmValue + " " + mPlaceType + "] " + mLat + ","
  + mLon;
  }

  /** Geographic rectangle. */
  public static final class BoundingBox
  {
    private final double mMinLat;
    private final double mMinLon;
    private final double mMaxLat;
    private final double mMaxLon;

    public BoundingBox(double minLat, double minLon, double maxLat, double maxLon)
    {
      mMinLat = Math.min(minLat, maxLat);
      mMaxLat = Math.max(minLat, maxLat);
      mMinLon = Math.min(minLon, maxLon);
      mMaxLon = Math.max(minLon, maxLon);
    }

    public double getMinLat()
    {
      return mMinLat;
    }

    public double getMinLon()
    {
      return mMinLon;
    }

    public double getMaxLat()
    {
      return mMaxLat;
    }

    public double getMaxLon()
    {
      return mMaxLon;
    }

    public boolean contains(double lat, double lon)
    {
      return lat >= mMinLat && lat <= mMaxLat && lon >= mMinLon && lon <= mMaxLon;
    }

    @NonNull
    @Override
    public String toString()
    {
      return "[" + mMinLat + "," + mMinLon + " - " + mMaxLat + "," + mMaxLon + "]";
    }
  }

  public static final class Builder
  {
    @Nullable
    private String mName;
    @Nullable
    private String mOsmType;
    private long mOsmId;
    @Nullable
    private String mOsmKey;
    @Nullable
    private String mOsmValue;
    @NonNull
    private PlaceType mPlaceType = PlaceType.OTHER;
    @Nullable
    private String mStreet;
    @Nullable
    private String mHouseNumber;
    @Nullable
    private String mPostcode;
    @Nullable
    private String mLocality;
    @Nullable
    private String mDistrict;
    @Nullable
    private String mCity;
    @Nullable
    private String mCounty;
    @Nullable
    private String mState;
    @Nullable
    private String mCountry;
    @Nullable
    private String mCountryCode;
    private final double mLat;
    private final double mLon;
    @Nullable
    private BoundingBox mExtent;

    private Builder(double lat, double lon)
    {
      if (Double.isNaN(lat) || Double.isNaN(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180)
        throw new IllegalArgumentException("Invalid coordinate: " + lat + ", " + lon);
      mLat = lat;
      mLon = lon;
    }

    @NonNull
    public Builder setName(@Nullable String name)
    {
      mName = trimToNull(name);
      return this;
    }

    @NonNull
    public Builder setOsm(@Nullable String type, long id)
    {
      mOsmType = trimToNull(type) == null ? null : type.trim().toUpperCase(Locale.ROOT);
      mOsmId = id;
      return this;
    }

    @NonNull
    public Builder setTag(@Nullable String key, @Nullable String value)
    {
      mOsmKey = trimToNull(key);
      mOsmValue = trimToNull(value);
      return this;
    }

    @NonNull
    public Builder setPlaceType(@NonNull PlaceType type)
    {
      mPlaceType = type;
      return this;
    }

    @NonNull
    public Builder setStreet(@Nullable String street, @Nullable String houseNumber)
    {
      mStreet = trimToNull(street);
      mHouseNumber = trimToNull(houseNumber);
      return this;
    }

    @NonNull
    public Builder setPostcode(@Nullable String postcode)
    {
      mPostcode = trimToNull(postcode);
      return this;
    }

    @NonNull
    public Builder setLocality(@Nullable String locality)
    {
      mLocality = trimToNull(locality);
      return this;
    }

    @NonNull
    public Builder setDistrict(@Nullable String district)
    {
      mDistrict = trimToNull(district);
      return this;
    }

    @NonNull
    public Builder setCity(@Nullable String city)
    {
      mCity = trimToNull(city);
      return this;
    }

    @NonNull
    public Builder setCounty(@Nullable String county)
    {
      mCounty = trimToNull(county);
      return this;
    }

    @NonNull
    public Builder setState(@Nullable String state)
    {
      mState = trimToNull(state);
      return this;
    }

    @NonNull
    public Builder setCountry(@Nullable String country, @Nullable String countryCode)
    {
      mCountry = trimToNull(country);
      mCountryCode = trimToNull(countryCode) == null ? null : countryCode.trim().toUpperCase(Locale.ROOT);
      return this;
    }

    @NonNull
    public Builder setExtent(@Nullable BoundingBox extent)
    {
      mExtent = extent;
      return this;
    }

    @NonNull
    public OnlineSearchResult build()
    {
      return new OnlineSearchResult(this);
    }

    @Nullable
    private static String trimToNull(@Nullable String s)
    {
      if (s == null)
        return null;
      final String trimmed = s.trim();
      return trimmed.isEmpty() ? null : trimmed;
    }
  }
}
