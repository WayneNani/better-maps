package app.organicmaps.sdk.transit.provider;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.transit.TransitDepartures;
import app.organicmaps.sdk.transit.TransitPlan;
import app.organicmaps.sdk.util.concurrency.AsyncResult;
import java.util.ArrayList;
import java.util.List;

/** Provider whose results are completed by the test. */
class FakeProvider implements TransitRoutingProvider, TransitDepartureProvider
{
  final String mId;
  boolean mEnabled = true;
  final List<TransitPlanRequest> mPlanRequests = new ArrayList<>();
  final List<AsyncResult<TransitPlan>> mPlanResults = new ArrayList<>();
  final List<AsyncResult<TransitDepartures>> mDepartureResults = new ArrayList<>();
  RuntimeException mThrow;

  FakeProvider(@NonNull String id)
  {
    mId = id;
  }

  @NonNull
  @Override
  public String getId()
  {
    return mId;
  }

  @NonNull
  @Override
  public String getName()
  {
    return "Fake " + mId;
  }

  @NonNull
  @Override
  public String getAttributionUrl()
  {
    return "https://example.org/" + mId;
  }

  @Override
  public boolean isEnabled()
  {
    return mEnabled;
  }

  @NonNull
  @Override
  public AsyncResult<TransitPlan> plan(@NonNull TransitPlanRequest request)
  {
    if (mThrow != null)
      throw mThrow;
    mPlanRequests.add(request);
    final AsyncResult<TransitPlan> result = new AsyncResult<>(Runnable::run);
    mPlanResults.add(result);
    return result;
  }

  @NonNull
  @Override
  public AsyncResult<TransitDepartures> departuresNear(double lat, double lon, int limit)
  {
    final AsyncResult<TransitDepartures> result = new AsyncResult<>(Runnable::run);
    mDepartureResults.add(result);
    return result;
  }

  AsyncResult<TransitPlan> lastPlan()
  {
    return mPlanResults.get(mPlanResults.size() - 1);
  }
}
