package app.organicmaps.car.util;

import android.content.pm.PackageManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.hardware.CarHardwareManager;
import androidx.car.app.hardware.common.CarValue;
import androidx.car.app.hardware.common.OnCarDataAvailableListener;
import androidx.car.app.hardware.info.CarInfo;
import androidx.car.app.hardware.info.EnergyLevel;
import androidx.core.content.ContextCompat;
import app.organicmaps.ev.EvCarBattery;
import app.organicmaps.ev.EvSettings;
import app.organicmaps.sdk.util.log.Logger;
import java.util.concurrent.Executor;

/**
 * better-maps: the battery level of the car (Car Hardware API, EnergyLevel) for the charging planner, if the user
 * allowed it and the car provides it. Needs the CAR_FUEL permission, which the vehicle data screen asks for; without it
 * nothing happens. Android Auto shows the Car Hardware API only to apps from Google Play, so this is untested in a car.
 */
public final class EvCarEnergy
{
  private static final String TAG = EvCarEnergy.class.getSimpleName();
  private static final String CAR_FUEL = "com.google.android.gms.permission.CAR_FUEL";

  @Nullable
  private CarInfo mCarInfo;
  private final OnCarDataAvailableListener<EnergyLevel> mListener = EvCarEnergy::onEnergyLevel;

  public void start(@NonNull CarContext context)
  {
    if (!EvSettings.from(context).isCarSocEnabled()
        || ContextCompat.checkSelfPermission(context, CAR_FUEL) != PackageManager.PERMISSION_GRANTED)
      return;
    try
    {
      mCarInfo = context.getCarService(CarHardwareManager.class).getCarInfo();
      final Executor executor = ContextCompat.getMainExecutor(context);
      mCarInfo.addEnergyLevelListener(executor, mListener);
    }
    catch (RuntimeException e)
    {
      // No Car Hardware API (e.g. not from Google Play) or no permission after all.
      Logger.w(TAG, "No energy level: " + e);
      mCarInfo = null;
    }
  }

  public void stop()
  {
    if (mCarInfo == null)
      return;
    try
    {
      mCarInfo.removeEnergyLevelListener(mListener);
    }
    catch (RuntimeException e)
    {
      Logger.w(TAG, "Can't remove the listener: " + e);
    }
    mCarInfo = null;
  }

  /** Also from the vehicle data screen. */
  public static void onEnergyLevel(@NonNull EnergyLevel level)
  {
    final CarValue<Float> battery = level.getBatteryPercent();
    if (battery.getStatus() == CarValue.STATUS_SUCCESS && battery.getValue() != null)
      EvCarBattery.INSTANCE.onBatteryPercent(battery.getValue());
  }
}
