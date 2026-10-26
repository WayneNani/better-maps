package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/** Immutable snapshot of the connection list or detail view, rendered by the view. */
public final class ConnectionsState
{
  public enum Screen
  {
    /** Nothing to show (no query yet or routing closed). */
    NONE,
    LIST,
    DETAIL
  }

  /** How the connections are shown. */
  public enum ViewMode
  {
    /** One row per connection with a bar over its duration. */
    LIST,
    /** One column per connection on a common vertical time axis. */
    GRID
  }

  @NonNull
  final Screen mScreen;
  @NonNull
  final String mTimeLabel;
  final boolean mArriveBy;
  final boolean mLoading;
  final boolean mLoadingEarlier;
  final boolean mLoadingLater;
  @Nullable
  final TransitError mError;
  @Nullable
  final TransitError mPagingError;
  @NonNull
  final List<ItineraryItem> mItems;
  final boolean mCanLoadEarlier;
  final boolean mCanLoadLater;
  @Nullable
  final ItineraryItem mSelected;
  @NonNull
  final List<LegItem> mLegs;
  @Nullable
  final String mProviderName;
  @Nullable
  final String mAttributionUrl;
  @NonNull
  final ViewMode mViewMode;
  final boolean mSavedView;
  final boolean mSelectedSaved;
  final boolean mPast;
  @Nullable
  final String mSelectedDate;
  @NonNull
  final Instant mNow;

  ConnectionsState(@NonNull Screen screen, @NonNull String timeLabel, boolean arriveBy, boolean loading,
                   boolean loadingEarlier, boolean loadingLater, @Nullable TransitError error,
                   @Nullable TransitError pagingError, @NonNull List<ItineraryItem> items, boolean canLoadEarlier,
                   boolean canLoadLater, @Nullable ItineraryItem selected, @NonNull List<LegItem> legs,
                   @Nullable String providerName, @Nullable String attributionUrl, @NonNull ViewMode viewMode,
                   boolean savedView, boolean selectedSaved, boolean past, @Nullable String selectedDate,
                   @NonNull Instant now)
  {
    mScreen = screen;
    mTimeLabel = timeLabel;
    mArriveBy = arriveBy;
    mLoading = loading;
    mLoadingEarlier = loadingEarlier;
    mLoadingLater = loadingLater;
    mError = error;
    mPagingError = pagingError;
    mItems = Collections.unmodifiableList(items);
    mCanLoadEarlier = canLoadEarlier;
    mCanLoadLater = canLoadLater;
    mSelected = selected;
    mLegs = Collections.unmodifiableList(legs);
    mProviderName = providerName;
    mAttributionUrl = attributionUrl;
    mViewMode = viewMode;
    mSavedView = savedView;
    mSelectedSaved = selectedSaved;
    mPast = past;
    mSelectedDate = selectedDate;
    mNow = now;
  }

  @NonNull
  public Screen getScreen()
  {
    return mScreen;
  }

  /** "Depart now", "Depart 14:05", "Arrive by 18:00". */
  @NonNull
  public String getTimeLabel()
  {
    return mTimeLabel;
  }

  public boolean isArriveBy()
  {
    return mArriveBy;
  }

  /** The first page is loading. */
  public boolean isLoading()
  {
    return mLoading;
  }

  public boolean isLoadingEarlier()
  {
    return mLoadingEarlier;
  }

  public boolean isLoadingLater()
  {
    return mLoadingLater;
  }

  /** Error of the first page; the list is empty then. */
  @Nullable
  public TransitError getError()
  {
    return mError;
  }

  /** Error while loading earlier or later connections; the list is kept. */
  @Nullable
  public TransitError getPagingError()
  {
    return mPagingError;
  }

  @NonNull
  public List<ItineraryItem> getItems()
  {
    return mItems;
  }

  /** True if the query succeeded without any connection. */
  public boolean isEmpty()
  {
    return !mLoading && mError == null && mItems.isEmpty();
  }

  public boolean canLoadEarlier()
  {
    return mCanLoadEarlier;
  }

  public boolean canLoadLater()
  {
    return mCanLoadLater;
  }

  /** The connection shown in the detail view. */
  @Nullable
  public ItineraryItem getSelected()
  {
    return mSelected;
  }

  @NonNull
  public List<LegItem> getLegs()
  {
    return mLegs;
  }

  /** Provider of the shown data, for the attribution. */
  @Nullable
  public String getProviderName()
  {
    return mProviderName;
  }

  @Nullable
  public String getAttributionUrl()
  {
    return mAttributionUrl;
  }

  /** List or time grid; kept while a connection is open, so that "back" returns to it. */
  @NonNull
  public ViewMode getViewMode()
  {
    return mViewMode;
  }

  /** True if the detail view shows a saved connection (opened from the saved connections, not from a search). */
  public boolean isSavedView()
  {
    return mSavedView;
  }

  /** True if the connection in the detail view is saved. */
  public boolean isSelectedSaved()
  {
    return mSelectedSaved;
  }

  /** True if the saved connection in the detail view is over. */
  public boolean isPast()
  {
    return mPast;
  }

  /** Date of the connection in the detail view, e.g. "Sun, 25 Oct 2026". */
  @Nullable
  public String getSelectedDate()
  {
    return mSelectedDate;
  }

  /** Time of this state, e.g. for the "now" line of the time grid. */
  @NonNull
  public Instant getNow()
  {
    return mNow;
  }
}
