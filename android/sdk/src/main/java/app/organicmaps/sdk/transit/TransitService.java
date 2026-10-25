package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.util.concurrency.AsyncResult;
import java.time.Instant;
import java.util.List;

/**
 * Online public transport information (stops, departures, routing).
 * <p>
 * All methods return immediately. Errors are delivered as {@link TransitServiceException}.
 */
public interface TransitService
{
  /** Stops near the given point, nearest first. */
  @NonNull
  AsyncResult<List<TransitStop>> reverseGeocodeStops(double lat, double lon);

  /** Next departures at the stop, starting now. */
  @NonNull
  AsyncResult<TransitDepartures> stopTimes(@NonNull String stopId, int limit);

  /** Next departures at the stop nearest to the given point; fails with NO_RESULTS if there is none. */
  @NonNull
  AsyncResult<TransitDepartures> closestStopWithTimes(double lat, double lon, int limit);

  /**
   * Computes public transport connections (MOTIS /plan).
   *
   * @param time departure time, or arrival time if {@code arriveBy}; null for now.
   * @param pageCursor {@link TransitPlan#getNextPageCursor()} or {@link TransitPlan#getPreviousPageCursor()} of
   *        a previous result for the same query, or null for the first page.
   * @return the plan; an empty plan ({@link TransitPlan#isEmpty()}) is a success, not an error.
   */
  @NonNull
  AsyncResult<TransitPlan> plan(@NonNull TransitLocation from, @NonNull TransitLocation to, @Nullable Instant time,
                                boolean arriveBy, @Nullable String pageCursor, @NonNull TransitRoutingOptions options);
}
