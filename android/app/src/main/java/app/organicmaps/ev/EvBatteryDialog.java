package app.organicmaps.ev;

import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.R;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.ev.EvPlanner;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import java.util.List;

/**
 * Quick correction of the battery level during the navigation: the charging stops ahead are evaluated from the current
 * position with it, and the user can plan them again.
 */
public final class EvBatteryDialog
{
  private EvBatteryDialog() {}

  public static void show(@NonNull Activity activity)
  {
    final EvRouteStops routeStops = EvRouteStops.INSTANCE;
    final EvPlanOptions planOptions = routeStops.getOptions();
    final String profileJson = routeStops.getProfileJson();
    if (planOptions == null || profileJson == null)
      return;

    final int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
    final LinearLayout layout = new LinearLayout(activity);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(padding, padding / 2, padding, 0);
    final TextView value = new TextView(activity);
    value.setTextAppearance(R.style.MwmTextAppearance_Title);
    final Slider slider = new Slider(activity);
    slider.setValueFrom(1);
    slider.setValueTo(100);
    slider.setStepSize(1);
    final Double carSoc = EvCarBattery.INSTANCE.getSocIfEnabled(EvSettings.from(activity));
    slider.setValue(carSoc != null ? Math.round(carSoc) : 50);
    final TextView result = new TextView(activity);
    layout.addView(value);
    layout.addView(slider);
    layout.addView(result);

    final Runnable evaluate = () ->
    {
      final int soc = Math.round(slider.getValue());
      value.setText(activity.getString(R.string.ev_unit_percent, String.valueOf(soc)));
      result.setText(describe(activity, evaluate(profileJson, planOptions, soc)));
    };
    slider.addOnChangeListener(
        (s, v, fromUser) -> value.setText(activity.getString(R.string.ev_unit_percent, String.valueOf(Math.round(v)))));
    slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
      @Override
      public void onStartTrackingTouch(@NonNull Slider s)
      {}

      @Override
      public void onStopTrackingTouch(@NonNull Slider s)
      {
        evaluate.run();
      }
    });
    evaluate.run();

    new MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.ev_nav_battery_title)
        .setView(layout)
        .setPositiveButton(R.string.ev_nav_replan, (d, w) -> replan(activity, Math.round(slider.getValue())))
        .setNegativeButton(R.string.close, null)
        .show();
  }

  /** The stops ahead with |soc| now; updates the charging times of the route. */
  @Nullable
  private static EvPlanResult.Plan evaluate(@NonNull String profileJson, @NonNull EvPlanOptions planOptions, int soc)
  {
    final EvRouteStops routeStops = EvRouteStops.INSTANCE;
    final List<EvPlanResult.Stop> ahead = routeStops.getStopsAhead(Framework.nativeGetRoutePoints());
    final EvPlanOptions options = planOptions.copy();
    options.startSoc = soc;
    final EvPlanResult.Plan plan =
        EvJson.parsePlan(EvPlanner.evaluate(profileJson, options.toJson(), EvJson.stopsToJson(ahead), true));
    if (plan != null && plan.stops.size() == ahead.size())
      for (int i = 0; i < ahead.size(); ++i)
        ahead.get(i).takeEvaluation(plan.stops.get(i));
    return plan;
  }

  @NonNull
  private static String describe(@NonNull Activity activity, @Nullable EvPlanResult.Plan plan)
  {
    if (plan == null)
      return "";
    final int arrival = EvFormatter.soc(plan.arrivalSocPct);
    final String text =
        plan.stops.isEmpty()
            ? activity.getString(R.string.ev_nav_battery_destination, arrival)
            : activity.getString(R.string.ev_nav_battery_ok, EvFormatter.soc(plan.stops.get(0).arrivalSocPct), arrival);
    return plan.meetsMinimums ? text : text + "\n" + activity.getString(R.string.ev_nav_battery_low);
  }

  /** Plans the charging stops ahead again with |soc| now, like the planner of the route planner but without its UI. */
  private static void replan(@NonNull Activity activity, int soc)
  {
    final EvSettings settings = EvSettings.from(activity);
    final EvVehicleProfile profile = EvVehicleProfile.fromJson(EvRouteStops.INSTANCE.getProfileJson());
    final EvPlanOptions options = EvRouteStops.INSTANCE.getOptions();
    if (profile == null || options == null)
      return;
    final EvPlanPresenter presenter = new EvPlanPresenter(
        settings, profile, new NativeEvEngine(),
        new AndroidEvRoute(activity.getString(R.string.ev_stop_subtitle), () -> {}), EvRouteStops.INSTANCE);
    final EvPlanOptions newOptions = options.copy();
    newOptions.startSoc = soc;
    newOptions.departureTime = 0;
    presenter.setOptions(newOptions);
    presenter.attach(new EvPlanPresenter.View() {
      @Override
      public void render()
      {
        final EvPlanPresenter.Screen screen = presenter.getScreen();
        if (screen == EvPlanPresenter.Screen.RESULT || screen == EvPlanPresenter.Screen.ERROR)
        {
          presenter.detach();
          final EvFormatter formatter = new EvFormatter(new AndroidEvTexts(activity), java.time.ZoneId.systemDefault());
          final EvPlanResult result = presenter.getResult();
          final String text = result != null
                                ? formatter.distanceAndStops(result.plan)
                                : formatter.error(presenter.getErrorResult() != null ? presenter.getErrorResult()
                                                                                     : new EvPlanResult());
          Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
        }
      }

      @Override
      public void close()
      {}
    });
    presenter.plan();
  }
}
