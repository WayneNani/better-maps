package app.organicmaps.sdk.crowdtraffic;

import static app.organicmaps.sdk.crowdtraffic.TestGateway.hex;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CrowdTrafficSenderTest
{
  private static final String PROBLEM_OHTTP_KEY =
      "{\"title\":\"key identifier unknown\",\"type\":\"https://iana.org/assignments/http-problem-types#ohttp-key\"}\n";

  /** Relay + gateway: serves keys of the current gateway key and answers requests as configured. */
  private final class FakeRelay extends Dispatcher
  {
    TestGateway gateway = new TestGateway(1, (byte) 1);
    /** Key served by /ohttp-keys (may differ from the gateway key to simulate a rotation). */
    TestGateway served = gateway;
    int innerStatus = 204;
    String innerProblem;
    int outerStatus = 200;
    final List<byte[]> received = new ArrayList<>();
    int keyFetches;

    @NonNull
    @Override
    public MockResponse dispatch(@NonNull RecordedRequest request)
    {
      mRequests.add(request);
      if (request.getPath().equals("/ohttp-keys"))
      {
        ++keyFetches;
        return new MockResponse().setHeader("Content-Type", Ohttp.CONTENT_TYPE_KEYS)
            .setBody(new Buffer().write(served.keyConfigs()));
      }
      if (!request.getPath().equals("/relay"))
        return new MockResponse().setResponseCode(404);
      if (outerStatus != 200)
        return new MockResponse().setResponseCode(outerStatus);
      try
      {
        final TestGateway.Received r = gateway.open(request.getBody().readByteArray());
        if (r == null)
          return new MockResponse().setResponseCode(400).setHeader("Content-Type", "application/problem+json")
              .setBody(PROBLEM_OHTTP_KEY);
        received.add(r.request);
        final byte[] inner = TestGateway.bhttpResponse(innerStatus, innerProblem);
        return new MockResponse().setHeader("Content-Type", Ohttp.CONTENT_TYPE_RESPONSE)
            .setBody(new Buffer().write(TestGateway.seal(r, inner)));
      }
      catch (Exception e)
      {
        return new MockResponse().setResponseCode(400).setBody("cannot decapsulate");
      }
    }
  }

  private final MockWebServer mServer = new MockWebServer();
  private final FakeRelay mRelay = new FakeRelay();
  private final List<RecordedRequest> mRequests = new ArrayList<>();
  private final List<String> mLog = new ArrayList<>();
  private long mNow = 1_793_030_500_000L;
  private CrowdTrafficSender mSender;
  private final byte[] mRequest = hex(OhttpGoVectors.REQUEST);

  @Before
  public void setUp() throws IOException
  {
    mServer.setDispatcher(mRelay);
    mServer.start();
    final RelayEndpoints endpoints = RelayEndpoints.parse(mServer.url("/").toString());
    mSender = new CrowdTrafficSender(endpoints, () -> mNow, mLog::add);
  }

  @After
  public void tearDown() throws IOException
  {
    mServer.shutdown();
  }

  @Test
  public void acceptedWithoutIdentifyingHeaders()
  {
    assertEquals(CrowdTrafficSender.Result.ACCEPTED, mSender.send(mRequest));
    assertEquals(1, mRelay.received.size());
    assertArrayEquals(mRequest, mRelay.received.get(0));
    // Keys first (through the relay), then the encapsulated request.
    assertEquals(2, mRequests.size());
    assertEquals("GET", mRequests.get(0).getMethod());
    final RecordedRequest post = mRequests.get(1);
    assertEquals("POST", post.getMethod());
    assertEquals(Ohttp.CONTENT_TYPE_REQUEST, post.getHeader("Content-Type"));
    for (RecordedRequest r : mRequests)
    {
      assertEquals(CrowdTrafficSender.USER_AGENT, r.getHeader("User-Agent"));
      assertNull(r.getHeader("Cookie"));
      assertNull(r.getHeader("X-Forwarded-For"));
    }
    // All requests have the same size: header, enc, padded request, tag.
    assertEquals(7 + 32 + 4096 + 16, post.getBodySize());
  }

  @Test
  public void keysAreCachedForADay()
  {
    assertEquals(CrowdTrafficSender.Result.ACCEPTED, mSender.send(mRequest));
    assertEquals(CrowdTrafficSender.Result.ACCEPTED, mSender.send(mRequest));
    assertEquals(1, mRelay.keyFetches);
    mNow += CrowdTrafficSender.KEY_CACHE_MS + 1;
    assertEquals(CrowdTrafficSender.Result.ACCEPTED, mSender.send(mRequest));
    assertEquals(2, mRelay.keyFetches);
    mSender.clearKeys();
    assertEquals(CrowdTrafficSender.Result.ACCEPTED, mSender.send(mRequest));
    assertEquals(3, mRelay.keyFetches);
  }

  @Test
  public void innerRejectionIsNotRetried()
  {
    mRelay.innerStatus = 400;
    mRelay.innerProblem = "{\"title\":\"window_late\",\"type\":\"urn:better-maps:crowd-traffic:invalid-bundle\"}";
    assertEquals(CrowdTrafficSender.Result.REJECTED, mSender.send(mRequest));
    assertTrue(String.join("\n", mLog).contains("window_late"));
    mRelay.innerStatus = 503;
    mRelay.innerProblem = "{\"title\":\"overloaded\",\"type\":\"about:blank\"}";
    assertEquals(CrowdTrafficSender.Result.RETRY, mSender.send(mRequest));
  }

  @Test
  public void staleKeyIsFetchedAgainOnce()
  {
    assertEquals(CrowdTrafficSender.Result.ACCEPTED, mSender.send(mRequest));
    // Key rotation: the gateway does not know the cached key any more.
    mRelay.gateway = new TestGateway(2, (byte) 2);
    mRelay.served = mRelay.gateway;
    assertEquals(CrowdTrafficSender.Result.ACCEPTED, mSender.send(mRequest));
    assertEquals(2, mRelay.keyFetches);
    assertEquals(2, mRelay.received.size());

    // The relay keeps serving a key the gateway does not know: give up after one retry.
    mRelay.served = new TestGateway(3, (byte) 3);
    mSender.clearKeys();
    assertEquals(CrowdTrafficSender.Result.FAILED, mSender.send(mRequest));
    assertEquals(4, mRelay.keyFetches);
  }

  @Test
  public void temporaryErrorsAreRetried() throws IOException
  {
    mRelay.outerStatus = 429;
    assertEquals(CrowdTrafficSender.Result.RETRY, mSender.send(mRequest));
    mRelay.outerStatus = 503;
    assertEquals(CrowdTrafficSender.Result.RETRY, mSender.send(mRequest));
    mRelay.outerStatus = 500;
    assertEquals(CrowdTrafficSender.Result.FAILED, mSender.send(mRequest));
    mServer.shutdown();
    assertEquals(CrowdTrafficSender.Result.RETRY, mSender.send(mRequest));
  }

  @Test
  public void networkErrorWhileFetchingKeys() throws IOException
  {
    mServer.shutdown();
    assertEquals(CrowdTrafficSender.Result.RETRY, mSender.send(mRequest));
  }

  @Test
  public void badKeyAnswers() throws IOException
  {
    final MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse().setHeader("Content-Type", "text/plain").setBody("hello"));
    server.enqueue(new MockResponse().setHeader("Content-Type", Ohttp.CONTENT_TYPE_KEYS).setBody("x"));
    server.enqueue(new MockResponse().setResponseCode(503));
    server.start();
    final CrowdTrafficSender sender =
        new CrowdTrafficSender(RelayEndpoints.parse(server.url("/").toString()), () -> mNow, mLog::add);
    assertEquals(CrowdTrafficSender.Result.FAILED, sender.send(mRequest));
    assertEquals(CrowdTrafficSender.Result.FAILED, sender.send(mRequest));
    assertEquals(CrowdTrafficSender.Result.RETRY, sender.send(mRequest));
    server.shutdown();
  }

  @Test
  public void undecryptableResponse() throws IOException
  {
    final MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse().setHeader("Content-Type", Ohttp.CONTENT_TYPE_KEYS)
                       .setBody(new Buffer().write(mRelay.gateway.keyConfigs())));
    server.enqueue(new MockResponse().setHeader("Content-Type", Ohttp.CONTENT_TYPE_RESPONSE)
                       .setBody(new Buffer().write(new byte[64])));
    server.start();
    final CrowdTrafficSender sender =
        new CrowdTrafficSender(RelayEndpoints.parse(server.url("/").toString()), () -> mNow, mLog::add);
    assertEquals(CrowdTrafficSender.Result.FAILED, sender.send(mRequest));
    server.shutdown();
  }
}
