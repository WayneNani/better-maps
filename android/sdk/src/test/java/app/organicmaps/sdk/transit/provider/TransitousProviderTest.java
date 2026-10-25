package app.organicmaps.sdk.transit.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.transit.TestModels;
import app.organicmaps.sdk.transit.TransitDepartures;
import app.organicmaps.sdk.transit.TransitLocation;
import app.organicmaps.sdk.transit.TransitPlan;
import app.organicmaps.sdk.transit.TransitRoutingOptions;
import app.organicmaps.sdk.transit.TransitService;
import app.organicmaps.sdk.transit.TransitStop;
import app.organicmaps.sdk.util.concurrency.AsyncResult;
import java.time.Instant;
import java.util.List;
import org.junit.Test;

public class TransitousProviderTest
{
  private static final class RecordingService implements TransitService
  {
    String mCall;
    final TransitPlan mPlan = TestModels.plan("n");
    final TransitDepartures mDepartures = TestModels.departures("S");

    @NonNull
    @Override
    public AsyncResult<List<TransitStop>> reverseGeocodeStops(double lat, double lon)
    {
      throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public AsyncResult<TransitDepartures> stopTimes(@NonNull String stopId, int limit)
    {
      throw new UnsupportedOperationException();
    }

    @NonNull
    @Override
    public AsyncResult<TransitDepartures> closestStopWithTimes(double lat, double lon, int limit)
    {
      mCall = "closest " + lat + " " + lon + " " + limit;
      return AsyncResult.completed(Runnable::run, mDepartures);
    }

    @NonNull
    @Override
    public AsyncResult<TransitPlan> plan(@NonNull TransitLocation from, @NonNull TransitLocation to,
                                         @Nullable Instant time, boolean arriveBy, @Nullable String pageCursor,
                                         @NonNull TransitRoutingOptions options)
    {
      mCall =
          "plan " + from + " " + to + " " + time + " " + arriveBy + " " + pageCursor + " " + options.getMaxTransfers();
      return AsyncResult.completed(Runnable::run, mPlan);
    }
  }

  @Test
  public void delegatesToService()
  {
    final RecordingService service = new RecordingService();
    final TransitousProvider provider = new TransitousProvider(service);
    assertEquals(TransitousProvider.ID, provider.getId());
    assertEquals("Transitous", provider.getName());
    assertEquals("https://transitous.org/sources/", provider.getAttributionUrl());

    final TransitPlanRequest request = new TransitPlanRequest(
        TransitLocation.fromCoordinates(1, 2), TransitLocation.fromStopId("stop"), Instant.ofEpochSecond(60), true,
        "cur", TransitRoutingOptions.builder().setMaxTransfers(2).build());
    final TransitPlan[] plan = new TransitPlan[1];
    provider.plan(request).onSuccess(p -> plan[0] = p);
    assertSame(service.mPlan, plan[0]);
    assertEquals("plan 1.000000,2.000000 stop 1970-01-01T00:01:00Z true cur 2", service.mCall);

    final TransitDepartures[] departures = new TransitDepartures[1];
    provider.departuresNear(52.5, 13.4, 7).onSuccess(d -> departures[0] = d);
    assertSame(service.mDepartures, departures[0]);
    assertEquals("closest 52.5 13.4 7", service.mCall);
  }
}
