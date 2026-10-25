package app.organicmaps.sdk.search.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.util.concurrency.AsyncResult;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Requests, mapping and error handling of {@link PhotonSearchProvider} against a local mock server. */
public class PhotonSearchProviderTest
{
  private static final String USER_AGENT = "better-maps/1.0 (CoMaps fork; +https://github.com/WayneNani/better-maps)";
  private static final Executor DIRECT = Runnable::run;

  private MockWebServer mServer;
  private PhotonSearchProvider mProvider;

  @Before
  public void setUp() throws IOException
  {
    mServer = new MockWebServer();
    mServer.start();
    mProvider = create(PhotonConfig.builder(USER_AGENT).setBaseUrl(mServer.url("/").toString()).build());
  }

  @After
  public void tearDown() throws IOException
  {
    mServer.shutdown();
  }

  @NonNull
  private static PhotonSearchProvider create(@NonNull PhotonConfig config)
  {
    return new PhotonSearchProvider(config, DIRECT, new OkHttpClient.Builder());
  }

  private void enqueueJson(int code, @NonNull String body)
  {
    mServer.enqueue(
        new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body));
  }

  @NonNull
  private RecordedRequest takeRequest() throws InterruptedException
  {
    final RecordedRequest request = mServer.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull("no request received", request);
    assertEquals("GET", request.getMethod());
    assertEquals(USER_AGENT, request.getHeader("User-Agent"));
    return request;
  }

  @NonNull
  private static Set<String> paramNames(@NonNull HttpUrl url)
  {
    return new TreeSet<>(url.queryParameterNames());
  }

  @Test
  public void sendsQueryLanguageLimitAndRoundedBias() throws Exception
  {
    enqueueJson(200, PhotonFixtures.read("search_marienplatz_muenchen_de.json"));
    final OnlineSearchRequest request = OnlineSearchRequest.builder("  Marienplatz   München ")
                                            .setLanguage("de-DE")
                                            .setLimit(10)
                                            .setLocationBias(52.520008, 13.404954)
                                            .build();
    final List<OnlineSearchResult> results = await(mProvider.search(request));
    assertEquals(2, results.size());

    final HttpUrl url = takeRequest().getRequestUrl();
    assertEquals("/api", url.encodedPath());
    assertEquals("Marienplatz München", url.queryParameter("q"));
    assertEquals("10", url.queryParameter("limit"));
    assertEquals("de", url.queryParameter("lang"));
    // Only the rounded position (about 1 km) leaves the device.
    assertEquals("52.52", url.queryParameter("lat"));
    assertEquals("13.40", url.queryParameter("lon"));
    assertEquals(new TreeSet<>(List.of("q", "limit", "lang", "lat", "lon")), paramNames(url));
  }

  @Test
  public void withoutBiasNoPositionIsSent() throws Exception
  {
    enqueueJson(200, PhotonFixtures.read("search_empty.json"));
    await(mProvider.search(OnlineSearchRequest.builder("Marienplatz").setLanguage("en").build()));
    final HttpUrl url = takeRequest().getRequestUrl();
    assertEquals(new TreeSet<>(List.of("q", "limit", "lang")), paramNames(url));
    assertEquals(Integer.toString(OnlineSearchRequest.DEFAULT_LIMIT), url.queryParameter("limit"));
  }

  @Test
  public void unsupportedLanguageIsNotSent() throws Exception
  {
    // The public instance answers HTTP 400 for unknown languages; local names are better than an error.
    enqueueJson(200, PhotonFixtures.read("search_empty.json"));
    await(mProvider.search(OnlineSearchRequest.builder("Marienplatz").setLanguage("pl-PL").build()));
    assertNull(takeRequest().getRequestUrl().queryParameter("lang"));

    enqueueJson(200, PhotonFixtures.read("search_empty.json"));
    await(mProvider.search(OnlineSearchRequest.builder("Marienplatz").build()));
    assertNull(takeRequest().getRequestUrl().queryParameter("lang"));
  }

  @Test
  public void configuredLanguagesOfAnOwnServer() throws Exception
  {
    final PhotonSearchProvider provider = create(PhotonConfig.builder(USER_AGENT)
                                                     .setBaseUrl(mServer.url("/").toString())
                                                     .setLanguages(Set.of("pl", "IT"))
                                                     .build());
    enqueueJson(200, PhotonFixtures.read("search_empty.json"));
    await(provider.search(OnlineSearchRequest.builder("Rynek").setLanguage("pl").build()));
    assertEquals("pl", takeRequest().getRequestUrl().queryParameter("lang"));
    enqueueJson(200, PhotonFixtures.read("search_empty.json"));
    await(provider.search(OnlineSearchRequest.builder("Rynek").setLanguage("de").build()));
    assertNull(takeRequest().getRequestUrl().queryParameter("lang"));
  }

  @Test
  public void baseUrlWithPathAndApiSuffix() throws Exception
  {
    final PhotonSearchProvider provider =
        create(PhotonConfig.builder(USER_AGENT).setBaseUrl(mServer.url("/photon/api/").toString()).build());
    enqueueJson(200, PhotonFixtures.read("search_empty.json"));
    await(provider.search(OnlineSearchRequest.builder("Marienplatz").build()));
    assertEquals("/photon/api", takeRequest().getRequestUrl().encodedPath());
    assertTrue(provider.getName(), provider.getName().startsWith("Photon ("));
  }

  @Test
  public void defaultServerIsNamedPhoton()
  {
    final PhotonSearchProvider provider = create(PhotonConfig.builder(USER_AGENT).build());
    assertEquals("Photon", provider.getName());
    assertEquals(PhotonSearchProvider.ID, provider.getId());
    assertEquals("https://www.openstreetmap.org/copyright", provider.getAttributionUrl());
    assertEquals("https://photon.komoot.io/api?q=K%C3%B6ln%20Dom&limit=5",
                 provider.buildUrl(OnlineSearchRequest.builder("Köln Dom").setLimit(5).build()).toString());
  }

  @Test
  public void mapsRecordedResults() throws Exception
  {
    enqueueJson(200, PhotonFixtures.read("search_marienplatz_en.json"));
    final List<OnlineSearchResult> results =
        await(mProvider.search(OnlineSearchRequest.builder("Marienplatz").setLanguage("en").build()));
    assertEquals(3, results.size());
    assertEquals("W436990284", results.get(0).getOsmRef());
    assertEquals("R7366154", results.get(1).getOsmRef());
    assertEquals("railway-station", results.get(2).getCategory());
    assertEquals("Munich", results.get(2).getCity());
  }

  @Test
  public void emptyResult() throws Exception
  {
    enqueueJson(200, PhotonFixtures.read("search_empty.json"));
    assertTrue(await(mProvider.search(OnlineSearchRequest.builder("xqzvbnwplkjh").build())).isEmpty());
  }

  @Test
  public void badRequestWithServerMessage() throws Exception
  {
    enqueueJson(400, PhotonFixtures.read("error_unsupported_language.json"));
    final OnlineSearchException e = awaitFailure(mProvider.search(OnlineSearchRequest.builder("Marienplatz").build()));
    assertEquals(OnlineSearchException.Kind.CLIENT_ERROR, e.getKind());
    assertEquals(400, e.getHttpCode());
    assertEquals("lang: Language is not supported. Supported are: default, de, en, fr", e.getServerMessage());
    assertFalse(e.isRetryable());
  }

  @Test
  public void serverErrorIsRetryable() throws Exception
  {
    mServer.enqueue(new MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"));
    final OnlineSearchException e = awaitFailure(mProvider.search(OnlineSearchRequest.builder("Marienplatz").build()));
    assertEquals(OnlineSearchException.Kind.SERVER_ERROR, e.getKind());
    assertEquals(502, e.getHttpCode());
    assertNull(e.getServerMessage());
    assertTrue(e.isRetryable());
  }

  @Test
  public void tooManyRequestsIsRetryable() throws Exception
  {
    enqueueJson(429, "{\"message\": \"Too many requests\"}");
    final OnlineSearchException e = awaitFailure(mProvider.search(OnlineSearchRequest.builder("Marienplatz").build()));
    assertEquals(OnlineSearchException.Kind.CLIENT_ERROR, e.getKind());
    assertEquals("Too many requests", e.getServerMessage());
    assertTrue(e.isRetryable());
  }

  @Test
  public void invalidJson() throws Exception
  {
    enqueueJson(200, "{\"type\": \"FeatureCollection\", \"features\": [");
    final OnlineSearchException e = awaitFailure(mProvider.search(OnlineSearchRequest.builder("Marienplatz").build()));
    assertEquals(OnlineSearchException.Kind.INVALID_RESPONSE, e.getKind());
    assertEquals(200, e.getHttpCode());
  }

  @Test
  public void htmlInsteadOfJson() throws Exception
  {
    mServer.enqueue(new MockResponse().setBody("<html><body>Captive portal</body></html>"));
    final OnlineSearchException e = awaitFailure(mProvider.search(OnlineSearchRequest.builder("Marienplatz").build()));
    assertEquals(OnlineSearchException.Kind.INVALID_RESPONSE, e.getKind());
  }

  @Test
  public void jsonWithoutFeatures() throws Exception
  {
    enqueueJson(200, "{\"type\": \"FeatureCollection\"}");
    final OnlineSearchException e = awaitFailure(mProvider.search(OnlineSearchRequest.builder("Marienplatz").build()));
    assertEquals(OnlineSearchException.Kind.INVALID_RESPONSE, e.getKind());
  }

  @Test
  public void connectionDropped() throws Exception
  {
    mServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
    final OnlineSearchException e = awaitFailure(mProvider.search(OnlineSearchRequest.builder("Marienplatz").build()));
    assertEquals(OnlineSearchException.Kind.NETWORK, e.getKind());
    assertTrue(e.isRetryable());
  }

  @Test
  public void readTimeout() throws Exception
  {
    final PhotonSearchProvider provider =
        create(PhotonConfig.builder(USER_AGENT).setBaseUrl(mServer.url("/").toString()).setReadTimeoutMs(300).build());
    mServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
    final OnlineSearchException e = awaitFailure(provider.search(OnlineSearchRequest.builder("Marienplatz").build()));
    assertEquals(OnlineSearchException.Kind.NETWORK, e.getKind());
  }

  @Test
  public void bodyCutOff() throws Exception
  {
    // The connection breaks while the body is read.
    mServer.enqueue(new MockResponse()
                        .setBody(PhotonFixtures.read("search_marienplatz_en.json"))
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
    final OnlineSearchException e = awaitFailure(mProvider.search(OnlineSearchRequest.builder("Marienplatz").build()));
    assertTrue(e.getKind() == OnlineSearchException.Kind.NETWORK
               || e.getKind() == OnlineSearchException.Kind.INVALID_RESPONSE);
  }

  @Test
  public void unreachableServer() throws Exception
  {
    final int port = mServer.getPort();
    mServer.shutdown();
    final PhotonSearchProvider provider = create(
        PhotonConfig.builder(USER_AGENT).setBaseUrl("http://127.0.0.1:" + port).setConnectTimeoutMs(2000).build());
    final OnlineSearchException e = awaitFailure(provider.search(OnlineSearchRequest.builder("Marienplatz").build()));
    assertEquals(OnlineSearchException.Kind.NETWORK, e.getKind());
    assertEquals(0, e.getHttpCode());
  }

  @Test
  public void resultsAreDeliveredOnTheExecutor() throws Exception
  {
    final List<Runnable> posted = Collections.synchronizedList(new java.util.ArrayList<>());
    final PhotonSearchProvider provider =
        new PhotonSearchProvider(PhotonConfig.builder(USER_AGENT).setBaseUrl(mServer.url("/").toString()).build(),
                                 posted::add, new OkHttpClient.Builder());
    enqueueJson(200, PhotonFixtures.read("search_empty.json"));
    final AtomicReference<List<OnlineSearchResult>> value = new AtomicReference<>();
    final AsyncResult<List<OnlineSearchResult>> result =
        provider.search(OnlineSearchRequest.builder("Marienplatz").build());
    result.onSuccess(value::set);
    final long end = System.currentTimeMillis() + 5000;
    while (!result.isDone() && System.currentTimeMillis() < end)
      Thread.sleep(10);
    assertTrue(result.isDone());
    // Nothing runs until the executor runs the posted handler.
    assertNull(value.get());
    for (Runnable r : new java.util.ArrayList<>(posted))
      r.run();
    assertNotNull(value.get());
  }

  @NonNull
  static <T> T await(@NonNull AsyncResult<T> result) throws InterruptedException
  {
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicReference<T> value = new AtomicReference<>();
    final AtomicReference<Throwable> error = new AtomicReference<>();
    result.onSuccess(v -> {
      value.set(v);
      latch.countDown();
    });
    result.onFailure(e -> {
      error.set(e);
      latch.countDown();
    });
    assertTrue("timeout", latch.await(10, TimeUnit.SECONDS));
    if (error.get() != null)
      throw new AssertionError("unexpected failure", error.get());
    return value.get();
  }

  @NonNull
  static OnlineSearchException awaitFailure(@NonNull AsyncResult<?> result) throws InterruptedException
  {
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicReference<Throwable> error = new AtomicReference<>();
    result.onSuccess(v -> latch.countDown());
    result.onFailure(e -> {
      error.set(e);
      latch.countDown();
    });
    assertTrue("timeout", latch.await(10, TimeUnit.SECONDS));
    if (!(error.get() instanceof OnlineSearchException))
      fail("expected OnlineSearchException, got " + error.get());
    return (OnlineSearchException) error.get();
  }
}
