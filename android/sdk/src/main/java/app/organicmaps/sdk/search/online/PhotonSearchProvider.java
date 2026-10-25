package app.organicmaps.sdk.search.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import app.organicmaps.sdk.util.concurrency.AsyncResult;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Online search with Photon (https://github.com/komoot/photon), a geocoder for OpenStreetMap data. By default the
 * public instance photon.komoot.io; any other Photon server with the same API can be configured.
 * <p>
 * Request: {@code GET <base>/api?q=...&limit=...[&lang=..][&lat=..&lon=..]}. Only the query, the language and, if
 * set, the rounded location bias are sent. Network I/O and parsing run on OkHttp's threads; results are delivered on
 * the given executor. Create one instance and reuse it: it owns an HTTP client with a connection pool.
 */
public class PhotonSearchProvider implements OnlineSearchProvider
{
  public static final String ID = "photon";
  public static final String NAME = "Photon";
  /** Photon results are OpenStreetMap data (ODbL). */
  public static final String ATTRIBUTION_URL = "https://www.openstreetmap.org/copyright";
  public static final String WEBSITE_URL = "https://photon.komoot.io/";

  @NonNull
  private final PhotonConfig mConfig;
  @NonNull
  private final Executor mExecutor;
  @NonNull
  private final OkHttpClient mClient;
  @NonNull
  private final HttpUrl mApiUrl;
  @NonNull
  private final String mName;

  /** @param executor executor for result callbacks, e.g. the main thread executor. */
  public PhotonSearchProvider(@NonNull PhotonConfig config, @NonNull Executor executor)
  {
    this(config, executor, new OkHttpClient.Builder());
  }

  @VisibleForTesting
  PhotonSearchProvider(@NonNull PhotonConfig config, @NonNull Executor executor,
                       @NonNull OkHttpClient.Builder httpClientBuilder)
  {
    mConfig = config;
    mExecutor = executor;
    mClient = httpClientBuilder.connectTimeout(config.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                  .readTimeout(config.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                  .callTimeout(config.getCallTimeoutMs(), TimeUnit.MILLISECONDS)
                  .build();
    final HttpUrl base = HttpUrl.parse(config.getBaseUrl());
    if (base == null)
      throw new IllegalArgumentException("Invalid base URL: " + config.getBaseUrl());
    mApiUrl = base.newBuilder().addPathSegment("api").build();
    // An own server is named by its host, so that the user sees where the query goes.
    mName = PhotonConfig.DEFAULT_URL.equals(config.getBaseUrl()) ? NAME : NAME + " (" + base.host() + ")";
  }

  @NonNull
  public PhotonConfig getConfig()
  {
    return mConfig;
  }

  @NonNull
  @Override
  public String getId()
  {
    return ID;
  }

  @NonNull
  @Override
  public String getName()
  {
    return mName;
  }

  @NonNull
  @Override
  public String getAttributionUrl()
  {
    return ATTRIBUTION_URL;
  }

  @NonNull
  @Override
  public AsyncResult<List<OnlineSearchResult>> search(@NonNull OnlineSearchRequest request)
  {
    final AsyncResult<List<OnlineSearchResult>> result = new AsyncResult<>(mExecutor);
    final Request httpRequest = new Request.Builder()
                                    .url(buildUrl(request))
                                    .header("User-Agent", mConfig.getUserAgent())
                                    .header("Accept", "application/json")
                                    .get()
                                    .build();
    mClient.newCall(httpRequest).enqueue(new Callback() {
      @Override
      public void onFailure(@NonNull Call call, @NonNull IOException e)
      {
        result.fail(new OnlineSearchException(OnlineSearchException.Kind.NETWORK, 0, null,
                                              "Network error: " + e.getMessage(), e));
      }

      @Override
      public void onResponse(@NonNull Call call, @NonNull Response response)
      {
        try (response)
        {
          handleResponse(response, result);
        }
      }
    });
    return result;
  }

  /** Builds the request URL; package-private for tests. */
  @NonNull
  HttpUrl buildUrl(@NonNull OnlineSearchRequest request)
  {
    final HttpUrl.Builder url = mApiUrl.newBuilder()
                                    .addQueryParameter("q", request.getQuery())
                                    .addQueryParameter("limit", Integer.toString(request.getLimit()));
    final String language = request.getLanguage();
    if (language != null && mConfig.getLanguages().contains(language))
      url.addQueryParameter("lang", language);
    if (request.hasLocationBias())
    {
      url.addQueryParameter("lat", formatCoordinate(request.getBiasLat()));
      url.addQueryParameter("lon", formatCoordinate(request.getBiasLon()));
    }
    return url.build();
  }

  @NonNull
  private static String formatCoordinate(@Nullable Double value)
  {
    return String.format(Locale.ROOT, "%." + OnlineSearchRequest.BIAS_DECIMALS + "f", value);
  }

  private static void handleResponse(@NonNull Response response, @NonNull AsyncResult<List<OnlineSearchResult>> result)
  {
    final int code = response.code();
    final String body;
    try
    {
      final ResponseBody responseBody = response.body();
      body = responseBody != null ? responseBody.string() : "";
    }
    catch (IOException e)
    {
      // E.g. a read timeout or a connection reset while reading the body.
      result.fail(new OnlineSearchException(OnlineSearchException.Kind.NETWORK, code, null,
                                            "Network error: " + e.getMessage(), e));
      return;
    }

    if (!response.isSuccessful())
    {
      final String serverMessage = PhotonMapper.parseServerMessage(body);
      final OnlineSearchException.Kind kind =
          code >= 500 ? OnlineSearchException.Kind.SERVER_ERROR : OnlineSearchException.Kind.CLIENT_ERROR;
      result.fail(new OnlineSearchException(
          kind, code, serverMessage, "HTTP " + code + (serverMessage != null ? ": " + serverMessage : ""), null));
      return;
    }

    final List<OnlineSearchResult> results;
    try
    {
      results = PhotonMapper.toResults(body);
    }
    catch (RuntimeException e)
    {
      result.fail(new OnlineSearchException(OnlineSearchException.Kind.INVALID_RESPONSE, code, null,
                                            "Invalid response: " + e.getMessage(), e));
      return;
    }
    result.complete(results);
  }
}
