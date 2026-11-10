package app.organicmaps.ev;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import app.organicmaps.R;
import app.organicmaps.sdk.ev.EvPlanner;
import app.organicmaps.util.ThemeUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "My vehicle" in the settings (docs/mockups/ladeplanung.png, screen 1): the charging planner on or off, the vehicle
 * template and its values, sockets, the charging curve and the battery level from Android Auto.
 */
@Keep
public class EvVehicleFragment extends Fragment
{
  /** The owner's car: the template that is chosen when there is no profile yet. */
  static final String DEFAULT_TEMPLATE = "skoda_elroq_85";
  /** Opened from the charging planner button of a car route (see {@link EvVehicleActivity#fromRoute}). */
  static final String ARG_FROM_ROUTE = "ev_from_route";

  private EvSettings mSettings;
  private MaterialSwitch mEnabled;
  private EvVehicleProfile mProfile;
  private List<EvVehicleProfile> mTemplates = new ArrayList<>();
  private LinearLayout mContent;
  private LinearLayout mValues;
  private EvChargeCurveView mCurve;
  private TextView mSource;
  private TextView mInvalid;
  private ChipGroup mSockets;
  // Values are set by the code, ignore the listeners meanwhile.
  private boolean mUpdating;

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState)
  {
    final NestedScrollView scroll = new NestedScrollView(requireContext());
    mContent = new LinearLayout(requireContext());
    mContent.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(mContent);
    return scroll;
  }

  /** The templates of the core; tests use others. */
  @NonNull
  protected List<EvVehicleProfile> loadTemplates()
  {
    return EvVehicleProfile.listFromJson(EvPlanner.getVehicleTemplates());
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
  {
    super.onViewCreated(view, savedInstanceState);
    mSettings = EvSettings.from(requireContext());
    mTemplates = loadTemplates();
    mProfile = mSettings.getProfile();
    if (mProfile == null)
    {
      mProfile = findTemplate(DEFAULT_TEMPLATE);
      if (mProfile == null)
        mProfile = mTemplates.isEmpty() ? new EvVehicleProfile() : mTemplates.get(0).copy();
      mSettings.setProfile(mProfile);
    }
    build();
  }

  @Nullable
  private EvVehicleProfile findTemplate(@NonNull String id)
  {
    for (EvVehicleProfile t : mTemplates)
      if (t.id.equals(id))
        return t.copy();
    return null;
  }

  private void build()
  {
    final Context context = requireContext();
    final boolean fromRoute = isFromRoute();

    final LinearLayout enable = section();
    if (fromRoute)
    {
      final TextView hint = text(getString(R.string.ev_setup_hint));
      hint.setId(R.id.ev_vehicle_hint);
      hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
      enable.addView(hint);
    }
    mEnabled = new MaterialSwitch(context);
    mEnabled.setId(R.id.ev_vehicle_enabled);
    mEnabled.setText(R.string.ev_enable);
    mEnabled.setChecked(mSettings.isEnabled());
    mEnabled.setOnCheckedChangeListener((b, checked) -> mSettings.setEnabled(checked));
    enable.addView(mEnabled);

    final LinearLayout template = section();
    template.addView(label(R.string.ev_template));
    final Spinner spinner = new Spinner(context);
    spinner.setId(R.id.ev_vehicle_template);
    final List<String> names = new ArrayList<>();
    for (EvVehicleProfile t : mTemplates)
      names.add(t.name);
    names.add(getString(R.string.ev_template_custom));
    final ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, names);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    spinner.setAdapter(adapter);
    spinner.setSelection(templateIndex());
    spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(AdapterView<?> parent, View v, int position, long id)
      {
        if (mUpdating || position == templateIndex())
          return;
        if (position < mTemplates.size())
          mProfile = mTemplates.get(position).copy();
        else
        {
          mProfile = mProfile.copy();
          mProfile.id = EvVehicleProfile.CUSTOM_ID;
          mProfile.source = "";
        }
        onProfileChanged(true);
        // Choosing the car is the setup: the planner is on with it.
        mEnabled.setChecked(true);
      }

      @Override
      public void onNothingSelected(AdapterView<?> parent)
      {}
    });
    template.addView(spinner);
    if (fromRoute)
    {
      // One tap for the preselected car: on, and back to the route, where the planner opens.
      final MaterialButton done = new MaterialButton(context);
      done.setId(R.id.ev_vehicle_plan_button);
      done.setText(R.string.ev_setup_done);
      done.setOnClickListener(v -> {
        mEnabled.setChecked(true);
        // Values that cannot be planned with stay here, with the hint below them.
        if (mProfile.isValid())
          requireActivity().finish();
      });
      template.addView(done, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                           ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    mValues = section();

    final LinearLayout sockets = section();
    sockets.addView(label(R.string.ev_sockets));
    mSockets = new ChipGroup(context);
    addSocket(R.string.ev_socket_ccs, EvVehicleProfile.SOCKET_CCS);
    addSocket(R.string.ev_socket_type2, EvVehicleProfile.SOCKET_TYPE2);
    addSocket(R.string.ev_socket_chademo, EvVehicleProfile.SOCKET_CHADEMO);
    sockets.addView(mSockets);

    final LinearLayout curve = section();
    curve.addView(label(R.string.ev_charge_curve));
    mCurve = new EvChargeCurveView(context, null);
    curve.addView(mCurve);
    curve.addView(muted(getString(R.string.ev_elevation_hint)));

    final LinearLayout car = section();
    final MaterialSwitch carSoc = new MaterialSwitch(context);
    carSoc.setText(R.string.ev_car_soc);
    carSoc.setChecked(mSettings.isCarSocEnabled());
    carSoc.setOnCheckedChangeListener((b, checked) -> mSettings.setCarSocEnabled(checked));
    car.addView(carSoc);
    car.addView(muted(getString(R.string.ev_car_soc_hint)));

    final LinearLayout source = section();
    mInvalid = muted(getString(R.string.ev_profile_invalid));
    mInvalid.setTextColor(ContextCompat.getColor(context, R.color.ev_charge));
    source.addView(mInvalid);
    mSource = muted("");
    source.addView(mSource);

    onProfileChanged(false);
  }

  private boolean isFromRoute()
  {
    final Bundle args = getArguments();
    return args != null && args.getBoolean(ARG_FROM_ROUTE, false);
  }

  private int templateIndex()
  {
    for (int i = 0; i < mTemplates.size(); ++i)
      if (mTemplates.get(i).id.equals(mProfile.id))
        return i;
    return mTemplates.size();
  }

  private void addSocket(@StringRes int name, @NonNull String type)
  {
    final Chip chip = new Chip(requireContext(), null, com.google.android.material.R.attr.chipStyle);
    chip.setText(name);
    chip.setCheckable(true);
    chip.setCheckedIconVisible(false);
    chip.setTag(type);
    chip.setOnCheckedChangeListener((b, checked) -> {
      if (mUpdating)
        return;
      makeCustom();
      mProfile.setSocket(type, checked);
      onProfileChanged(false);
    });
    mSockets.addView(chip);
  }

  /** A changed template is the user's own vehicle. */
  private void makeCustom()
  {
    if (!mProfile.isCustom())
    {
      mProfile.id = EvVehicleProfile.CUSTOM_ID;
      mProfile.source = "";
    }
  }

  private void onProfileChanged(boolean rebuildValues)
  {
    mUpdating = true;
    if (rebuildValues || mValues.getChildCount() == 0)
      buildValues();
    for (int i = 0; i < mSockets.getChildCount(); ++i)
    {
      final Chip chip = (Chip) mSockets.getChildAt(i);
      chip.setChecked(mProfile.hasSocket((String) chip.getTag()));
    }
    mCurve.setProfile(mProfile);
    mSource.setText(mProfile.source.isEmpty() ? "" : getString(R.string.ev_source, mProfile.source));
    mSource.setVisibility(mProfile.source.isEmpty() ? View.GONE : View.VISIBLE);
    final boolean valid = mProfile.isValid();
    mInvalid.setVisibility(valid ? View.GONE : View.VISIBLE);
    if (valid)
      mSettings.setProfile(mProfile);
    mUpdating = false;
  }

  private interface Setter
  {
    void set(double value);
  }

  private void buildValues()
  {
    mValues.removeAllViews();
    addValue(R.string.ev_usable_capacity, "kWh", mProfile.usableCapacityKwh, v -> mProfile.usableCapacityKwh = v);
    addValue(R.string.ev_consumption_motorway, "kWh/100 km", mProfile.consumptionMotorway,
             v -> mProfile.consumptionMotorway = v);
    addValue(R.string.ev_consumption_country, "kWh/100 km", mProfile.consumptionCountry,
             v -> mProfile.consumptionCountry = v);
    addValue(R.string.ev_max_dc, "kW", mProfile.maxDcPowerKw, v -> mProfile.maxDcPowerKw = v);
    addValue(R.string.ev_max_ac, "kW", mProfile.maxAcPowerKw, v -> mProfile.maxAcPowerKw = v);
    addValue(R.string.ev_mass, "kg", mProfile.massKg, v -> mProfile.massKg = v);
    addValue(R.string.ev_consumption_factor, "%", Math.round((mProfile.consumptionFactor - 1) * 100),
             v -> mProfile.consumptionFactor = 1 + v / 100.0);
  }

  private void addValue(@StringRes int label, @NonNull String unit, double value, @NonNull Setter setter)
  {
    final Context context = requireContext();
    final LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.addView(text(getString(label)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    final EditText edit = new EditText(context);
    edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    edit.setGravity(Gravity.END);
    edit.setMinEms(3);
    edit.setText(format(value));
    edit.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after)
      {}

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count)
      {}

      @Override
      public void afterTextChanged(Editable s)
      {
        if (mUpdating)
          return;
        try
        {
          setter.set(Double.parseDouble(s.toString().replace(',', '.')));
        }
        catch (NumberFormatException e)
        {
          setter.set(0);
        }
        makeCustom();
        onProfileChanged(false);
      }
    });
    row.addView(edit);
    row.addView(muted(" ≈ " + unit));
    mValues.addView(row);
  }

  @NonNull
  private static String format(double value)
  {
    return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
  }

  @NonNull
  private LinearLayout section()
  {
    final Context context = requireContext();
    final LinearLayout section = new LinearLayout(context);
    section.setOrientation(LinearLayout.VERTICAL);
    final int pad = Math.round(16 * getResources().getDisplayMetrics().density);
    section.setPadding(pad, pad / 2, pad, pad / 2);
    mContent.addView(section);
    final View divider = new View(context);
    divider.setBackgroundColor(ContextCompat.getColor(context, R.color.divider));
    mContent.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
    return section;
  }

  @NonNull
  private TextView text(@NonNull String s)
  {
    final TextView view = new TextView(requireContext());
    view.setText(s);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
    view.setTextColor(ThemeUtils.getColor(requireContext(), android.R.attr.textColorPrimary));
    return view;
  }

  @NonNull
  private TextView muted(@NonNull String s)
  {
    final TextView view = text(s);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
    view.setTextColor(ThemeUtils.getColor(requireContext(), android.R.attr.textColorSecondary));
    return view;
  }

  @NonNull
  private TextView label(@StringRes int text)
  {
    return muted(getString(text));
  }
}
