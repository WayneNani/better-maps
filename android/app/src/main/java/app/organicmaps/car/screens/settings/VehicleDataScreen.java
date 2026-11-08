package app.organicmaps.car.screens.settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.hardware.CarHardwareManager;
import androidx.car.app.hardware.common.CarValue;
import androidx.car.app.hardware.common.OnCarDataAvailableListener;
import androidx.car.app.hardware.info.CarInfo;
import androidx.car.app.hardware.info.EnergyLevel;
import androidx.car.app.hardware.info.EnergyProfile;
import androidx.car.app.hardware.info.Mileage;
import androidx.car.app.hardware.info.Model;
import androidx.car.app.hardware.info.Speed;
import androidx.car.app.hardware.info.TollCard;
import androidx.car.app.model.Action;
import androidx.car.app.model.Header;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.MapWithContentTemplate;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import app.organicmaps.R;
import app.organicmaps.car.renderer.Renderer;
import app.organicmaps.car.screens.base.BaseMapScreen;
import app.organicmaps.car.util.UiHelpers;
import app.organicmaps.sdk.util.log.Logger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * better-maps: shows (and logs with tag {@link #TAG}) everything the car reports through the Car Hardware API, to find
 * out which values a connected car provides (model, energy profile, battery/fuel level, range, speed, odometer).
 */
public class VehicleDataScreen extends BaseMapScreen implements DefaultLifecycleObserver
{
  private static final String TAG = "BMCarData";
  // Android Auto (projected) permissions of the Car Hardware API.
  private static final List<String> PERMISSIONS =
      Arrays.asList("com.google.android.gms.permission.CAR_FUEL", "com.google.android.gms.permission.CAR_SPEED",
                    "com.google.android.gms.permission.CAR_MILEAGE");

  @Nullable
  private CarInfo mCarInfo;
  @NonNull
  private String mModel = "…";
  @NonNull
  private String mProfile = "…";
  @NonNull
  private String mEnergy = "…";
  @NonNull
  private String mRange = "…";
  @NonNull
  private String mSpeed = "…";
  @NonNull
  private String mMileage = "…";
  @NonNull
  private String mToll = "…";
  @NonNull
  private String mPermissions = "…";

  private final OnCarDataAvailableListener<EnergyLevel> mEnergyListener = this::onEnergyLevel;
  private final OnCarDataAvailableListener<Speed> mSpeedListener = this::onSpeed;
  private final OnCarDataAvailableListener<Mileage> mMileageListener = this::onMileage;
  private final OnCarDataAvailableListener<TollCard> mTollListener = this::onToll;

  public VehicleDataScreen(@NonNull CarContext carContext, @NonNull Renderer surfaceRenderer)
  {
    super(carContext, surfaceRenderer);
    getLifecycle().addObserver(this);
  }

  @Override
  public void onCreate(@NonNull LifecycleOwner owner)
  {
    Logger.i(TAG, "Car App API level " + getCarContext().getCarAppApiLevel());
    try
    {
      mCarInfo = getCarContext().getCarService(CarHardwareManager.class).getCarInfo();
    }
    catch (RuntimeException e)
    {
      Logger.e(TAG, "Car Hardware API not available", e);
      mModel = mProfile = mEnergy = mRange = mSpeed = mMileage = mToll = "API nicht verfügbar: " + e.getMessage();
      invalidate();
      return;
    }
    // Model and toll need no permission; ask for the rest and start listening once answered.
    fetchModel();
    addTollListener();
    try
    {
      getCarContext().requestPermissions(PERMISSIONS, (granted, rejected) -> {
        mPermissions = "erlaubt: " + shortNames(granted) + (rejected.isEmpty() ? "" : " · abgelehnt: " + shortNames(rejected));
        Logger.i(TAG, "Permissions granted " + granted + ", rejected " + rejected);
        startPermissionListeners(granted);
        invalidate();
      });
    }
    catch (RuntimeException e)
    {
      Logger.e(TAG, "Permission request failed", e);
      mPermissions = "Anfrage fehlgeschlagen: " + e.getMessage();
      startPermissionListeners(PERMISSIONS);
    }
  }

  @Override
  public void onDestroy(@NonNull LifecycleOwner owner)
  {
    if (mCarInfo == null)
      return;
    mCarInfo.removeEnergyLevelListener(mEnergyListener);
    mCarInfo.removeSpeedListener(mSpeedListener);
    mCarInfo.removeMileageListener(mMileageListener);
    mCarInfo.removeTollListener(mTollListener);
  }

  @NonNull
  @Override
  protected Template onGetTemplateImpl()
  {
    final ItemList.Builder list = new ItemList.Builder();
    list.addItem(row("Fahrzeug", mModel, "Car-API-Level " + getCarContext().getCarAppApiLevel()));
    list.addItem(row("Antrieb und Stecker", mProfile, null));
    list.addItem(row("Akku / Tank", mEnergy, mRange));
    list.addItem(row("Geschwindigkeit", mSpeed, null));
    list.addItem(row("Kilometerstand / Maut", mMileage, mToll));
    list.addItem(row("Berechtigungen", mPermissions, null));
    final Header header = new Header.Builder()
                              .setStartHeaderAction(Action.BACK)
                              .setTitle(getCarContext().getString(R.string.car_vehicle_data))
                              .build();
    final ListTemplate content = new ListTemplate.Builder().setHeader(header).setSingleList(list.build()).build();
    return new MapWithContentTemplate.Builder()
        .setMapController(UiHelpers.createMapController(getCarContext(), getSurfaceRenderer()))
        .setContentTemplate(content)
        .build();
  }

  @NonNull
  private static Row row(@NonNull String title, @NonNull String line1, @Nullable String line2)
  {
    final Row.Builder builder = new Row.Builder().setTitle(title).addText(line1);
    if (line2 != null)
      builder.addText(line2);
    return builder.build();
  }

  private Executor executor()
  {
    return ContextCompat.getMainExecutor(getCarContext());
  }

  private void fetchModel()
  {
    try
    {
      mCarInfo.fetchModel(executor(), model -> {
        mModel = value(model.getManufacturer()) + " " + value(model.getName()) + " (" + value(model.getYear()) + ")";
        Logger.i(TAG, "Model " + model);
        invalidate();
      });
    }
    catch (RuntimeException e)
    {
      mModel = error(e);
    }
  }

  private void addTollListener()
  {
    try
    {
      mCarInfo.addTollListener(executor(), mTollListener);
    }
    catch (RuntimeException e)
    {
      mToll = "Maut: " + error(e);
    }
  }

  private void startPermissionListeners(@NonNull List<String> granted)
  {
    if (mCarInfo == null)
      return;
    if (granted.contains(PERMISSIONS.get(0)))
    {
      try
      {
        mCarInfo.fetchEnergyProfile(executor(), this::onEnergyProfile);
        mCarInfo.addEnergyLevelListener(executor(), mEnergyListener);
      }
      catch (RuntimeException e)
      {
        mProfile = mEnergy = error(e);
      }
    }
    else
      mProfile = mEnergy = mRange = "keine Berechtigung (CAR_FUEL)";
    if (granted.contains(PERMISSIONS.get(1)))
    {
      try
      {
        mCarInfo.addSpeedListener(executor(), mSpeedListener);
      }
      catch (RuntimeException e)
      {
        mSpeed = error(e);
      }
    }
    else
      mSpeed = "keine Berechtigung (CAR_SPEED)";
    if (granted.contains(PERMISSIONS.get(2)))
    {
      try
      {
        mCarInfo.addMileageListener(executor(), mMileageListener);
      }
      catch (RuntimeException e)
      {
        mMileage = error(e);
      }
    }
    else
      mMileage = "keine Berechtigung (CAR_MILEAGE)";
  }

  private void onEnergyProfile(@NonNull EnergyProfile profile)
  {
    mProfile = "Antrieb " + fuelTypes(profile.getFuelTypes()) + " · Stecker " + connectors(profile.getEvConnectorTypes());
    Logger.i(TAG, "EnergyProfile " + profile);
    invalidate();
  }

  private void onEnergyLevel(@NonNull EnergyLevel level)
  {
    mEnergy = "Akku " + percent(level.getBatteryPercent()) + " · Tank " + percent(level.getFuelPercent())
            + " · niedrig " + value(level.getEnergyIsLow());
    final CarValue<Float> range = level.getRangeRemainingMeters();
    mRange = "Reichweite "
           + (isOk(range) ? String.format(Locale.ROOT, "%.0f km", range.getValue() / 1000f) : status(range));
    Logger.i(TAG, "EnergyLevel " + level);
    invalidate();
  }

  private void onSpeed(@NonNull Speed speed)
  {
    mSpeed = "roh " + kmh(speed.getRawSpeedMetersPerSecond()) + " · Anzeige " + kmh(speed.getDisplaySpeedMetersPerSecond());
    Logger.d(TAG, "Speed " + speed);
    invalidate();
  }

  private void onMileage(@NonNull Mileage mileage)
  {
    // car-app 1.7: getOdometerMeters() returns kilometers despite its name (renamed in 1.8).
    final CarValue<Float> km = mileage.getOdometerMeters();
    mMileage = "Kilometerstand " + (isOk(km) ? String.format(Locale.ROOT, "%.0f km", km.getValue()) : status(km));
    Logger.i(TAG, "Mileage " + mileage);
    invalidate();
  }

  private void onToll(@NonNull TollCard toll)
  {
    mToll = "Mautkarte " + value(toll.getCardState());
    Logger.i(TAG, "TollCard " + toll);
    invalidate();
  }

  private static boolean isOk(@NonNull CarValue<?> value)
  {
    return value.getStatus() == CarValue.STATUS_SUCCESS && value.getValue() != null;
  }

  @NonNull
  private static String value(@NonNull CarValue<?> value)
  {
    return isOk(value) ? String.valueOf(value.getValue()) : status(value);
  }

  @NonNull
  private static String percent(@NonNull CarValue<Float> value)
  {
    return isOk(value) ? String.format(Locale.ROOT, "%.0f %%", value.getValue()) : status(value);
  }

  @NonNull
  private static String kmh(@NonNull CarValue<Float> value)
  {
    return isOk(value) ? String.format(Locale.ROOT, "%.0f km/h", value.getValue() * 3.6f) : status(value);
  }

  @NonNull
  private static String status(@NonNull CarValue<?> value)
  {
    switch (value.getStatus())
    {
    case CarValue.STATUS_UNIMPLEMENTED: return "nicht unterstützt";
    case CarValue.STATUS_UNAVAILABLE: return "nicht verfügbar";
    case CarValue.STATUS_UNKNOWN: return "unbekannt";
    default: return "leer";
    }
  }

  @NonNull
  private static String error(@NonNull RuntimeException e)
  {
    Logger.e(TAG, "Car data request failed", e);
    return "Fehler: " + e.getClass().getSimpleName();
  }

  @NonNull
  private static String fuelTypes(@NonNull CarValue<List<Integer>> types)
  {
    if (!isOk(types))
      return status(types);
    final List<String> names = new ArrayList<>();
    for (int type : types.getValue())
    {
      switch (type)
      {
      case EnergyProfile.FUEL_TYPE_ELECTRIC: names.add("elektrisch"); break;
      case EnergyProfile.FUEL_TYPE_UNLEADED: names.add("Benzin"); break;
      case EnergyProfile.FUEL_TYPE_DIESEL_1:
      case EnergyProfile.FUEL_TYPE_DIESEL_2: names.add("Diesel"); break;
      case EnergyProfile.FUEL_TYPE_HYDROGEN: names.add("Wasserstoff"); break;
      default: names.add("Typ " + type);
      }
    }
    return names.isEmpty() ? "keine" : String.join(", ", names);
  }

  @NonNull
  private static String connectors(@NonNull CarValue<List<Integer>> types)
  {
    if (!isOk(types))
      return status(types);
    final List<String> names = new ArrayList<>();
    for (int type : types.getValue())
    {
      switch (type)
      {
      case EnergyProfile.EVCONNECTOR_TYPE_J1772: names.add("Typ 1"); break;
      case EnergyProfile.EVCONNECTOR_TYPE_MENNEKES: names.add("Typ 2"); break;
      case EnergyProfile.EVCONNECTOR_TYPE_CHADEMO: names.add("CHAdeMO"); break;
      case EnergyProfile.EVCONNECTOR_TYPE_COMBO_1: names.add("CCS1"); break;
      case EnergyProfile.EVCONNECTOR_TYPE_COMBO_2: names.add("CCS2"); break;
      case EnergyProfile.EVCONNECTOR_TYPE_TESLA_SUPERCHARGER: names.add("Tesla"); break;
      default: names.add("Typ " + type);
      }
    }
    return names.isEmpty() ? "keine" : String.join(", ", names);
  }

  @NonNull
  private static String shortNames(@NonNull List<String> permissions)
  {
    if (permissions.isEmpty())
      return "keine";
    final List<String> names = new ArrayList<>();
    for (String p : permissions)
      names.add(p.substring(p.lastIndexOf('.') + 1));
    return String.join(", ", names);
  }
}
