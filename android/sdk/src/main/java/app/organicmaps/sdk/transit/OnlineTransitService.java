package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.util.Function;
import app.organicmaps.sdk.motis.api.GeocodeApi;
import app.organicmaps.sdk.motis.api.RoutingApi;
import app.organicmaps.sdk.motis.api.TimetableApi;
import app.organicmaps.sdk.motis.invoker.ApiCallback;
import app.organicmaps.sdk.motis.invoker.ApiClient;
import app.organicmaps.sdk.motis.invoker.ApiException;
import app.organicmaps.sdk.motis.model.LocationType;
import app.organicmaps.sdk.motis.model.Mode;
import app.organicmaps.sdk.motis.model.PedestrianProfile;
import app.organicmaps.sdk.motis.model.RealtimeMode;
import app.organicmaps.sdk.util.concurrency.AsyncResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.MalformedJsonException;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

/**
 * {@link TransitService} backed by a MOTIS 2 server (API v6), by default Transitous.
 * <p>
 * Network I/O and parsing run on OkHttp's threads; results are delivered on the given executor.
 * Create one instance and reuse it: it owns an HTTP client with a connection pool.
 */
public class OnlineTransitService implements TransitService
{
  @NonNull
  private final TransitServiceConfig mConfig;
  @NonNull
  private final GeocodeApi mGeocodeApi;
  @NonNull
  private final TimetableApi mTimetableApi;
  @NonNull
  private final RoutingApi mRoutingApi;
  @NonNull
  private final Executor mExecutor;

  /**
   * @param executor executor for result callbacks, e.g. {@code ContextCompat.getMainExecutor(context)}.
   */
  public OnlineTransitService(@NonNull TransitServiceConfig config, @NonNull Executor executor)
  {
    this(config, executor, new OkHttpClient.Builder());
  }

  @VisibleForTesting
  OnlineTransitService(@NonNull TransitServiceConfig config, @NonNull Executor executor,
                       @NonNull OkHttpClient.Builder httpClientBuilder)
  {
    mConfig = config;
    mExecutor = executor;
    final OkHttpClient httpClient =
        httpClientBuilder.connectTimeout(config.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
            .readTimeout(config.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
            .callTimeout(config.getCallTimeoutMs(), TimeUnit.MILLISECONDS)
            .build();
    final ApiClient client = new ApiClient(httpClient);
    client.setBasePath(config.getBaseUrl());
    client.setUserAgent(config.getUserAgent());
    mGeocodeApi = new GeocodeApi(client);
    mTimetableApi = new TimetableApi(client);
    mRoutingApi = new RoutingApi(client);
  }

  @NonNull
  public TransitServiceConfig getConfig()
  {
    return mConfig;
  }

  @NonNull
  @Override
  public AsyncResult<List<TransitStop>> reverseGeocodeStops(double lat, double lon)
  {
    final AsyncResult<List<TransitStop>> result = new AsyncResult<>(mExecutor);
    final String place = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon);
    try
    {
      mGeocodeApi.reverseGeocode(place)
          .type(Collections.singletonList(LocationType.STOP))
          .executeAsync(new Callback<>(result, MotisMapper::toStops));
    }
    catch (ApiException e)
    {
      result.fail(toServiceException(e, 0));
    }
    return result;
  }

  @NonNull
  @Override
  public AsyncResult<TransitDepartures> stopTimes(@NonNull String stopId, int limit)
  {
    final AsyncResult<TransitDepartures> result = new AsyncResult<>(mExecutor);
    if (limit < 1)
    {
      result.fail(new IllegalArgumentException("limit must be >= 1: " + limit));
      return result;
    }
    try
    {
      mTimetableApi.stoptimes().stopId(stopId).n(limit).executeAsync(new Callback<>(result, MotisMapper::toDepartures));
    }
    catch (ApiException e)
    {
      result.fail(toServiceException(e, 0));
    }
    return result;
  }

  @NonNull
  @Override
  public AsyncResult<TransitDepartures> closestStopWithTimes(double lat, double lon, int limit)
  {
    return reverseGeocodeStops(lat, lon).thenCompose(stops -> {
      if (stops.isEmpty())
        return AsyncResult.failed(mExecutor,
                                  TransitServiceException.noResults("No stops found near " + lat + ", " + lon));
      return stopTimes(stops.get(0).getId(), limit);
    });
  }

  @NonNull
  @Override
  public AsyncResult<TransitPlan> plan(@NonNull TransitLocation from, @NonNull TransitLocation to,
                                       @Nullable Instant time, boolean arriveBy, @Nullable String pageCursor,
                                       @NonNull TransitRoutingOptions options)
  {
    final AsyncResult<TransitPlan> result = new AsyncResult<>(mExecutor);
    try
    {
      buildPlanRequest(from, to, time, arriveBy, pageCursor, options)
          .executeAsync(new Callback<>(result, MotisMapper::toPlan));
    }
    catch (ApiException | RuntimeException e)
    {
      result.fail(e instanceof ApiException ? toServiceException((ApiException) e, 0) : e);
    }
    return result;
  }

  @NonNull
  private RoutingApi.APIplanRequest buildPlanRequest(@NonNull TransitLocation from, @NonNull TransitLocation to,
                                                     @Nullable Instant time, boolean arriveBy,
                                                     @Nullable String pageCursor,
                                                     @NonNull TransitRoutingOptions options)
  {
    final RoutingApi.APIplanRequest request = mRoutingApi.plan(from.toApiString(), to.toApiString());
    if (time != null)
      request.time(OffsetDateTime.ofInstant(time, ZoneOffset.UTC));
    if (arriveBy)
      request.arriveBy(true);
    if (pageCursor != null && !pageCursor.isEmpty())
      request.pageCursor(pageCursor);

    request.numItineraries(options.getNumItineraries());
    request.useRoutedTransfers(options.isUseRoutedTransfers());
    request.detailedTransfers(options.isDetailedTransfers());
    if (options.getMaxTransfers() != null)
      request.maxTransfers(options.getMaxTransfers());
    if (options.getMinTransferTimeMinutes() != null)
      request.minTransferTime(options.getMinTransferTimeMinutes());
    if (options.getAdditionalTransferTimeMinutes() != null)
      request.additionalTransferTime(options.getAdditionalTransferTimeMinutes());
    if (options.getTransferTimeFactor() != null)
      request.transferTimeFactor(BigDecimal.valueOf(options.getTransferTimeFactor()));
    if (options.getPedestrianProfile() != null)
      request.pedestrianProfile(PedestrianProfile.fromValue(options.getPedestrianProfile().name()));
    if (options.getPedestrianSpeed() != null)
      request.pedestrianSpeed(BigDecimal.valueOf(options.getPedestrianSpeed()));
    if (options.getRequireBikeTransport() != null)
      request.requireBikeTransport(options.getRequireBikeTransport());
    if (!options.getTransitModes().isEmpty())
      request.transitModes(toApiModes(options.getTransitModes()));
    if (!options.getPreTransitModes().isEmpty())
      request.preTransitModes(toApiModes(options.getPreTransitModes()));
    if (!options.getPostTransitModes().isEmpty())
      request.postTransitModes(toApiModes(options.getPostTransitModes()));
    if (options.getMaxPreTransitTimeSeconds() != null)
      request.maxPreTransitTime(options.getMaxPreTransitTimeSeconds());
    if (options.getMaxPostTransitTimeSeconds() != null)
      request.maxPostTransitTime(options.getMaxPostTransitTimeSeconds());
    if (options.getSearchWindowSeconds() != null)
      request.searchWindow(options.getSearchWindowSeconds());
    if (options.getRealtimeMode() != null)
      request.realtimeMode(RealtimeMode.fromValue(options.getRealtimeMode().name()));
    return request;
  }

  @NonNull
  private static List<Mode> toApiModes(@NonNull Set<TransitMode> modes)
  {
    final List<Mode> result = new ArrayList<>(modes.size());
    for (TransitMode mode : modes)
      result.add(Mode.fromValue(mode.name()));
    return result;
  }

  /** Converts a failure of the generated client into a {@link TransitServiceException}. */
  @NonNull
  static TransitServiceException toServiceException(@NonNull ApiException e, int statusCode)
  {
    final Throwable cause = e.getCause() != null ? e.getCause() : e;
    if (e.getCode() == 0)
    {
      // No HTTP error status: either no response at all, or reading/parsing the body failed.
      // Reading errors arrive wrapped, e.g. a read timeout as JsonSyntaxException(SocketTimeoutException).
      if (isNetworkFailure(cause))
        return new TransitServiceException(TransitServiceException.Kind.NETWORK, statusCode, null,
                                           "Network error: " + cause.getMessage(), cause);
      return new TransitServiceException(TransitServiceException.Kind.INVALID_RESPONSE, statusCode, null,
                                         "Invalid response: " + cause.getMessage(), cause);
    }
    final int code = e.getCode();
    if (code >= 200 && code < 300)
      return new TransitServiceException(TransitServiceException.Kind.INVALID_RESPONSE, code, null,
                                         "Invalid response: " + e.getMessage(), e);
    final String serverMessage = parseServerMessage(e.getResponseBody());
    final TransitServiceException.Kind kind =
        code >= 500 ? TransitServiceException.Kind.SERVER_ERROR : TransitServiceException.Kind.CLIENT_ERROR;
    return new TransitServiceException(kind, code, serverMessage,
                                       "HTTP " + code + (serverMessage != null ? ": " + serverMessage : ""), e);
  }

  /**
   * @return true if an I/O error (connection, timeout, TLS, truncated transfer) is in the cause chain. Malformed
   *         or prematurely ending JSON from a complete response is not a network failure.
   */
  static boolean isNetworkFailure(@Nullable Throwable error)
  {
    for (int depth = 0; error != null && depth < 10; ++depth, error = error.getCause())
    {
      if (error instanceof MalformedJsonException || error instanceof EOFException)
        return false;
      if (error instanceof IOException)
        return true;
    }
    return false;
  }

  /** Extracts the message from a MOTIS error response: {"error": "..."}. */
  @Nullable
  static String parseServerMessage(@Nullable String body)
  {
    if (body == null || body.isEmpty())
      return null;
    try
    {
      final JsonElement json = JsonParser.parseString(body);
      if (json.isJsonObject())
      {
        final JsonObject object = json.getAsJsonObject();
        if (object.has("error") && object.get("error").isJsonPrimitive())
          return object.get("error").getAsString();
      }
    }
    catch (RuntimeException ignored)
    {
      // Not JSON, e.g. an HTML error page of a proxy.
    }
    return null;
  }

  /** Completes an {@link AsyncResult} from a generated client callback, mapping the DTO on the network thread. */
  private static final class Callback<D, R> implements ApiCallback<D>
  {
    @NonNull
    private final AsyncResult<R> mResult;
    @NonNull
    private final Function<D, R> mMapper;

    Callback(@NonNull AsyncResult<R> result, @NonNull Function<D, R> mapper)
    {
      mResult = result;
      mMapper = mapper;
    }

    @Override
    public void onSuccess(D response, int statusCode, Map<String, List<String>> responseHeaders)
    {
      if (response == null)
      {
        mResult.fail(new TransitServiceException(TransitServiceException.Kind.INVALID_RESPONSE, statusCode, null,
                                                 "Empty response body", null));
        return;
      }
      final R mapped;
      try
      {
        mapped = mMapper.apply(response);
      }
      catch (RuntimeException e)
      {
        mResult.fail(new TransitServiceException(TransitServiceException.Kind.INVALID_RESPONSE, statusCode, null,
                                                 "Cannot map response: " + e.getMessage(), e));
        return;
      }
      mResult.complete(mapped);
    }

    @Override
    public void onFailure(ApiException e, int statusCode, Map<String, List<String>> responseHeaders)
    {
      mResult.fail(toServiceException(e, statusCode));
    }

    @Override
    public void onUploadProgress(long bytesWritten, long contentLength, boolean done)
    {}

    @Override
    public void onDownloadProgress(long bytesRead, long contentLength, boolean done)
    {}
  }
}
