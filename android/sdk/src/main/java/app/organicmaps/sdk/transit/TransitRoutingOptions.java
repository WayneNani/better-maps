package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Options of a public transport routing query (MOTIS /plan). Immutable, create with {@link #builder()}.
 * <p>
 * Nullable values are not sent, so the server default applies. Durations are given in the unit of the
 * MOTIS API: transfer times in minutes, pre/post transit times and the search window in seconds.
 */
public final class TransitRoutingOptions
{
  /** Accessibility profile for walking parts (transfers, first and last mile). */
  public enum PedestrianProfile
  {
    FOOT,
    WHEELCHAIR
  }

  /** How realtime data (delays, cancellations) is used. */
  public enum RealtimeMode
  {
    /** Use realtime data for routing and sorting (server default). */
    REALTIME,
    /** Route on the timetable, but annotate the result with realtime data. */
    REALTIME_ANNOTATION_ONLY,
    /** Timetable only, no realtime data. */
    OFF
  }

  public static final int DEFAULT_NUM_ITINERARIES = 5;

  @Nullable
  private final Integer mMaxTransfers;
  @Nullable
  private final Integer mMinTransferTimeMinutes;
  @Nullable
  private final Integer mAdditionalTransferTimeMinutes;
  @Nullable
  private final Double mTransferTimeFactor;
  private final boolean mUseRoutedTransfers;
  private final boolean mDetailedTransfers;
  @Nullable
  private final PedestrianProfile mPedestrianProfile;
  @Nullable
  private final Double mPedestrianSpeed;
  @Nullable
  private final Boolean mRequireBikeTransport;
  @NonNull
  private final Set<TransitMode> mTransitModes;
  @NonNull
  private final Set<TransitMode> mPreTransitModes;
  @NonNull
  private final Set<TransitMode> mPostTransitModes;
  @Nullable
  private final Integer mMaxPreTransitTimeSeconds;
  @Nullable
  private final Integer mMaxPostTransitTimeSeconds;
  private final int mNumItineraries;
  @Nullable
  private final Integer mSearchWindowSeconds;
  @Nullable
  private final RealtimeMode mRealtimeMode;

  private TransitRoutingOptions(@NonNull Builder b)
  {
    mMaxTransfers = b.mMaxTransfers;
    mMinTransferTimeMinutes = b.mMinTransferTimeMinutes;
    mAdditionalTransferTimeMinutes = b.mAdditionalTransferTimeMinutes;
    mTransferTimeFactor = b.mTransferTimeFactor;
    mUseRoutedTransfers = b.mUseRoutedTransfers;
    mDetailedTransfers = b.mDetailedTransfers;
    mPedestrianProfile = b.mPedestrianProfile;
    mPedestrianSpeed = b.mPedestrianSpeed;
    mRequireBikeTransport = b.mRequireBikeTransport;
    mTransitModes = Collections.unmodifiableSet(copyOf(b.mTransitModes));
    mPreTransitModes = Collections.unmodifiableSet(copyOf(b.mPreTransitModes));
    mPostTransitModes = Collections.unmodifiableSet(copyOf(b.mPostTransitModes));
    mMaxPreTransitTimeSeconds = b.mMaxPreTransitTimeSeconds;
    mMaxPostTransitTimeSeconds = b.mMaxPostTransitTimeSeconds;
    mNumItineraries = b.mNumItineraries;
    mSearchWindowSeconds = b.mSearchWindowSeconds;
    mRealtimeMode = b.mRealtimeMode;
  }

  /** Defaults: routed transfers, detailed transfers, 5 itineraries, everything else as on the server. */
  @NonNull
  public static TransitRoutingOptions defaults()
  {
    return builder().build();
  }

  @NonNull
  public static Builder builder()
  {
    return new Builder();
  }

  @NonNull
  public Builder toBuilder()
  {
    return new Builder(this);
  }

  /** Maximum number of transfers (changes between transit legs), null for the server default. */
  @Nullable
  public Integer getMaxTransfers()
  {
    return mMaxTransfers;
  }

  @Nullable
  public Integer getMinTransferTimeMinutes()
  {
    return mMinTransferTimeMinutes;
  }

  @Nullable
  public Integer getAdditionalTransferTimeMinutes()
  {
    return mAdditionalTransferTimeMinutes;
  }

  @Nullable
  public Double getTransferTimeFactor()
  {
    return mTransferTimeFactor;
  }

  public boolean isUseRoutedTransfers()
  {
    return mUseRoutedTransfers;
  }

  public boolean isDetailedTransfers()
  {
    return mDetailedTransfers;
  }

  @Nullable
  public PedestrianProfile getPedestrianProfile()
  {
    return mPedestrianProfile;
  }

  /** Walking speed in meters per second, null for the server default. */
  @Nullable
  public Double getPedestrianSpeed()
  {
    return mPedestrianSpeed;
  }

  @Nullable
  public Boolean getRequireBikeTransport()
  {
    return mRequireBikeTransport;
  }

  /** Allowed transit modes, empty for the server default (all transit). */
  @NonNull
  public Set<TransitMode> getTransitModes()
  {
    return mTransitModes;
  }

  /** Modes for the first mile, empty for the server default (walking). */
  @NonNull
  public Set<TransitMode> getPreTransitModes()
  {
    return mPreTransitModes;
  }

  /** Modes for the last mile, empty for the server default (walking). */
  @NonNull
  public Set<TransitMode> getPostTransitModes()
  {
    return mPostTransitModes;
  }

  @Nullable
  public Integer getMaxPreTransitTimeSeconds()
  {
    return mMaxPreTransitTimeSeconds;
  }

  @Nullable
  public Integer getMaxPostTransitTimeSeconds()
  {
    return mMaxPostTransitTimeSeconds;
  }

  public int getNumItineraries()
  {
    return mNumItineraries;
  }

  @Nullable
  public Integer getSearchWindowSeconds()
  {
    return mSearchWindowSeconds;
  }

  @Nullable
  public RealtimeMode getRealtimeMode()
  {
    return mRealtimeMode;
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o)
      return true;
    if (!(o instanceof TransitRoutingOptions))
      return false;
    final TransitRoutingOptions that = (TransitRoutingOptions) o;
    return mUseRoutedTransfers == that.mUseRoutedTransfers && mDetailedTransfers == that.mDetailedTransfers
 && mNumItineraries == that.mNumItineraries && Objects.equals(mMaxTransfers, that.mMaxTransfers)
 && Objects.equals(mMinTransferTimeMinutes, that.mMinTransferTimeMinutes)
 && Objects.equals(mAdditionalTransferTimeMinutes, that.mAdditionalTransferTimeMinutes)
 && Objects.equals(mTransferTimeFactor, that.mTransferTimeFactor) && mPedestrianProfile == that.mPedestrianProfile
 && Objects.equals(mPedestrianSpeed, that.mPedestrianSpeed)
 && Objects.equals(mRequireBikeTransport, that.mRequireBikeTransport) && mTransitModes.equals(that.mTransitModes)
 && mPreTransitModes.equals(that.mPreTransitModes) && mPostTransitModes.equals(that.mPostTransitModes)
 && Objects.equals(mMaxPreTransitTimeSeconds, that.mMaxPreTransitTimeSeconds)
 && Objects.equals(mMaxPostTransitTimeSeconds, that.mMaxPostTransitTimeSeconds)
 && Objects.equals(mSearchWindowSeconds, that.mSearchWindowSeconds) && mRealtimeMode == that.mRealtimeMode;
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(mMaxTransfers, mMinTransferTimeMinutes, mAdditionalTransferTimeMinutes, mTransferTimeFactor,
                        mUseRoutedTransfers, mDetailedTransfers, mPedestrianProfile, mPedestrianSpeed,
                        mRequireBikeTransport, mTransitModes, mPreTransitModes, mPostTransitModes,
                        mMaxPreTransitTimeSeconds, mMaxPostTransitTimeSeconds, mNumItineraries, mSearchWindowSeconds,
                        mRealtimeMode);
  }

  @NonNull
  @Override
  public String toString()
  {
    return "TransitRoutingOptions{maxTransfers=" + mMaxTransfers
  + ", additionalTransferTime=" + mAdditionalTransferTimeMinutes + ", pedestrianProfile=" + mPedestrianProfile
  + ", pedestrianSpeed=" + mPedestrianSpeed + ", requireBikeTransport=" + mRequireBikeTransport
  + ", transitModes=" + mTransitModes + ", maxPreTransitTime=" + mMaxPreTransitTimeSeconds
  + ", maxPostTransitTime=" + mMaxPostTransitTimeSeconds + ", numItineraries=" + mNumItineraries + "}";
  }

  @NonNull
  private static Set<TransitMode> copyOf(@NonNull Set<TransitMode> modes)
  {
    return modes.isEmpty() ? EnumSet.noneOf(TransitMode.class) : EnumSet.copyOf(modes);
  }

  public static final class Builder
  {
    @Nullable
    private Integer mMaxTransfers;
    @Nullable
    private Integer mMinTransferTimeMinutes;
    @Nullable
    private Integer mAdditionalTransferTimeMinutes;
    @Nullable
    private Double mTransferTimeFactor;
    private boolean mUseRoutedTransfers = true;
    private boolean mDetailedTransfers = true;
    @Nullable
    private PedestrianProfile mPedestrianProfile;
    @Nullable
    private Double mPedestrianSpeed;
    @Nullable
    private Boolean mRequireBikeTransport;
    @NonNull
    private Set<TransitMode> mTransitModes = EnumSet.noneOf(TransitMode.class);
    @NonNull
    private Set<TransitMode> mPreTransitModes = EnumSet.noneOf(TransitMode.class);
    @NonNull
    private Set<TransitMode> mPostTransitModes = EnumSet.noneOf(TransitMode.class);
    @Nullable
    private Integer mMaxPreTransitTimeSeconds;
    @Nullable
    private Integer mMaxPostTransitTimeSeconds;
    private int mNumItineraries = DEFAULT_NUM_ITINERARIES;
    @Nullable
    private Integer mSearchWindowSeconds;
    @Nullable
    private RealtimeMode mRealtimeMode;

    private Builder() {}

    private Builder(@NonNull TransitRoutingOptions o)
    {
      mMaxTransfers = o.mMaxTransfers;
      mMinTransferTimeMinutes = o.mMinTransferTimeMinutes;
      mAdditionalTransferTimeMinutes = o.mAdditionalTransferTimeMinutes;
      mTransferTimeFactor = o.mTransferTimeFactor;
      mUseRoutedTransfers = o.mUseRoutedTransfers;
      mDetailedTransfers = o.mDetailedTransfers;
      mPedestrianProfile = o.mPedestrianProfile;
      mPedestrianSpeed = o.mPedestrianSpeed;
      mRequireBikeTransport = o.mRequireBikeTransport;
      mTransitModes = copyOf(o.mTransitModes);
      mPreTransitModes = copyOf(o.mPreTransitModes);
      mPostTransitModes = copyOf(o.mPostTransitModes);
      mMaxPreTransitTimeSeconds = o.mMaxPreTransitTimeSeconds;
      mMaxPostTransitTimeSeconds = o.mMaxPostTransitTimeSeconds;
      mNumItineraries = o.mNumItineraries;
      mSearchWindowSeconds = o.mSearchWindowSeconds;
      mRealtimeMode = o.mRealtimeMode;
    }

    @NonNull
    public Builder setMaxTransfers(@Nullable Integer maxTransfers)
    {
      mMaxTransfers = requireNonNegative(maxTransfers, "maxTransfers");
      return this;
    }

    @NonNull
    public Builder setMinTransferTimeMinutes(@Nullable Integer minutes)
    {
      mMinTransferTimeMinutes = requireNonNegative(minutes, "minTransferTime");
      return this;
    }

    @NonNull
    public Builder setAdditionalTransferTimeMinutes(@Nullable Integer minutes)
    {
      mAdditionalTransferTimeMinutes = requireNonNegative(minutes, "additionalTransferTime");
      return this;
    }

    /** Factor applied to the minimum transfer time of each transfer, must be at least 1. */
    @NonNull
    public Builder setTransferTimeFactor(@Nullable Double factor)
    {
      if (factor != null && !(factor >= 1.0))
        throw new IllegalArgumentException("transferTimeFactor must be >= 1: " + factor);
      mTransferTimeFactor = factor;
      return this;
    }

    @NonNull
    public Builder setUseRoutedTransfers(boolean useRoutedTransfers)
    {
      mUseRoutedTransfers = useRoutedTransfers;
      return this;
    }

    @NonNull
    public Builder setDetailedTransfers(boolean detailedTransfers)
    {
      mDetailedTransfers = detailedTransfers;
      return this;
    }

    @NonNull
    public Builder setPedestrianProfile(@Nullable PedestrianProfile profile)
    {
      mPedestrianProfile = profile;
      return this;
    }

    /** @param metersPerSecond walking speed, e.g. 1.2; null for the server default. */
    @NonNull
    public Builder setPedestrianSpeed(@Nullable Double metersPerSecond)
    {
      if (metersPerSecond != null && !(metersPerSecond > 0))
        throw new IllegalArgumentException("pedestrianSpeed must be > 0: " + metersPerSecond);
      mPedestrianSpeed = metersPerSecond;
      return this;
    }

    @NonNull
    public Builder setRequireBikeTransport(@Nullable Boolean requireBikeTransport)
    {
      mRequireBikeTransport = requireBikeTransport;
      return this;
    }

    @NonNull
    public Builder setTransitModes(@NonNull Set<TransitMode> modes)
    {
      for (TransitMode mode : modes)
        if (!mode.isTransit())
          throw new IllegalArgumentException("Not a transit mode: " + mode);
      mTransitModes = copyOf(modes);
      return this;
    }

    @NonNull
    public Builder setPreTransitModes(@NonNull Set<TransitMode> modes)
    {
      mPreTransitModes = requireStreetModes(modes);
      return this;
    }

    @NonNull
    public Builder setPostTransitModes(@NonNull Set<TransitMode> modes)
    {
      mPostTransitModes = requireStreetModes(modes);
      return this;
    }

    @NonNull
    public Builder setMaxPreTransitTimeSeconds(@Nullable Integer seconds)
    {
      mMaxPreTransitTimeSeconds = requireNonNegative(seconds, "maxPreTransitTime");
      return this;
    }

    @NonNull
    public Builder setMaxPostTransitTimeSeconds(@Nullable Integer seconds)
    {
      mMaxPostTransitTimeSeconds = requireNonNegative(seconds, "maxPostTransitTime");
      return this;
    }

    @NonNull
    public Builder setNumItineraries(int numItineraries)
    {
      if (numItineraries < 1)
        throw new IllegalArgumentException("numItineraries must be >= 1: " + numItineraries);
      mNumItineraries = numItineraries;
      return this;
    }

    @NonNull
    public Builder setSearchWindowSeconds(@Nullable Integer seconds)
    {
      mSearchWindowSeconds = requireNonNegative(seconds, "searchWindow");
      return this;
    }

    @NonNull
    public Builder setRealtimeMode(@Nullable RealtimeMode realtimeMode)
    {
      mRealtimeMode = realtimeMode;
      return this;
    }

    @NonNull
    public TransitRoutingOptions build()
    {
      return new TransitRoutingOptions(this);
    }

    @Nullable
    private static Integer requireNonNegative(@Nullable Integer value, @NonNull String name)
    {
      if (value != null && value < 0)
        throw new IllegalArgumentException(name + " must be >= 0: " + value);
      return value;
    }

    @NonNull
    private static Set<TransitMode> requireStreetModes(@NonNull Set<TransitMode> modes)
    {
      for (TransitMode mode : modes)
        if (mode.isTransit() || mode == TransitMode.UNKNOWN)
          throw new IllegalArgumentException("Not a street mode: " + mode);
      return copyOf(modes);
    }
  }
}
