package app.organicmaps.transit;

import androidx.annotation.NonNull;

/**
 * The user's decision about online public transport (Transitous). Start, destination and time of a query (and
 * the position of a stop for departures) are sent to the server, so nothing is requested before the user agreed.
 * <p>
 * The decision is stored in the switch preference {@link TransitSettings#KEY_ONLINE}: missing = not asked yet,
 * true = agreed, false = declined or revoked in the settings.
 */
public final class TransitConsent
{
  public enum State
  {
    /** Not asked yet: ask before the first online request. */
    UNDECIDED,
    GRANTED,
    /** Declined in the dialog or switched off in the settings: never ask again automatically. */
    DECLINED
  }

  /**
   * Version of the consent text. Increase it if the dialog describes a materially different data flow (e.g. a
   * new provider); users who agreed to an older version are asked again.
   */
  public static final int VERSION = 1;

  static final String KEY_VERSION = "transit_consent_version";
  static final String KEY_TIME = "transit_consent_time";

  @NonNull
  private final KeyValueStore mStore;

  public TransitConsent(@NonNull KeyValueStore store)
  {
    mStore = store;
  }

  @NonNull
  public State getState()
  {
    if (!mStore.contains(TransitSettings.KEY_ONLINE))
      return State.UNDECIDED;
    if (!mStore.getBoolean(TransitSettings.KEY_ONLINE, false))
      return State.DECLINED;
    if (mStore.getInt(KEY_VERSION, 0) < VERSION)
      return State.UNDECIDED;
    return State.GRANTED;
  }

  public boolean isGranted()
  {
    return getState() == State.GRANTED;
  }

  /** True if the opt-in dialog should be shown before the online feature is used. */
  public boolean shouldAsk()
  {
    return getState() == State.UNDECIDED;
  }

  public void grant(long nowMillis)
  {
    mStore.putInt(KEY_VERSION, VERSION);
    mStore.putLong(KEY_TIME, nowMillis);
    mStore.putBoolean(TransitSettings.KEY_ONLINE, true);
  }

  /** Declined in the dialog or revoked in the settings. */
  public void decline()
  {
    mStore.putBoolean(TransitSettings.KEY_ONLINE, false);
    mStore.remove(KEY_TIME);
  }

  /** Forgets the decision, so that the user is asked again (e.g. for tests or a reset). */
  public void reset()
  {
    mStore.remove(TransitSettings.KEY_ONLINE);
    mStore.remove(KEY_VERSION);
    mStore.remove(KEY_TIME);
  }
}
