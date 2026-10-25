package app.organicmaps.sdk.search.online;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.util.concurrency.AsyncResult;
import java.util.List;

/**
 * A source of online search results for places outside the downloaded maps, e.g. Photon. Implementations send the
 * request as is; consent, caching and debouncing are handled by {@link OnlineSearchService} and its callers.
 */
public interface OnlineSearchProvider
{
  /** Stable identifier, e.g. "photon". */
  @NonNull
  String getId();

  /** Name for display next to the results, e.g. "Photon". */
  @NonNull
  String getName();

  /** Web page about the data and its license; shown next to the results. */
  @NonNull
  String getAttributionUrl();

  /**
   * Searches places. Fails with an {@link OnlineSearchException} for network, HTTP and parsing errors. An empty list
   * means nothing was found.
   */
  @NonNull
  AsyncResult<List<OnlineSearchResult>> search(@NonNull OnlineSearchRequest request);
}
