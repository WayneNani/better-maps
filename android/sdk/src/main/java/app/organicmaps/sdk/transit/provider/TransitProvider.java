package app.organicmaps.sdk.transit.provider;

import androidx.annotation.NonNull;

/**
 * A source of online public transport data, e.g. Transitous (MOTIS). A provider implements
 * {@link TransitRoutingProvider} and/or {@link TransitDepartureProvider}; several providers are combined by a
 * {@link TransitProviderRegistry}.
 */
public interface TransitProvider
{
  /** Stable identifier, e.g. "transitous". Used for pinning follow-up requests (paging) to one provider. */
  @NonNull
  String getId();

  /** Name for display, e.g. "Transitous". */
  @NonNull
  String getName();

  /** Web page that lists the data sources and their licenses; must be shown next to the data. */
  @NonNull
  String getAttributionUrl();

  /** False if the provider is switched off (e.g. in the settings); the registry skips it then. */
  default boolean isEnabled()
  {
    return true;
  }
}
