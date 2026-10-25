package app.organicmaps.sdk.transit.provider;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.transit.TransitDepartures;
import app.organicmaps.sdk.util.concurrency.AsyncResult;

/** A provider of departures at stops. */
public interface TransitDepartureProvider extends TransitProvider
{
  /**
   * Next departures at the stop nearest to the given point. Stop ids differ between providers, so the registry
   * uses coordinates to be able to fall back to another provider.
   */
  @NonNull
  AsyncResult<TransitDepartures> departuresNear(double lat, double lon, int limit);
}
