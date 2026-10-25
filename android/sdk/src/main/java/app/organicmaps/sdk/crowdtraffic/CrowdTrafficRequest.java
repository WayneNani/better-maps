package app.organicmaps.sdk.crowdtraffic;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

/**
 * A bundle prepared by the native reporting client: the padded Binary HTTP request (always 4096 bytes) and
 * the time after which it must not be sent any more (end of the server's grace period).
 */
// Called from JNI.
@Keep
public final class CrowdTrafficRequest
{
  @NonNull
  public final byte[] request;
  public final long deadlineMs;
  public final int reports;

  public CrowdTrafficRequest(@NonNull byte[] request, long deadlineMs, int reports)
  {
    this.request = request;
    this.deadlineMs = deadlineMs;
    this.reports = reports;
  }
}
