package app.organicmaps.search.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * When the online search supplements the offline search. The offline search always runs first and its results stay
 * on top; online results are only added when the offline maps can't answer well:
 * <ol>
 * <li>never for queries shorter than {@link #MIN_QUERY_LENGTH} characters (no requests while typing the first
 * letters) and never for category searches picked from the category list (Photon is no category search);</li>
 * <li>right away (in parallel to the offline search) if the search center, i.e. the map center or the user's position,
 * lies in a map region that is not downloaded;</li>
 * <li>otherwise after the offline search finished with fewer than {@link #FEW_RESULTS} results that match the whole
 * query ({@link OfflinePlace#countAnswers}): searching "Marienplatz München" in Berlin finds the Marienplatz in Berlin
 * and some "Münchener Straße", but none of them is what the user wants;</li>
 * <li>otherwise not.</li>
 * </ol>
 * The user's consent is checked separately; without it nothing is sent.
 */
public final class OnlineSearchPolicy
{
  public static final int MIN_QUERY_LENGTH = 3;
  /** Fewer offline results than this count as "few". */
  public static final int FEW_RESULTS = 3;

  public enum Decision
  {
    QUERY_TOO_SHORT(false),
    CATEGORY(false),
    /** The search center is in a region without downloaded map: search online right away. */
    REGION_NOT_DOWNLOADED(true),
    /** The offline search is still running: decide when it has finished. */
    WAIT_FOR_OFFLINE(false),
    /** The offline search found nothing or only a few results that match the whole query. */
    FEW_OFFLINE_RESULTS(true),
    ENOUGH_OFFLINE_RESULTS(false);

    private final boolean mOnline;

    Decision(boolean online)
    {
      mOnline = online;
    }

    /** True if the online search should supplement the offline results. */
    public boolean isOnline()
    {
      return mOnline;
    }
  }

  private OnlineSearchPolicy() {}

  /**
   * @param centerRegion map region at the search center, null if unknown or none (e.g. sea).
   * @param offlineResults number of offline results (places, without query suggestions) that match the whole query.
   * @param offlineFinished true if the offline search has finished.
   */
  @NonNull
  public static Decision decide(@Nullable String query, boolean isCategory, @Nullable RegionInfo centerRegion,
                                int offlineResults, boolean offlineFinished)
  {
    if (!isLongEnough(query))
      return Decision.QUERY_TOO_SHORT;
    if (isCategory)
      return Decision.CATEGORY;
    if (centerRegion != null && !centerRegion.isDownloaded())
      return Decision.REGION_NOT_DOWNLOADED;
    if (!offlineFinished)
      return Decision.WAIT_FOR_OFFLINE;
    return offlineResults < FEW_RESULTS ? Decision.FEW_OFFLINE_RESULTS : Decision.ENOUGH_OFFLINE_RESULTS;
  }

  /** True if the trimmed query (white space collapsed) has at least {@link #MIN_QUERY_LENGTH} characters. */
  public static boolean isLongEnough(@Nullable String query)
  {
    if (query == null)
      return false;
    final String normalized = query.trim().replaceAll("\\s+", " ");
    return normalized.codePointCount(0, normalized.length()) >= MIN_QUERY_LENGTH;
  }
}
