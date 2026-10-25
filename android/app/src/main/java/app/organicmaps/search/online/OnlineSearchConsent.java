package app.organicmaps.search.online;

import androidx.annotation.NonNull;
import app.organicmaps.transit.KeyValueStore;

/**
 * The user's decision about the online search. The search text and the approximate map position are sent to the
 * Photon server, so nothing is requested before the user agreed. Default: off.
 * <p>
 * The decision is stored in the switch preference {@link OnlineSearchSettings#KEY_ONLINE}: missing = not asked yet,
 * true = agreed, false = declined or switched off in the settings.
 */
public final class OnlineSearchConsent
{
  public enum State
  {
    /** Not asked yet: the search list offers the online search, nothing is sent. */
    UNDECIDED,
    GRANTED,
    /** Declined or switched off in the settings: neither asked again nor offered in the list. */
    DECLINED
  }

  /**
   * Version of the consent text. Increase it if the dialog describes a materially different data flow; users who
   * agreed to an older version are asked again.
   */
  public static final int VERSION = 1;

  static final String KEY_VERSION = "online_search_consent_version";
  static final String KEY_TIME = "online_search_consent_time";

  @NonNull
  private final KeyValueStore mStore;

  public OnlineSearchConsent(@NonNull KeyValueStore store)
  {
    mStore = store;
  }

  @NonNull
  public State getState()
  {
    if (!mStore.contains(OnlineSearchSettings.KEY_ONLINE))
      return State.UNDECIDED;
    if (!mStore.getBoolean(OnlineSearchSettings.KEY_ONLINE, false))
      return State.DECLINED;
    if (mStore.getInt(KEY_VERSION, 0) < VERSION)
      return State.UNDECIDED;
    return State.GRANTED;
  }

  public boolean isGranted()
  {
    return getState() == State.GRANTED;
  }

  public void grant(long nowMillis)
  {
    mStore.putInt(KEY_VERSION, VERSION);
    mStore.putLong(KEY_TIME, nowMillis);
    mStore.putBoolean(OnlineSearchSettings.KEY_ONLINE, true);
  }

  /** Declined in the dialog or switched off in the settings. */
  public void decline()
  {
    mStore.putBoolean(OnlineSearchSettings.KEY_ONLINE, false);
    mStore.remove(KEY_TIME);
  }

  /** Forgets the decision (e.g. for tests): the online search is offered again. */
  public void reset()
  {
    mStore.remove(OnlineSearchSettings.KEY_ONLINE);
    mStore.remove(KEY_VERSION);
    mStore.remove(KEY_TIME);
  }
}
