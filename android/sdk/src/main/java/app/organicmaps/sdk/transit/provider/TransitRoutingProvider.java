package app.organicmaps.sdk.transit.provider;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.transit.TransitPlan;
import app.organicmaps.sdk.util.concurrency.AsyncResult;

/** A provider that computes public transport connections. */
public interface TransitRoutingProvider extends TransitProvider
{
  /**
   * Computes connections. Errors are delivered as {@link app.organicmaps.sdk.transit.TransitServiceException};
   * retryable errors make the registry fall back to the next provider.
   */
  @NonNull
  AsyncResult<TransitPlan> plan(@NonNull TransitPlanRequest request);
}
