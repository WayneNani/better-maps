package app.organicmaps.ev;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.concurrent.TimeUnit;

/**
 * State of charge from the car (Android Auto, CarInfo EnergyLevel), if it provides it. Used as "Battery now" of the
 * charging planner when the user allowed it in the vehicle settings.
 */
public final class EvCarBattery
{
  public static final EvCarBattery INSTANCE = new EvCarBattery();

  /** Older values are not used: the car was probably driven or charged since. */
  static final long MAX_AGE_MS = TimeUnit.MINUTES.toMillis(10);

  @Nullable
  private Double mSocPct;
  private long mTimeMs;

  public interface Clock
  {
    long nowMs();
  }

  @NonNull
  private Clock mClock = System::currentTimeMillis;

  void setClock(@NonNull Clock clock)
  {
    mClock = clock;
  }

  /** From the car's EnergyLevel listener. */
  public synchronized void onBatteryPercent(float percent)
  {
    if (percent < 0 || percent > 100)
      return;
    mSocPct = (double) percent;
    mTimeMs = mClock.nowMs();
  }

  public synchronized void clear()
  {
    mSocPct = null;
  }

  /** The latest state of charge of the car, null if there is none or it is old. */
  @Nullable
  public synchronized Double getSoc()
  {
    if (mSocPct == null || mClock.nowMs() - mTimeMs > MAX_AGE_MS)
      return null;
    return mSocPct;
  }

  @Nullable
  public Double getSocIfEnabled(@NonNull EvSettings settings)
  {
    return settings.isCarSocEnabled() ? getSoc() : null;
  }
}
