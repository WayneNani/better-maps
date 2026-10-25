package app.organicmaps.transit;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.transit.TransitDepartures;
import app.organicmaps.sdk.transit.TransitPlan;
import app.organicmaps.sdk.transit.provider.TransitDepartureProvider;
import app.organicmaps.sdk.transit.provider.TransitPlanRequest;
import app.organicmaps.sdk.transit.provider.TransitRoutingProvider;
import app.organicmaps.sdk.util.concurrency.AsyncResult;
import java.util.ArrayList;
import java.util.List;

/** Provider whose results the test completes; results are delivered synchronously. */
class FakeTransitProvider implements TransitRoutingProvider, TransitDepartureProvider
{
  final List<TransitPlanRequest> mPlanRequests = new ArrayList<>();
  final List<AsyncResult<TransitPlan>> mPlanResults = new ArrayList<>();
  final List<double[]> mDepartureRequests = new ArrayList<>();
  final List<AsyncResult<TransitDepartures>> mDepartureResults = new ArrayList<>();

  @NonNull
  @Override
  public String getId()
  {
    return "fake";
  }

  @NonNull
  @Override
  public String getName()
  {
    return "Fake transit";
  }

  @NonNull
  @Override
  public String getAttributionUrl()
  {
    return "https://example.org/sources";
  }

  @NonNull
  @Override
  public AsyncResult<TransitPlan> plan(@NonNull TransitPlanRequest request)
  {
    mPlanRequests.add(request);
    final AsyncResult<TransitPlan> result = new AsyncResult<>(Runnable::run);
    mPlanResults.add(result);
    return result;
  }

  @NonNull
  @Override
  public AsyncResult<TransitDepartures> departuresNear(double lat, double lon, int limit)
  {
    mDepartureRequests.add(new double[] {lat, lon, limit});
    final AsyncResult<TransitDepartures> result = new AsyncResult<>(Runnable::run);
    mDepartureResults.add(result);
    return result;
  }

  @NonNull
  AsyncResult<TransitPlan> lastPlan()
  {
    return mPlanResults.get(mPlanResults.size() - 1);
  }

  @NonNull
  TransitPlanRequest lastRequest()
  {
    return mPlanRequests.get(mPlanRequests.size() - 1);
  }
}
