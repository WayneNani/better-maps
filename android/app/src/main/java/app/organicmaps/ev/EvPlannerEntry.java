package app.organicmaps.ev;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.Router;

/**
 * The way into the charging planner from the route planner: the button is shown on every car route, also before the
 * planner is set up. Without a vehicle the button opens the vehicle setup first.
 */
public final class EvPlannerEntry
{
  public enum Action
  {
    /** Not a car route: nothing to plan. */
    NONE,
    /** The charging planner for the route. */
    PLANNER,
    /** "My vehicle" first: the planner is off or has no valid vehicle. */
    SETUP
  }

  private EvPlannerEntry() {}

  /** The charging planner button in the route planner, for car routes only. */
  public static boolean isButtonShown(@Nullable Router router)
  {
    return router == Router.Vehicle;
  }

  /** What a tap on the charging planner button opens. */
  @NonNull
  public static Action onButton(@NonNull EvSettings settings, @Nullable Router router)
  {
    if (!isButtonShown(router))
      return Action.NONE;
    return settings.isAvailable(router) ? Action.PLANNER : Action.SETUP;
  }

  /**
   * Back from the vehicle setup that the button opened: the planner if it is set up now and the car route is still
   * in the route planner, else just the route.
   */
  public static boolean openPlannerAfterSetup(@NonNull EvSettings settings, @Nullable Router router,
                                              boolean routePlanning)
  {
    return routePlanning && settings.isAvailable(router);
  }
}
