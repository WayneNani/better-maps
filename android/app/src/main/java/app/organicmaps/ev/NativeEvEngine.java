package app.organicmaps.ev;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.ev.EvPlanner;

/** The planner of the core, see {@link EvPlanner}. */
public class NativeEvEngine implements EvPlanPresenter.Engine
{
  @Override
  public void plan(@NonNull String profileJson, @NonNull String optionsJson, @NonNull Callback callback)
  {
    EvPlanner.plan(profileJson, optionsJson, callback::onResult);
  }

  @Override
  public void cancel()
  {
    EvPlanner.cancel();
  }

  @Nullable
  @Override
  public String evaluate(@NonNull String profileJson, @NonNull String optionsJson, @NonNull String stopsJson)
  {
    return EvPlanner.evaluate(profileJson, optionsJson, stopsJson, false /* fromCurrentPosition */);
  }
}
