package app.organicmaps.ev;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import app.organicmaps.R;
import app.organicmaps.sdk.ev.EvPlanner;
import app.organicmaps.transit.ui.TransitMapViewport;
import app.organicmaps.util.ThemeUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.slider.Slider;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Charging planner over the map in the route planner (docs/mockups/ladeplanung.png, screens 2-4): options, the plan
 * with its stops (already added to the route) and a stop in detail. The logic is in {@link EvPlanPresenter}.
 * <p>
 * The map screen hosts this fragment and implements {@link Host}.
 */
public class EvPlanFragment extends Fragment implements EvPlanPresenter.View
{
  public static final String TAG = "EvPlanFragment";

  public interface Host
  {
    /** The planner was closed: remove it and show the route planner again. */
    void onEvPlanClosed();

    /** Start the navigation like the start button of the route planner. */
    void onEvPlanStartNavigation();
  }

  /** Keeps the presenter over configuration changes. */
  public static class EvPlanViewModel extends ViewModel
  {
    @Nullable
    EvPlanPresenter mPresenter;
  }

  private EvPlanPresenter mPresenter;
  private EvFormatter mFormatter;
  private LinearLayout mContent;
  private TextView mTitle;
  private View mTop;
  private View mSheet;
  @Nullable
  private EvPlanPresenter.Screen mBuiltScreen;
  private boolean mAlternativesShown;
  private boolean mClosed;
  @Nullable
  private EvPlanResult.Plan mShownPlan;
  // Options screen: views that change without building the screen again (sliders keep their drag).
  private TextView mStartSocValue;
  private TextView mMinArrivalValue;
  private TextView mMinStopValue;
  private ChipGroup mPriorityGroup;
  private ChipGroup mPowerGroup;
  private ChipGroup mAmenityGroup;
  private LinearLayout mProvidersSection;

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState)
  {
    return inflater.inflate(R.layout.fragment_ev_plan, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
  {
    super.onViewCreated(view, savedInstanceState);
    mFormatter = new EvFormatter(new AndroidEvTexts(requireContext()), ZoneId.systemDefault());
    mContent = view.findViewById(R.id.ev_plan_content);
    mTitle = view.findViewById(R.id.ev_plan_title);
    mTop = view.findViewById(R.id.ev_plan_top);
    mSheet = view.findViewById(R.id.ev_plan_sheet);
    view.findViewById(R.id.ev_plan_back).setOnClickListener(v -> onBackPressed());

    ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
      final Insets bars =
          insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
      final ViewGroup.LayoutParams lp = mTop.getLayoutParams();
      lp.height = Math.max(1, bars.top);
      mTop.setLayoutParams(lp);
      mSheet.setPadding(bars.left, 0, bars.right, bars.bottom);
      return insets;
    });
    ViewCompat.requestApplyInsets(view);

    final EvPlanViewModel model = new ViewModelProvider(requireActivity()).get(EvPlanViewModel.class);
    EvPlanPresenter presenter = model.mPresenter;
    final boolean isNew = presenter == null;
    if (isNew && savedInstanceState != null)
    {
      // The app was restarted: the planning is gone.
      mClosed = true;
      view.post(this::notifyClosed);
      return;
    }
    if (presenter == null)
    {
      presenter = createPresenter(requireContext());
      if (presenter == null)
      {
        mClosed = true;
        view.post(this::notifyClosed);
        return;
      }
      model.mPresenter = presenter;
    }
    mPresenter = presenter;
    mPresenter.attach(this);
    if (isNew)
      mPresenter.start(EvCarBattery.INSTANCE.getSocIfEnabled(EvSettings.from(requireContext())));
    view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitMap());
  }

  /** The planner with the native core and the route; tests use other ones. Null without a vehicle profile. */
  @VisibleForTesting
  @Nullable
  protected EvPlanPresenter createPresenter(@NonNull Context context)
  {
    final EvSettings settings = EvSettings.from(context);
    final EvVehicleProfile profile = settings.getProfile();
    if (profile == null || !profile.isValid())
      return null;
    return new EvPlanPresenter(settings, profile, new NativeEvEngine(),
                               new AndroidEvRoute(context.getString(R.string.ev_stop_subtitle), () -> {
                                 if (getActivity() instanceof Host host)
                                   host.onEvPlanStartNavigation();
                               }), EvRouteStops.INSTANCE);
  }

  @VisibleForTesting
  public EvPlanPresenter getPresenter()
  {
    return mPresenter;
  }

  @Override
  public void onDestroyView()
  {
    if (mPresenter != null)
      mPresenter.detach();
    super.onDestroyView();
  }

  /** Back: one screen back, or closes the planner. */
  public void onBackPressed()
  {
    if (mPresenter == null || !mPresenter.back())
      closePlanner();
  }

  public void closePlanner()
  {
    if (mPresenter != null)
      mPresenter.close();
    else
      close();
  }

  @Override
  public void close()
  {
    if (mClosed)
      return;
    mClosed = true;
    if (getActivity() != null)
      new ViewModelProvider(requireActivity()).get(EvPlanViewModel.class).mPresenter = null;
    notifyClosed();
  }

  private void notifyClosed()
  {
    if (getActivity() == null)
      return;
    TransitMapViewport.reset(requireActivity());
    if (getActivity() instanceof Host host)
      host.onEvPlanClosed();
  }

  private void fitMap()
  {
    if (mClosed || !isAdded() || mTop == null || mSheet == null)
      return;
    if (!TransitMapViewport.fitBetween(requireActivity(), mTop, mSheet))
      return;
    // The whole route with its charging stops above the plan, once per plan.
    final EvPlanResult result = mPresenter == null ? null : mPresenter.getResult();
    if (mPresenter != null && mPresenter.getScreen() == EvPlanPresenter.Screen.RESULT && result != null
        && result.plan != mShownPlan)
    {
      mShownPlan = result.plan;
      EvPlanner.showRoute();
    }
  }

  @Override
  public void render()
  {
    if (mContent == null || mPresenter == null)
      return;
    final EvPlanPresenter.Screen screen = mPresenter.getScreen();
    if (screen == EvPlanPresenter.Screen.OPTIONS && mBuiltScreen == screen)
    {
      updateOptions();
      return;
    }
    mContent.removeAllViews();
    mBuiltScreen = screen;
    switch (screen)
    {
    case OPTIONS -> buildOptions();
    case PLANNING -> buildProgress();
    case RESULT -> buildResult();
    case DETAIL -> buildDetail();
    case ERROR -> buildError();
    }
  }

  // Screen 2: options.

  private void buildOptions()
  {
    mTitle.setText("⚡ " + getString(R.string.ev_planning));
    final EvPlanOptions o = mPresenter.getOptions();

    final LinearLayout battery = section();
    final EvVehicleProfile profile = mPresenter.getProfile();
    battery.addView(label(profile.name + " · " + Math.round(profile.usableCapacityKwh) + " kWh"));
    final boolean fromCar = EvCarBattery.INSTANCE.getSocIfEnabled(EvSettings.from(requireContext())) != null;
    mStartSocValue = row(battery, getString(fromCar ? R.string.ev_battery_now_from_car : R.string.ev_battery_now));
    battery.addView(slider(R.id.ev_start_soc, 1, 100, 1, o.startSoc, v -> mPresenter.setStartSoc(v)));
    mMinArrivalValue = row(battery, getString(R.string.ev_min_arrival));
    battery.addView(slider(R.id.ev_min_arrival_soc, 0, 50, 5, o.minArrivalSoc, v -> mPresenter.setMinArrivalSoc(v)));
    mMinStopValue = row(battery, getString(R.string.ev_min_stop));
    battery.addView(slider(R.id.ev_min_stop_soc, 0, 30, 5, o.minStopSoc, v -> mPresenter.setMinStopSoc(v)));

    final LinearLayout priority = section();
    priority.addView(label(getString(R.string.ev_priority)));
    mPriorityGroup = chipGroup(R.id.ev_priority_group);
    addChoice(mPriorityGroup, R.string.ev_priority_fastest, EvPlanOptions.PRIORITY_FASTEST);
    addChoice(mPriorityGroup, R.string.ev_priority_fewest_stops, EvPlanOptions.PRIORITY_FEWEST_STOPS);
    addChoice(mPriorityGroup, R.string.ev_priority_long_breaks, EvPlanOptions.PRIORITY_LONG_BREAKS);
    priority.addView(mPriorityGroup);

    mProvidersSection = section();

    final LinearLayout power = section();
    power.addView(label(getString(R.string.ev_min_power)));
    mPowerGroup = chipGroup(R.id.ev_min_power_group);
    for (int kw : EvSettings.MIN_POWER_CHOICES)
    {
      final Chip chip = chip(kw + " kW", false);
      chip.setTag(kw);
      chip.setOnClickListener(v -> mPresenter.setMinPowerKw(kw));
      mPowerGroup.addView(chip);
    }
    power.addView(mPowerGroup);

    final LinearLayout amenities = section();
    amenities.addView(label(getString(R.string.ev_prefer_at_stops)));
    mAmenityGroup = chipGroup(R.id.ev_amenities_group);
    addAmenity(R.string.ev_amenity_toilets, EvPlanResult.Amenity.TOILETS);
    addAmenity(R.string.ev_amenity_playground, EvPlanResult.Amenity.PLAYGROUND);
    addAmenity(R.string.ev_amenity_food, EvPlanResult.Amenity.FOOD);
    addAmenity(R.string.ev_amenity_shopping, EvPlanResult.Amenity.SHOPPING);
    amenities.addView(mAmenityGroup);

    final LinearLayout buttons = section();
    final MaterialButton plan = new MaterialButton(requireContext());
    plan.setId(R.id.ev_plan_button);
    plan.setText(R.string.ev_plan_button);
    plan.setOnClickListener(v -> mPresenter.plan());
    buttons.addView(
        plan, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    updateOptions();
  }

  private void addChoice(@NonNull ChipGroup group, @StringRes int text, @NonNull String priority)
  {
    final Chip chip = chip(getString(text), false);
    chip.setTag(priority);
    chip.setOnClickListener(v -> mPresenter.setPriority(priority));
    group.addView(chip);
  }

  private void addAmenity(@StringRes int text, @NonNull String type)
  {
    final Chip chip = chip(getString(text), false);
    chip.setTag(type);
    chip.setOnClickListener(
        v -> mPresenter.setAmenity(type, !mPresenter.getOptions().preferredAmenities.contains(type)));
    mAmenityGroup.addView(chip);
  }

  private void updateOptions()
  {
    final EvPlanOptions o = mPresenter.getOptions();
    mStartSocValue.setText(percent(o.startSoc));
    mMinArrivalValue.setText(percent(o.minArrivalSoc));
    mMinStopValue.setText(percent(o.minStopSoc));
    for (int i = 0; i < mPriorityGroup.getChildCount(); ++i)
    {
      final Chip chip = (Chip) mPriorityGroup.getChildAt(i);
      chip.setChecked(o.priority.equals(chip.getTag()));
    }
    for (int i = 0; i < mPowerGroup.getChildCount(); ++i)
    {
      final Chip chip = (Chip) mPowerGroup.getChildAt(i);
      chip.setChecked(Math.round(o.minPowerKw) == (int) chip.getTag());
    }
    for (int i = 0; i < mAmenityGroup.getChildCount(); ++i)
    {
      final Chip chip = (Chip) mAmenityGroup.getChildAt(i);
      chip.setChecked(o.preferredAmenities.contains((String) chip.getTag()));
    }
    buildProviders();
  }

  private void buildProviders()
  {
    mProvidersSection.removeAllViews();
    mProvidersSection.addView(label(getString(R.string.ev_preferred_providers)));
    final List<EvPlanResult.Provider> providers = mPresenter.getProviders();
    final ChipGroup preferred = chipGroup(R.id.ev_providers_group);
    final ChipGroup excluded = chipGroup(R.id.ev_excluded_group);
    for (EvPlanResult.Provider p : providers)
    {
      final boolean isExcluded = mPresenter.isExcluded(p.key);
      final Chip chip = chip(p.name, mPresenter.isPreferred(p.key));
      if (isExcluded)
        chip.setPaintFlags(chip.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
      chip.setOnClickListener(v -> mPresenter.toggleProvider(p.key));
      (isExcluded ? excluded : preferred).addView(chip);
    }
    if (preferred.getChildCount() > 0)
      mProvidersSection.addView(preferred);
    else
      mProvidersSection.addView(muted(
          getString(mPresenter.isProvidersLoading() ? R.string.ev_providers_loading : R.string.ev_providers_none)));
    if (excluded.getChildCount() > 0)
    {
      mProvidersSection.addView(label(getString(R.string.ev_excluded_providers)));
      mProvidersSection.addView(excluded);
    }
    if (!providers.isEmpty())
      mProvidersSection.addView(muted(getString(R.string.ev_providers_hint)));
  }

  // Planning.

  private void buildProgress()
  {
    mTitle.setText("⚡ " + getString(R.string.ev_planning));
    final LinearLayout section = section();
    section.setGravity(Gravity.CENTER_HORIZONTAL);
    section.addView(new ProgressBar(requireContext()));
    section.addView(muted(getString(R.string.ev_planning_progress)));
  }

  private void buildError()
  {
    mTitle.setText("⚡ " + getString(R.string.ev_planning));
    final LinearLayout section = section();
    final EvPlanResult result = mPresenter.getErrorResult();
    final TextView text = text(result == null ? getString(R.string.ev_error) : mFormatter.error(result));
    text.setId(R.id.ev_error_text);
    section.addView(text);
    final ChipGroup buttons = chipGroup(View.NO_ID);
    final Chip options = chip(getString(R.string.ev_options), false);
    options.setId(R.id.ev_options_button);
    options.setOnClickListener(v -> mPresenter.showOptions());
    buttons.addView(options);
    section.addView(buttons);
  }

  // Screen 3: the route with charging stops.

  private void buildResult()
  {
    final EvPlanResult result = mPresenter.getResult();
    if (result == null)
      return;
    final EvPlanResult.Plan plan = result.plan;
    mTitle.setText(getString(R.string.ev_planning));
    final long now = System.currentTimeMillis() / 1000;

    final LinearLayout summary = section();
    final LinearLayout head = horizontal();
    final TextView total = text(mFormatter.total(plan));
    total.setId(R.id.ev_result_total);
    total.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
    total.setTypeface(Typeface.DEFAULT_BOLD);
    head.addView(total, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    head.addView(muted(mFormatter.distanceAndStops(plan)));
    summary.addView(head);
    final TextView line = muted(mFormatter.summary(plan, now));
    line.setId(R.id.ev_result_summary);
    summary.addView(line);
    final EvBarView bar = new EvBarView(requireContext());
    bar.setId(R.id.ev_result_bar);
    bar.setPlan(plan);
    final LinearLayout.LayoutParams barParams =
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    barParams.topMargin = dp(8);
    summary.addView(bar, barParams);
    summary.addView(legend());
    if (!plan.meetsMinimums)
      summary.addView(warning(getString(R.string.ev_below_minimum)));

    for (int i = 0; i < plan.stops.size(); ++i)
      addStop(plan.stops.get(i), i);

    if (mAlternativesShown || plan.stops.isEmpty())
      for (int i = 0; i < result.alternatives.size(); ++i)
        addAlternative(result.alternatives.get(i), plan, i);
    else if (!result.alternatives.isEmpty())
      addAlternative(result.alternatives.get(0), plan, 0);

    final LinearLayout buttons = section();
    final ChipGroup group = chipGroup(View.NO_ID);
    if (result.alternatives.size() > 1)
    {
      final Chip alternatives = chip(getString(R.string.ev_alternatives), mAlternativesShown);
      alternatives.setId(R.id.ev_alternatives_button);
      alternatives.setOnClickListener(v -> {
        mAlternativesShown = !mAlternativesShown;
        mBuiltScreen = null;
        render();
      });
      group.addView(alternatives);
    }
    final Chip options = chip(getString(R.string.ev_options), false);
    options.setId(R.id.ev_options_button);
    options.setOnClickListener(v -> mPresenter.showOptions());
    group.addView(options);
    final Chip start = chip(getString(R.string.ev_start), true);
    start.setId(R.id.ev_start_button);
    start.setOnClickListener(v -> mPresenter.startNavigation());
    group.addView(start);
    buttons.addView(group);
  }

  private void addStop(@NonNull EvPlanResult.Stop stop, int index)
  {
    final LinearLayout item = section();
    item.setId(R.id.ev_stop_item);
    item.setTag(index);
    item.setBackgroundResource(ThemeUtils.getResource(requireContext(), android.R.attr.selectableItemBackground));
    item.setOnClickListener(v -> mPresenter.showStop(index));
    final LinearLayout head = horizontal();
    final TextView title = text(stop.title);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    head.addView(muted(mFormatter.getTexts().inDistance(mFormatter.getTexts().distance(stop.distanceAlongRouteM))));
    item.addView(head);
    item.addView(muted(mFormatter.stopDetails(stop)));
    final LinearLayout charge = horizontal();
    final TextView arrive = text(mFormatter.arriveAndCharge(stop));
    arrive.setTypeface(Typeface.DEFAULT_BOLD);
    charge.addView(arrive, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    charge.addView(text(mFormatter.stopDuration(stop)));
    item.addView(charge);
    final List<String> amenities = new ArrayList<>();
    for (EvPlanResult.Amenity a : EvFormatter.nearestByType(stop))
      amenities.add(mFormatter.amenity(a));
    if (!amenities.isEmpty())
      item.addView(muted(String.join("   ", amenities)));
  }

  private void addAlternative(@NonNull EvPlanResult.Plan alternative, @NonNull EvPlanResult.Plan main, int index)
  {
    final LinearLayout item = section();
    item.setId(R.id.ev_alternative_item);
    item.setTag(index);
    item.setBackgroundResource(ThemeUtils.getResource(requireContext(), android.R.attr.selectableItemBackground));
    item.setOnClickListener(v -> {
      mAlternativesShown = false;
      mPresenter.chooseAlternative(index);
    });
    item.addView(muted(mFormatter.alternativeTitle(alternative)));
    item.addView(muted(mFormatter.alternativeDescription(alternative, main)));
  }

  private View legend()
  {
    final TextView legend = muted("");
    final String drive = "■ " + getString(R.string.ev_legend_drive) + "   ";
    final String charge = "■ " + getString(R.string.ev_legend_charge);
    final android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(drive + charge);
    sb.setSpan(new android.text.style.ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.ev_drive)),
               0, 1, 0);
    sb.setSpan(new android.text.style.ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.ev_charge)),
               drive.length(), drive.length() + 1, 0);
    legend.setText(sb);
    return legend;
  }

  // Screen 4: a charging stop in detail.

  private void buildDetail()
  {
    final EvPlanResult.Stop stop = mPresenter.getSelectedStop();
    if (stop == null)
      return;
    mTitle.setText(stop.title);

    final LinearLayout station = section();
    station.addView(muted(mFormatter.stationHeader(stop)));
    for (String[] row : mFormatter.socketRows(stop))
      row(station, row[0]).setText(row[1]);
    station.addView(muted(mFormatter.getTexts().availability(stop.availability)));

    final LinearLayout nearby = section();
    nearby.addView(label(getString(R.string.ev_nearby)));
    if (stop.amenities.isEmpty())
      nearby.addView(muted(getString(R.string.ev_nearby_none)));
    for (EvPlanResult.Amenity a : stop.amenities)
    {
      final String name = mFormatter.amenityWithOpening(a) + (a.name.isEmpty() ? "" : " – " + a.name);
      final TextView distance = rowWithMutedValue(nearby, name);
      distance.setText(mFormatter.getTexts().distance(a.distanceM));
    }

    final LinearLayout charge = section();
    final TextView chargeValue = row(charge, mFormatter.charge(stop));
    chargeValue.setId(R.id.ev_detail_charge);
    chargeValue.setText(mFormatter.stopDuration(stop));
    final String longer = mFormatter.longer(stop);
    if (!longer.isEmpty())
    {
      final TextView longerText = muted(longer);
      longerText.setId(R.id.ev_detail_longer);
      longerText.setBackgroundResource(
          ThemeUtils.getResource(requireContext(), android.R.attr.selectableItemBackground));
      longerText.setOnClickListener(v -> mPresenter.chargeLonger());
      charge.addView(longerText);
    }

    final LinearLayout buttons = section();
    final ChipGroup group = chipGroup(View.NO_ID);
    final Chip other = chip(getString(R.string.ev_other_stop), false);
    other.setId(R.id.ev_other_stop_button);
    other.setOnClickListener(v -> mPresenter.chooseOtherStop());
    group.addView(other);
    final Chip keep = chip(getString(R.string.ev_keep_stop), true);
    keep.setId(R.id.ev_keep_stop_button);
    keep.setOnClickListener(v -> mPresenter.keepStop());
    group.addView(keep);
    buttons.addView(group);
  }

  // Views.

  @NonNull
  private LinearLayout section()
  {
    final LinearLayout section = new LinearLayout(requireContext());
    section.setOrientation(LinearLayout.VERTICAL);
    section.setPadding(dp(16), dp(10), dp(16), dp(10));
    mContent.addView(section, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                            ViewGroup.LayoutParams.WRAP_CONTENT));
    final View divider = new View(requireContext());
    divider.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.divider));
    mContent.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
    return section;
  }

  @NonNull
  private LinearLayout horizontal()
  {
    final LinearLayout row = new LinearLayout(requireContext());
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    return row;
  }

  /** A row with |left| and a bold value on the right, which is returned. */
  @NonNull
  private TextView row(@NonNull LinearLayout parent, @NonNull String left)
  {
    final LinearLayout row = horizontal();
    row.setPadding(0, dp(2), 0, dp(2));
    row.addView(text(left), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    final TextView value = text("");
    value.setTypeface(Typeface.DEFAULT_BOLD);
    row.addView(value);
    parent.addView(row);
    return value;
  }

  @NonNull
  private TextView rowWithMutedValue(@NonNull LinearLayout parent, @NonNull String left)
  {
    final LinearLayout row = horizontal();
    row.setPadding(0, dp(2), 0, dp(2));
    row.addView(text(left), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    final TextView value = muted("");
    row.addView(value);
    parent.addView(row);
    return value;
  }

  @NonNull
  private TextView text(@NonNull String s)
  {
    final TextView view = new TextView(requireContext());
    view.setText(s);
    view.setTextColor(ThemeUtils.getColor(requireContext(), android.R.attr.textColorPrimary));
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
    return view;
  }

  @NonNull
  private TextView muted(@NonNull String s)
  {
    final TextView view = text(s);
    view.setTextColor(ThemeUtils.getColor(requireContext(), android.R.attr.textColorSecondary));
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
    return view;
  }

  @NonNull
  private TextView label(@NonNull String s)
  {
    final TextView view = muted(s);
    view.setPadding(0, 0, 0, dp(4));
    return view;
  }

  @NonNull
  private TextView warning(@NonNull String s)
  {
    final TextView view = text(s);
    view.setTextColor(ContextCompat.getColor(requireContext(), R.color.ev_charge));
    return view;
  }

  @NonNull
  private ChipGroup chipGroup(@IdRes int id)
  {
    final ChipGroup group = new ChipGroup(requireContext());
    group.setId(id);
    group.setChipSpacingHorizontal(dp(6));
    group.setChipSpacingVertical(dp(0));
    return group;
  }

  @NonNull
  private Chip chip(@NonNull String text, boolean checked)
  {
    final Chip chip = new Chip(requireContext(), null, com.google.android.material.R.attr.chipStyle);
    chip.setText(text);
    chip.setCheckable(true);
    chip.setCheckedIconVisible(false);
    chip.setChecked(checked);
    // The presenter decides: a tap does not check the chip by itself.
    chip.setOnCheckedChangeListener(null);
    return chip;
  }

  private interface IntListener
  {
    void onValue(int value);
  }

  @NonNull
  private Slider slider(@IdRes int id, int from, int to, int step, double value, @NonNull IntListener listener)
  {
    final Slider slider = new Slider(requireContext());
    slider.setId(id);
    slider.setValueFrom(from);
    slider.setValueTo(to);
    slider.setStepSize(step);
    slider.setValue((float) Math.max(from, Math.min(to, Math.round(value / step) * step)));
    slider.setLabelBehavior(com.google.android.material.slider.LabelFormatter.LABEL_GONE);
    slider.addOnChangeListener((s, v, fromUser) -> {
      if (fromUser)
        listener.onValue(Math.round(v));
    });
    return slider;
  }

  @NonNull
  private String percent(double value)
  {
    return Math.round(value) + " %";
  }

  private int dp(int dp)
  {
    return Math.round(dp * getResources().getDisplayMetrics().density);
  }
}
