package app.organicmaps.sdk.maplayer.subway;

/**
 * better-maps: decides whether "Subway map is unavailable" is worth telling the user.
 *
 * The core reports missing subway data whenever the transit layer is on and the visible maps have none. The layer is
 * also switched on automatically (e.g. by the public transport map mode on every app start), and a toast each time
 * covers the search button. So the notice is shown only once, right after the user turned the layer on themselves.
 */
final class SubwayNoDataNotice
{
  /** How long after the user switched the layer on a "no data" report still belongs to that action. */
  static final long USER_ACTION_WINDOW_MS = 10_000;

  private long mUserEnabledAtMs = -1;

  /** The user turned the subway layer on (layer menu). */
  void onUserEnabled(long nowMs)
  {
    mUserEnabledAtMs = nowMs;
  }

  /** The core reported missing subway data. @return true if the notice should be shown now. */
  boolean onNoData(long nowMs)
  {
    if (mUserEnabledAtMs < 0 || nowMs - mUserEnabledAtMs > USER_ACTION_WINDOW_MS)
      return false;
    // At most once per user action.
    mUserEnabledAtMs = -1;
    return true;
  }
}
