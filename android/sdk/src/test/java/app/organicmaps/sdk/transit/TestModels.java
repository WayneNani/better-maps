package app.organicmaps.sdk.transit;

import androidx.annotation.NonNull;

/** Creates model objects for tests in other packages (their constructors are package-private). */
public final class TestModels
{
  private TestModels() {}

  @NonNull
  public static TransitPlan plan(@NonNull String nextPageCursor)
  {
    final TransitPlan plan = new TransitPlan();
    plan.mNextPageCursor = nextPageCursor;
    return plan;
  }

  @NonNull
  public static TransitDepartures departures(@NonNull String stopName)
  {
    final TransitDepartures departures = new TransitDepartures();
    departures.mStop.mName = stopName;
    return departures;
  }
}
