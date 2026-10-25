package app.organicmaps.sdk.transit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.util.concurrency.AsyncResult;
import java.io.IOException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Requests and error handling of {@link OnlineTransitService} against a local mock server with recorded responses. */
public class OnlineTransitServiceTest
{
  private static final String USER_AGENT = "better-maps-test/1.0 (+https://example.org)";
  private static final Executor DIRECT = Runnable::run;
  private static final TransitLocation HBF = TransitLocation.fromCoordinates(52.525, 13.3694);
  private static final TransitLocation ALEX = TransitLocation.fromCoordinates(52.5219, 13.4132);

  private MockWebServer mServer;
  private OnlineTransitService mService;

  @Before
  public void setUp() throws IOException
  {
    mServer = new MockWebServer();
    mServer.start();
    mService = createService(TransitServiceConfig.builder(USER_AGENT).setBaseUrl(mServer.url("/").toString()).build());
  }

  @After
  public void tearDown() throws IOException
  {
    mServer.shutdown();
  }

  @NonNull
  private static OnlineTransitService createService(@NonNull TransitServiceConfig config)
  {
    return new OnlineTransitService(config, DIRECT, new OkHttpClient.Builder());
  }

  private void enqueueJson(int code, @NonNull String body)
  {
    mServer.enqueue(
        new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body));
  }

  @NonNull
  private HttpUrl takeRequestUrl() throws InterruptedException
  {
    final RecordedRequest request = mServer.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull("no request received", request);
    assertEquals("GET", request.getMethod());
    assertEquals(USER_AGENT, request.getHeader("User-Agent"));
    return request.getRequestUrl();
  }

  @NonNull
  private static Set<String> paramNames(@NonNull HttpUrl url)
  {
    return new TreeSet<>(url.queryParameterNames());
  }

  // ---------------------------------------------------------------------------------------------
  // /plan request parameters

  @Test
  public void planWithDefaultOptionsSendsOnlyRequiredAndDefaultParameters() throws Exception
  {
    enqueueJson(200, Fixtures.read("plan_hbf_alex.json"));
    final TransitPlan plan = await(mService.plan(HBF, ALEX, null, false, null, TransitRoutingOptions.defaults()));
    assertEquals(7, plan.getItineraries().size());

    final HttpUrl url = takeRequestUrl();
    assertEquals("/api/v6/plan", url.encodedPath());
    assertEquals(Set.of("fromPlace", "toPlace", "numItineraries", "useRoutedTransfers", "detailedTransfers"),
                 paramNames(url));
    assertEquals("52.525000,13.369400", url.queryParameter("fromPlace"));
    assertEquals("52.521900,13.413200", url.queryParameter("toPlace"));
    assertEquals("5", url.queryParameter("numItineraries"));
    assertEquals("true", url.queryParameter("useRoutedTransfers"));
    assertEquals("true", url.queryParameter("detailedTransfers"));
  }

  @Test
  public void planWithAllOptions() throws Exception
  {
    enqueueJson(200, Fixtures.read("plan_regional.json"));
    final TransitRoutingOptions options =
        TransitRoutingOptions.builder()
            .setMaxTransfers(2)
            .setMinTransferTimeMinutes(3)
            .setAdditionalTransferTimeMinutes(2)
            .setTransferTimeFactor(1.5)
            .setUseRoutedTransfers(false)
            .setDetailedTransfers(false)
            .setPedestrianProfile(TransitRoutingOptions.PedestrianProfile.WHEELCHAIR)
            .setPedestrianSpeed(1.0)
            .setRequireBikeTransport(true)
            .setTransitModes(EnumSet.of(TransitMode.BUS, TransitMode.TRAM, TransitMode.REGIONAL_RAIL))
            .setPreTransitModes(EnumSet.of(TransitMode.WALK, TransitMode.BIKE))
            .setPostTransitModes(EnumSet.of(TransitMode.WALK))
            .setMaxPreTransitTimeSeconds(1200)
            .setMaxPostTransitTimeSeconds(900)
            .setNumItineraries(3)
            .setSearchWindowSeconds(3600)
            .setRealtimeMode(TransitRoutingOptions.RealtimeMode.OFF)
            .build();
    await(mService.plan(HBF, TransitLocation.fromCoordinates(51.7563, 14.3329), null, false, null, options));

    final HttpUrl url = takeRequestUrl();
    assertEquals(
        Set.of("fromPlace", "toPlace", "numItineraries", "useRoutedTransfers", "detailedTransfers", "maxTransfers",
               "minTransferTime", "additionalTransferTime", "transferTimeFactor", "pedestrianProfile",
               "pedestrianSpeed", "requireBikeTransport", "transitModes", "preTransitModes", "postTransitModes",
               "maxPreTransitTime", "maxPostTransitTime", "searchWindow", "realtimeMode"),
        paramNames(url));
    assertEquals("2", url.queryParameter("maxTransfers"));
    assertEquals("3", url.queryParameter("minTransferTime"));
    assertEquals("2", url.queryParameter("additionalTransferTime"));
    assertEquals("1.5", url.queryParameter("transferTimeFactor"));
    assertEquals("false", url.queryParameter("useRoutedTransfers"));
    assertEquals("false", url.queryParameter("detailedTransfers"));
    assertEquals("WHEELCHAIR", url.queryParameter("pedestrianProfile"));
    assertEquals("1.0", url.queryParameter("pedestrianSpeed"));
    assertEquals("true", url.queryParameter("requireBikeTransport"));
    // EnumSet keeps declaration order of TransitMode.
    assertEquals("TRAM,BUS,REGIONAL_RAIL", url.queryParameter("transitModes"));
    assertEquals("WALK,BIKE", url.queryParameter("preTransitModes"));
    assertEquals("WALK", url.queryParameter("postTransitModes"));
    assertEquals("1200", url.queryParameter("maxPreTransitTime"));
    assertEquals("900", url.queryParameter("maxPostTransitTime"));
    assertEquals("3", url.queryParameter("numItineraries"));
    assertEquals("3600", url.queryParameter("searchWindow"));
    assertEquals("OFF", url.queryParameter("realtimeMode"));
  }

  @Test
  public void realtimeModesAreSentByName() throws Exception
  {
    for (TransitRoutingOptions.RealtimeMode mode : TransitRoutingOptions.RealtimeMode.values())
    {
      enqueueJson(200, Fixtures.read("plan_empty.json"));
      await(mService.plan(HBF, ALEX, null, false, null, TransitRoutingOptions.builder().setRealtimeMode(mode).build()));
      assertEquals(mode.name(), takeRequestUrl().queryParameter("realtimeMode"));
    }
  }

  @Test
  public void planWithStopIdTimeArriveByAndCursor() throws Exception
  {
    enqueueJson(200, Fixtures.read("plan_stopid_arriveby.json"));
    final TransitPlan plan = await(mService.plan(
        TransitLocation.fromStopId("de-VBB_de:11000:900100003"), TransitLocation.fromCoordinates(52.3917, 13.0661),
        Instant.parse("2026-10-25T18:00:00Z"), true, "LATER|1792946700", TransitRoutingOptions.defaults()));
    assertEquals("de-VBB_de:11000:900100003", plan.getFrom().getStopId());

    final HttpUrl url = takeRequestUrl();
    assertEquals("de-VBB_de:11000:900100003", url.queryParameter("fromPlace"));
    assertEquals("52.391700,13.066100", url.queryParameter("toPlace"));
    assertEquals("true", url.queryParameter("arriveBy"));
    assertEquals("LATER|1792946700", url.queryParameter("pageCursor"));
    assertEquals(Instant.parse("2026-10-25T18:00:00Z"), Instant.parse(url.queryParameter("time")));
    // The cursor contains '|' and must be percent-encoded.
    assertTrue(url.encodedQuery(), url.encodedQuery().contains("pageCursor=LATER%7C1792946700"));
  }

  @Test
  public void departByAndEmptyCursorAreNotSent() throws Exception
  {
    enqueueJson(200, Fixtures.read("plan_hbf_alex.json"));
    await(mService.plan(HBF, ALEX, null, false, "", TransitRoutingOptions.defaults()));
    final HttpUrl url = takeRequestUrl();
    assertNull(url.queryParameter("arriveBy"));
    assertNull(url.queryParameter("pageCursor"));
    assertNull(url.queryParameter("time"));
  }

  @Test
  public void pagingUsesCursorFromPreviousResult() throws Exception
  {
    enqueueJson(200, Fixtures.read("plan_hbf_alex.json"));
    enqueueJson(200, Fixtures.read("plan_hbf_alex_next.json"));
    final TransitRoutingOptions options = TransitRoutingOptions.defaults();
    final TransitPlan first = await(mService.plan(HBF, ALEX, null, false, null, options));
    final TransitPlan next = await(mService.plan(HBF, ALEX, null, false, first.getNextPageCursor(), options));
    assertEquals(2, next.getItineraries().size());

    final HttpUrl firstUrl = takeRequestUrl();
    final HttpUrl nextUrl = takeRequestUrl();
    assertEquals("LATER|1792946700", nextUrl.queryParameter("pageCursor"));
    // Apart from the cursor the query is unchanged, as required by MOTIS.
    assertNull(firstUrl.queryParameter("pageCursor"));
    assertEquals(firstUrl, nextUrl.newBuilder().removeAllQueryParameters("pageCursor").build());
  }

  @Test
  public void baseUrlWithPathPrefix() throws Exception
  {
    final OnlineTransitService service =
        createService(TransitServiceConfig.builder(USER_AGENT).setBaseUrl(mServer.url("/motis/").toString()).build());
    enqueueJson(200, Fixtures.read("plan_empty.json"));
    await(service.plan(HBF, ALEX, null, false, null, TransitRoutingOptions.defaults()));
    assertEquals("/motis/api/v6/plan", takeRequestUrl().encodedPath());
  }

  @Test
  public void emptyPlanIsSuccess() throws Exception
  {
    enqueueJson(200, Fixtures.read("plan_empty.json"));
    final TransitPlan plan = await(mService.plan(HBF, ALEX, null, false, null, TransitRoutingOptions.defaults()));
    assertTrue(plan.isEmpty());
  }

  // ---------------------------------------------------------------------------------------------
  // Errors

  @Test
  public void notFoundWithServerMessage() throws Exception
  {
    enqueueJson(404, Fixtures.read("error_bad_place.json"));
    final TransitServiceException e = awaitFailure(mService.plan(TransitLocation.fromStopId("not-a-stop"), ALEX, null,
                                                                 false, null, TransitRoutingOptions.defaults()));
    assertEquals(TransitServiceException.Kind.CLIENT_ERROR, e.getKind());
    assertEquals(404, e.getHttpCode());
    assertEquals("unknown feed id \"\"", e.getServerMessage());
    assertFalse(e.isRetryable());
  }

  @Test
  public void badRequestWithServerMessage() throws Exception
  {
    enqueueJson(400, Fixtures.read("error_bad_request.json"));
    final TransitServiceException e =
        awaitFailure(mService.plan(HBF, ALEX, null, false, null, TransitRoutingOptions.defaults()));
    assertEquals(TransitServiceException.Kind.CLIENT_ERROR, e.getKind());
    assertEquals(400, e.getHttpCode());
    assertEquals("parameter \"time\": failed to parse timestamp \"yesterday\"", e.getServerMessage());
    assertTrue(e.getMessage().contains("400"));
  }

  @Test
  public void tooManyRequestsIsRetryable() throws Exception
  {
    mServer.enqueue(new MockResponse().setResponseCode(429).setBody("slow down"));
    final TransitServiceException e =
        awaitFailure(mService.plan(HBF, ALEX, null, false, null, TransitRoutingOptions.defaults()));
    assertEquals(TransitServiceException.Kind.CLIENT_ERROR, e.getKind());
    assertEquals(429, e.getHttpCode());
    assertNull(e.getServerMessage());
    assertTrue(e.isRetryable());
  }

  @Test
  public void serverErrorWithHtmlBody() throws Exception
  {
    mServer.enqueue(new MockResponse()
                        .setResponseCode(502)
                        .setHeader("Content-Type", "text/html")
                        .setBody("<html><body>Bad Gateway</body></html>"));
    final TransitServiceException e =
        awaitFailure(mService.plan(HBF, ALEX, null, false, null, TransitRoutingOptions.defaults()));
    assertEquals(TransitServiceException.Kind.SERVER_ERROR, e.getKind());
    assertEquals(502, e.getHttpCode());
    assertNull(e.getServerMessage());
    assertTrue(e.isRetryable());
  }

  @Test
  public void serverErrorWithJsonBody() throws Exception
  {
    enqueueJson(500, "{\"error\":\"internal error\"}");
    final TransitServiceException e = awaitFailure(mService.stopTimes("de-VBB_de:11000:900100003", 5));
    assertEquals(TransitServiceException.Kind.SERVER_ERROR, e.getKind());
    assertEquals("internal error", e.getServerMessage());
  }

  @Test
  public void connectionDropIsNetworkError() throws Exception
  {
    mServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
    final TransitServiceException e =
        awaitFailure(mService.plan(HBF, ALEX, null, false, null, TransitRoutingOptions.defaults()));
    assertEquals(TransitServiceException.Kind.NETWORK, e.getKind());
    assertEquals(0, e.getHttpCode());
    assertTrue(e.isRetryable());
    assertTrue(e.getCause() instanceof IOException);
  }

  @Test
  public void unreachableServerIsNetworkError() throws Exception
  {
    final String url = mServer.url("/").toString();
    mServer.shutdown();
    final OnlineTransitService service =
        createService(TransitServiceConfig.builder(USER_AGENT).setBaseUrl(url).setConnectTimeoutMs(2000).build());
    final TransitServiceException e = awaitFailure(service.reverseGeocodeStops(52.52, 13.41));
    assertEquals(TransitServiceException.Kind.NETWORK, e.getKind());
  }

  @Test
  public void readTimeoutIsNetworkError() throws Exception
  {
    final OnlineTransitService service = createService(
        TransitServiceConfig.builder(USER_AGENT).setBaseUrl(mServer.url("/").toString()).setReadTimeoutMs(200).build());
    mServer.enqueue(new MockResponse().setBody(Fixtures.read("plan_empty.json")).setBodyDelay(3, TimeUnit.SECONDS));
    final TransitServiceException e =
        awaitFailure(service.plan(HBF, ALEX, null, false, null, TransitRoutingOptions.defaults()));
    assertEquals(TransitServiceException.Kind.NETWORK, e.getKind());
  }

  @Test
  public void callTimeoutIsNetworkError() throws Exception
  {
    final OnlineTransitService service = createService(
        TransitServiceConfig.builder(USER_AGENT).setBaseUrl(mServer.url("/").toString()).setCallTimeoutMs(300).build());
    mServer.enqueue(new MockResponse().setBody(Fixtures.read("plan_empty.json")).setHeadersDelay(3, TimeUnit.SECONDS));
    final TransitServiceException e =
        awaitFailure(service.plan(HBF, ALEX, null, false, null, TransitRoutingOptions.defaults()));
    assertEquals(TransitServiceException.Kind.NETWORK, e.getKind());
  }

  @Test
  public void malformedJsonIsInvalidResponse() throws Exception
  {
    enqueueJson(200, "{\"itineraries\": [");
    final TransitServiceException e =
        awaitFailure(mService.plan(HBF, ALEX, null, false, null, TransitRoutingOptions.defaults()));
    assertEquals(TransitServiceException.Kind.INVALID_RESPONSE, e.getKind());
    assertEquals(200, e.getHttpCode());
  }

  @Test
  public void missingRequiredFieldsIsInvalidResponse() throws Exception
  {
    // An itinerary without legs/times violates the schema.
    enqueueJson(200,
                "{\"from\":{\"name\":\"START\",\"lat\":1,\"lon\":2},\"to\":{\"name\":\"END\",\"lat\":1,\"lon\":2},"
                    + "\"direct\":[],\"itineraries\":[{\"id\":\"x\"}],\"requestParameters\":{},\"debugOutput\":{}}");
    final TransitServiceException e =
        awaitFailure(mService.plan(HBF, ALEX, null, false, null, TransitRoutingOptions.defaults()));
    assertEquals(TransitServiceException.Kind.INVALID_RESPONSE, e.getKind());
  }

  @Test
  public void htmlInsteadOfJsonIsInvalidResponse() throws Exception
  {
    mServer.enqueue(new MockResponse().setHeader("Content-Type", "text/html").setBody("<html>captive portal</html>"));
    final TransitServiceException e = awaitFailure(mService.reverseGeocodeStops(52.52, 13.41));
    assertEquals(TransitServiceException.Kind.INVALID_RESPONSE, e.getKind());
  }

  // ---------------------------------------------------------------------------------------------
  // Stops and departures

  @Test
  public void reverseGeocodeStops() throws Exception
  {
    enqueueJson(200, Fixtures.read("reverse_geocode_alex.json"));
    final List<TransitStop> stops = await(mService.reverseGeocodeStops(52.5219, 13.4132));
    assertEquals(5, stops.size());
    assertEquals("de-VBB_de:11000:900100024::1", stops.get(0).getId());

    final HttpUrl url = takeRequestUrl();
    assertEquals("/api/v1/reverse-geocode", url.encodedPath());
    assertEquals("52.521900,13.413200", url.queryParameter("place"));
    assertEquals("STOP", url.queryParameter("type"));
  }

  @Test
  public void stopTimes() throws Exception
  {
    enqueueJson(200, Fixtures.read("stoptimes_alex.json"));
    final TransitDepartures departures = await(mService.stopTimes("de-VBB_de:11000:900100003", 20));
    assertEquals(21, departures.getDepartures().size());

    final HttpUrl url = takeRequestUrl();
    assertEquals("/api/v6/stoptimes", url.encodedPath());
    assertEquals("de-VBB_de:11000:900100003", url.queryParameter("stopId"));
    assertEquals("20", url.queryParameter("n"));
  }

  @Test
  public void stopTimesRejectsInvalidLimitWithoutRequest() throws Exception
  {
    final Throwable e = awaitError(mService.stopTimes("x", 0));
    assertTrue(e instanceof IllegalArgumentException);
    assertEquals(0, mServer.getRequestCount());
  }

  @Test
  public void closestStopWithTimesUsesNearestStop() throws Exception
  {
    enqueueJson(200, Fixtures.read("reverse_geocode_alex.json"));
    enqueueJson(200, Fixtures.read("stoptimes_alex.json"));
    final TransitDepartures departures = await(mService.closestStopWithTimes(52.5219, 13.4132, 3));
    assertEquals("S+U Alexanderplatz Bhf (Berlin)", departures.getStop().getName());
    assertEquals("/api/v1/reverse-geocode", takeRequestUrl().encodedPath());
    final HttpUrl stopTimesUrl = takeRequestUrl();
    assertEquals("de-VBB_de:11000:900100024::1", stopTimesUrl.queryParameter("stopId"));
    assertEquals("3", stopTimesUrl.queryParameter("n"));
  }

  @Test
  public void closestStopWithTimesWithoutStops() throws Exception
  {
    enqueueJson(200, "[]");
    final TransitServiceException e = awaitFailure(mService.closestStopWithTimes(40.0, -30.0, 3));
    assertEquals(TransitServiceException.Kind.NO_RESULTS, e.getKind());
    assertEquals(1, mServer.getRequestCount());
  }

  @Test
  public void closestStopWithTimesPropagatesSecondFailure() throws Exception
  {
    enqueueJson(200, Fixtures.read("reverse_geocode_alex.json"));
    enqueueJson(404, Fixtures.read("error_bad_place.json"));
    final TransitServiceException e = awaitFailure(mService.closestStopWithTimes(52.5219, 13.4132, 3));
    assertEquals(TransitServiceException.Kind.CLIENT_ERROR, e.getKind());
    assertEquals(404, e.getHttpCode());
  }

  @Test
  public void connectionDropDuringBodyIsNetworkError() throws Exception
  {
    mServer.enqueue(new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(Fixtures.read("plan_hbf_alex.json"))
                        .throttleBody(4096, 10, TimeUnit.MILLISECONDS)
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
    final TransitServiceException e =
        awaitFailure(mService.plan(HBF, ALEX, null, false, null, TransitRoutingOptions.defaults()));
    assertEquals(TransitServiceException.Kind.NETWORK, e.getKind());
  }

  @Test
  public void networkFailureClassification()
  {
    assertTrue(OnlineTransitService.isNetworkFailure(new java.net.SocketTimeoutException()));
    assertTrue(OnlineTransitService.isNetworkFailure(
        new com.google.gson.JsonSyntaxException(new java.net.SocketTimeoutException("timeout"))));
    assertTrue(OnlineTransitService.isNetworkFailure(new javax.net.ssl.SSLHandshakeException("bad cert")));
    assertFalse(OnlineTransitService.isNetworkFailure(
        new com.google.gson.JsonSyntaxException(new com.google.gson.stream.MalformedJsonException("x"))));
    assertFalse(OnlineTransitService.isNetworkFailure(
        new com.google.gson.JsonSyntaxException(new java.io.EOFException("End of input"))));
    assertFalse(OnlineTransitService.isNetworkFailure(new IllegalArgumentException("missing field")));
    assertFalse(OnlineTransitService.isNetworkFailure(null));
  }

  @Test
  public void parseServerMessage()
  {
    assertEquals("x", OnlineTransitService.parseServerMessage("{\"error\":\"x\"}"));
    assertNull(OnlineTransitService.parseServerMessage("{\"error\":{\"nested\":1}}"));
    assertNull(OnlineTransitService.parseServerMessage("{\"message\":\"x\"}"));
    assertNull(OnlineTransitService.parseServerMessage("<html>"));
    assertNull(OnlineTransitService.parseServerMessage(""));
    assertNull(OnlineTransitService.parseServerMessage(null));
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers

  private static final class Outcome<T>
  {
    final CountDownLatch mLatch = new CountDownLatch(1);
    volatile T mValue;
    volatile Throwable mError;
  }

  @NonNull
  private static <T> Outcome<T> wait(@NonNull AsyncResult<T> result) throws InterruptedException
  {
    final Outcome<T> outcome = new Outcome<>();
    result
        .onSuccess(value -> {
          outcome.mValue = value;
          outcome.mLatch.countDown();
        })
        .onFailure(error -> {
          outcome.mError = error;
          outcome.mLatch.countDown();
        });
    assertTrue("timed out", outcome.mLatch.await(10, TimeUnit.SECONDS));
    return outcome;
  }

  private static <T> T await(@NonNull AsyncResult<T> result) throws InterruptedException
  {
    final Outcome<T> outcome = wait(result);
    if (outcome.mError != null)
      throw new AssertionError("Unexpected failure", outcome.mError);
    return outcome.mValue;
  }

  @NonNull
  private static Throwable awaitError(@NonNull AsyncResult<?> result) throws InterruptedException
  {
    final Outcome<?> outcome = wait(result);
    if (outcome.mError == null)
      fail("Expected a failure, got " + outcome.mValue);
    return outcome.mError;
  }

  @NonNull
  private static TransitServiceException awaitFailure(@NonNull AsyncResult<?> result) throws InterruptedException
  {
    final Throwable error = awaitError(result);
    if (!(error instanceof TransitServiceException))
      throw new AssertionError("Expected TransitServiceException", error);
    return (TransitServiceException) error;
  }
}
