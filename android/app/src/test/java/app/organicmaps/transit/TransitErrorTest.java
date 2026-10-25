package app.organicmaps.transit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.organicmaps.sdk.transit.TransitServiceException;
import org.junit.Test;

public class TransitErrorTest
{
  private static TransitError map(TransitServiceException.Kind kind, int code)
  {
    return TransitError.from(new TransitServiceException(kind, code, null, "m", null));
  }

  @Test
  public void mapping()
  {
    assertEquals(TransitError.NETWORK, map(TransitServiceException.Kind.NETWORK, 0));
    assertEquals(TransitError.SERVER, map(TransitServiceException.Kind.SERVER_ERROR, 503));
    assertEquals(TransitError.SERVER, map(TransitServiceException.Kind.INVALID_RESPONSE, 200));
    assertEquals(TransitError.SERVER, map(TransitServiceException.Kind.CLIENT_ERROR, 429));
    assertEquals(TransitError.REQUEST, map(TransitServiceException.Kind.CLIENT_ERROR, 400));
    assertEquals(TransitError.NO_RESULTS, map(TransitServiceException.Kind.NO_RESULTS, 0));
    assertEquals(TransitError.DISABLED, map(TransitServiceException.Kind.DISABLED, 0));
    assertEquals(TransitError.UNKNOWN, TransitError.from(new IllegalStateException()));
  }

  @Test
  public void retryable()
  {
    assertTrue(TransitError.NETWORK.isRetryable());
    assertTrue(TransitError.SERVER.isRetryable());
    assertTrue(TransitError.UNKNOWN.isRetryable());
    assertFalse(TransitError.REQUEST.isRetryable());
    assertFalse(TransitError.NO_RESULTS.isRetryable());
    assertFalse(TransitError.DISABLED.isRetryable());
  }
}
