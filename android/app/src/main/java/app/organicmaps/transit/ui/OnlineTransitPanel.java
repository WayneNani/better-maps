package app.organicmaps.transit.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.settings.TransitSettingsActivity;
import app.organicmaps.transit.ConnectionsState;
import app.organicmaps.transit.ItineraryItem;
import app.organicmaps.transit.TransitConnectionsPresenter;
import app.organicmaps.transit.TransitFormatter;
import app.organicmaps.transit.TransitServices;
import app.organicmaps.transit.TransitSheet;
import app.organicmaps.util.UiUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import java.util.List;

/**
 * Binds the online connection panel in the route planner to {@link TransitConnectionsPresenter}.
 * <p>
 * The panel is a sheet at the bottom of the map that can be dragged between three states (see
 * {@link TransitSheet.State}): collapsed to a summary line, half (the list up to a part of the screen) and expanded
 * up to the route planner's toolbar. The map shows the selected connection in the area above the sheet.
 */
public final class OnlineTransitPanel implements TransitConnectionsPresenter.View, TransitSheetLayout.DragListener
{
  /** Changes the start or the destination of the search (the route points of the route planner). */
  public interface RoutePoints
  {
    /** Lets the user choose another start ({@code start}) or destination, e.g. with the search. */
    void choose(boolean start);

    /** Swaps start and destination. */
    void swap();
  }

  /** Share of the screen height the body (list, time grid, legs) takes in the half state. */
  private static final float HALF_HEIGHT_FRACTION = 0.45f;
  private static final long SETTLE_DURATION_MS = 220;

  @NonNull
  private final Activity mActivity;
  @NonNull
  private final TransitSheetLayout mPanel;
  private final View mHandle;
  private final View mSummary;
  private final TextView mSummaryText;
  private final View mHeader;
  private final View mPoints;
  private final View mFrom;
  private final View mTo;
  private final TextView mFromName;
  private final TextView mToName;
  @Nullable
  private final RoutePoints mRoutePoints;
  @NonNull
  private final TransitConnectionsPresenter mPresenter;
  private final MaterialButton mBack;
  private final MaterialButton mTime;
  private final TextView mDetailTitle;
  private final View mProgress;
  private final View mStatus;
  private final TextView mStatusText;
  private final View mRetry;
  private final MaxHeightRecyclerView mList;
  private final TextView mAttribution;
  private final MaterialButtonToggleGroup mViewToggle;
  private final View mSavedList;
  private final MaterialButton mSave;
  private final View mSavedInfo;
  private final TextView mSavedText;
  private final HorizontalScrollView mGridScroll;
  private final TimeGridView mGrid;
  @NonNull
  private final TransitFormatter mFormatter;
  private final ConnectionsAdapter mConnections;
  private final LegsAdapter mLegs;
  private boolean mShown;
  private ConnectionsState.Screen mScreen;
  // Set while the toggle is updated from the state, so that this is not taken for a user choice.
  private boolean mBinding;
  // First connection in the time grid, to keep it in view when earlier ones are added.
  @Nullable
  private String mGridFirstKey;

  @NonNull
  private TransitSheet.State mSheetState = TransitSheet.State.HALF;
  // Limit of the body height in pixels; the list may be lower if it has less content.
  private int mBodyHeight;
  private final int mHalfHeight;
  @Nullable
  private ValueAnimator mSettleAnimator;
  private boolean mWasLoading;

  public OnlineTransitPanel(@NonNull Activity activity, @NonNull View panel)
  {
    this(activity, panel, null);
  }

  /** @param routePoints changes start and destination; without it they are not shown. */
  public OnlineTransitPanel(@NonNull Activity activity, @NonNull View panel, @Nullable RoutePoints routePoints)
  {
    mActivity = activity;
    mRoutePoints = routePoints;
    mPanel = (TransitSheetLayout) panel;
    mHandle = panel.findViewById(R.id.transit_handle);
    mSummary = panel.findViewById(R.id.transit_summary);
    mSummaryText = panel.findViewById(R.id.transit_summary_text);
    mHeader = panel.findViewById(R.id.transit_header);
    mPoints = panel.findViewById(R.id.transit_points);
    mFrom = panel.findViewById(R.id.transit_from);
    mTo = panel.findViewById(R.id.transit_to);
    mFromName = panel.findViewById(R.id.transit_from_name);
    mToName = panel.findViewById(R.id.transit_to_name);
    mPresenter = TransitServices.get(activity).getConnectionsPresenter();
    mBack = panel.findViewById(R.id.transit_back);
    mTime = panel.findViewById(R.id.transit_time);
    mDetailTitle = panel.findViewById(R.id.transit_detail_title);
    mProgress = panel.findViewById(R.id.transit_progress);
    mStatus = panel.findViewById(R.id.transit_status);
    mStatusText = panel.findViewById(R.id.transit_status_text);
    mRetry = panel.findViewById(R.id.transit_retry);
    mList = panel.findViewById(R.id.transit_list);
    mAttribution = panel.findViewById(R.id.transit_attribution);
    mViewToggle = panel.findViewById(R.id.transit_view_toggle);
    mSavedList = panel.findViewById(R.id.transit_saved_list);
    mSave = panel.findViewById(R.id.transit_save);
    mSavedInfo = panel.findViewById(R.id.transit_saved_info);
    mSavedText = panel.findViewById(R.id.transit_saved_text);
    mGridScroll = panel.findViewById(R.id.transit_grid_scroll);
    mGrid = panel.findViewById(R.id.transit_grid);
    mFormatter = TransitServices.get(activity).createItemFactory(activity).getFormatter();

    mConnections = new ConnectionsAdapter(mPresenter);
    mLegs = new LegsAdapter(mPresenter);
    mList.setLayoutManager(new LinearLayoutManager(activity));
    mHalfHeight = (int) (activity.getResources().getDisplayMetrics().heightPixels * HALF_HEIGHT_FRACTION);
    setBodyHeight(mHalfHeight);

    mPanel.setScrollingChild(mList);
    mPanel.setDragListener(this);
    mHandle.setOnClickListener(v
                               -> settle(mSheetState == TransitSheet.State.COLLAPSED ? TransitSheet.State.HALF
                                                                                     : TransitSheet.State.COLLAPSED));
    mSummary.setOnClickListener(v -> settle(TransitSheet.State.HALF));
    // The map shows its content (e.g. the selected connection) above the sheet.
    mPanel.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
      if (!mShown)
        return;
      if (t != oldT)
        TransitMapViewport.fitAbove(mActivity, mPanel);
      // Never higher than the room below the route planner's toolbar, e.g. in landscape or when the header grows.
      if (mSheetState != TransitSheet.State.COLLAPSED && !isSettling())
      {
        final int max = maxBodyHeight();
        if (mBodyHeight > max)
          mPanel.post(() -> {
            if (mShown && !isSettling() && mBodyHeight > max)
              setBodyHeight(max);
          });
      }
    });

    mBack.setOnClickListener(v -> back(activity, mPresenter));
    mViewToggle.addOnButtonCheckedListener((group, id, checked) -> {
      if (!checked || mBinding)
        return;
      mPresenter.setViewMode(id == R.id.transit_view_grid ? ConnectionsState.ViewMode.GRID
                                                          : ConnectionsState.ViewMode.LIST);
    });
    mSavedList.setOnClickListener(v -> SavedConnectionsActivity.start(activity));
    mSave.setOnClickListener(v -> {
      final boolean wasSaved = mPresenter.getState().isSelectedSaved();
      mPresenter.toggleSaved();
      Toast.makeText(activity, wasSaved ? R.string.transit_unsaved : R.string.transit_saved, Toast.LENGTH_SHORT).show();
    });
    panel.findViewById(R.id.transit_search_again).setOnClickListener(v -> mPresenter.searchAgain());
    mGrid.setListener(new TimeGridView.Listener() {
      @Override
      public void onConnectionClick(int index)
      {
        mPresenter.select(index);
      }

      @Override
      public void onEarlierClick()
      {
        mPresenter.loadEarlier();
      }

      @Override
      public void onLaterClick()
      {
        mPresenter.loadLater();
      }
    });
    mRetry.setOnClickListener(v -> mPresenter.retry());
    if (routePoints != null)
    {
      mFrom.setOnClickListener(v -> routePoints.choose(true));
      mTo.setOnClickListener(v -> routePoints.choose(false));
      panel.findViewById(R.id.transit_swap).setOnClickListener(v -> routePoints.swap());
    }
    mTime.setOnClickListener(
        v -> TransitTimeDialog.show(activity, mPresenter.getTime(), mPresenter.isArriveBy(), mPresenter::setTime));
    panel.findViewById(R.id.transit_settings).setOnClickListener(v -> TransitSettingsActivity.start(activity));
  }

  public void show()
  {
    UiUtils.show(mPanel);
    if (!mShown)
    {
      mShown = true;
      mPresenter.attach(this);
    }
  }

  public void hide()
  {
    UiUtils.hide(mPanel);
    if (mShown)
    {
      mShown = false;
      mPresenter.detach(this);
      cancelSettle();
      TransitMapViewport.reset(mActivity);
    }
  }

  @NonNull
  public TransitSheet.State getSheetState()
  {
    return mSheetState;
  }

  /** Moves the sheet to a state, animated. */
  public void setSheetState(@NonNull TransitSheet.State state)
  {
    settle(state);
  }

  public boolean isShown()
  {
    return mShown;
  }

  /** Handles the back button. @return true if the detail view was closed. */
  public boolean onBackPressed()
  {
    return mShown && back(mActivity, mPresenter);
  }

  /**
   * Back from the detail view to the list or time grid. A saved connection closes the route planner; if it was
   * opened from the list of saved connections, that list opens again.
   *
   * @return false if there was no detail view.
   */
  public static boolean back(@NonNull Activity activity, @NonNull TransitConnectionsPresenter presenter)
  {
    if (!presenter.isShowingSaved())
      return presenter.back();
    presenter.back();
    final RoutingController controller = RoutingController.get();
    if (controller.isPlanning())
      controller.cancel();
    if (TransitServices.get(activity).getRouter().isSavedFromList())
      SavedConnectionsActivity.start(activity);
    return true;
  }

  @Override
  public void render(@NonNull ConnectionsState state)
  {
    // A new search shows its results.
    if (state.isLoading() && !mWasLoading && mSheetState == TransitSheet.State.COLLAPSED)
      settle(TransitSheet.State.HALF);
    mWasLoading = state.isLoading();
    final String summary = TransitSheet.summary(state, mFormatter.getTexts());
    if (summary != null)
      mSummaryText.setText(summary);
    else if (state.getError() != null)
      mSummaryText.setText(TransitViews.errorText(state.getError()));
    else
      mSummaryText.setText("");

    final boolean detail = state.getScreen() == ConnectionsState.Screen.DETAIL;
    final boolean grid = !detail && state.getViewMode() == ConnectionsState.ViewMode.GRID;
    UiUtils.showIf(detail, mBack, mDetailTitle, mSave);
    UiUtils.showIf(!detail, mTime, mViewToggle, mSavedList);
    UiUtils.showIf(mRoutePoints != null && state.getScreen() == ConnectionsState.Screen.LIST, mPoints);
    mFromName.setText(state.getFromLabel());
    mToName.setText(state.getToLabel());
    mFrom.setContentDescription(mActivity.getString(R.string.transit_change_start, state.getFromLabel()));
    mTo.setContentDescription(mActivity.getString(R.string.transit_change_destination, state.getToLabel()));
    mTime.setText(state.getTimeLabel());
    mBinding = true;
    mViewToggle.check(grid ? R.id.transit_view_grid : R.id.transit_view_list);
    mBinding = false;

    final boolean saved = state.isSelectedSaved();
    mSave.setIconResource(saved ? R.drawable.ic_transit_bookmark_filled : R.drawable.ic_transit_bookmark);
    mSave.setContentDescription(mActivity.getString(saved ? R.string.transit_unsave : R.string.transit_save));
    mSave.setSelected(saved);

    UiUtils.showIf(state.isSavedView(), mSavedInfo);
    if (state.isSavedView())
      mSavedText.setText(mActivity.getString(state.isPast() ? R.string.transit_saved_past : R.string.transit_saved_on,
                                             state.getSelectedDate()));

    final ItineraryItem selected = state.getSelected();
    if (selected != null)
      mDetailTitle.setText(TransitSheet.title(selected));

    UiUtils.showIf(state.isLoading(), mProgress);

    if (state.getError() != null)
    {
      mStatusText.setText(TransitViews.errorText(state.getError()));
      UiUtils.showIf(state.getError().isRetryable(), mRetry);
      UiUtils.show(mStatus);
    }
    else if (state.isEmpty() && state.getScreen() != ConnectionsState.Screen.NONE)
    {
      mStatusText.setText(state.isFarAhead() ? R.string.transit_no_connections_far_ahead
                                             : R.string.transit_no_connections);
      UiUtils.hide(mRetry);
      UiUtils.show(mStatus);
    }
    else if (state.getPagingError() != null)
    {
      mStatusText.setText(TransitViews.errorText(state.getPagingError()));
      UiUtils.showIf(state.getPagingError().isRetryable(), mRetry);
      UiUtils.show(mStatus);
    }
    else
      UiUtils.hide(mStatus);

    final boolean showGrid = grid && !state.getItems().isEmpty();
    final boolean expanded = mSheetState != TransitSheet.State.COLLAPSED || isSettling();
    UiUtils.showIf(showGrid && expanded, mGridScroll);
    UiUtils.showIf(!grid && expanded, mList);
    if (detail)
    {
      if (mList.getAdapter() != mLegs)
        mList.setAdapter(mLegs);
      mLegs.setLegs(state.getLegs());
    }
    else if (grid)
      bindGrid(state);
    else
    {
      if (mList.getAdapter() != mConnections)
        mList.setAdapter(mConnections);
      mConnections.setState(state);
    }
    if (mScreen != state.getScreen())
    {
      mScreen = state.getScreen();
      mList.scrollToPosition(0);
    }

    TransitViews.bindAttribution(mAttribution, null, state.getProviderName(), state.getAttributionUrl());
    updateCollapsedViews();
  }

  /** Collapsed: only the handle, the summary line and the progress; otherwise the header and the body. */
  private void updateCollapsedViews()
  {
    final boolean collapsed = mSheetState == TransitSheet.State.COLLAPSED && !isSettling();
    UiUtils.showIf(collapsed, mSummary);
    UiUtils.showIf(!collapsed, mHeader);
    if (collapsed)
      UiUtils.hide(mPoints, mStatus, mSavedInfo, mAttribution, mList, mGridScroll);
  }

  private void rerender()
  {
    if (mShown)
      render(mPresenter.getState());
  }

  // Sheet: dragging and settling.

  @Override
  public boolean canDrag()
  {
    return mShown && mPresenter.getState().getScreen() != ConnectionsState.Screen.NONE;
  }

  @Override
  public boolean canGrow()
  {
    if (mSheetState == TransitSheet.State.COLLAPSED)
      return true;
    final View body = visibleBody();
    if (body == null || mBodyHeight >= maxBodyHeight())
      return false;
    // The list shows everything already: nothing to gain.
    return body == mGridScroll || mList.canScrollVertically(1) || mList.canScrollVertically(-1);
  }

  @Override
  public boolean canShrink()
  {
    return mSheetState != TransitSheet.State.COLLAPSED || isSettling();
  }

  @Override
  public void onDragStart()
  {
    cancelSettle();
    if (mSheetState == TransitSheet.State.COLLAPSED)
    {
      // The header and an empty body appear and grow with the finger.
      mSheetState = TransitSheet.State.HALF;
      setBodyHeight(0);
      rerender();
      return;
    }
    final View body = visibleBody();
    if (body != null && body.getHeight() < mBodyHeight)
      setBodyHeight(body.getHeight());
  }

  @Override
  public void onDrag(int delta)
  {
    setBodyHeight(Math.max(0, Math.min(maxBodyHeight(), mBodyHeight + delta)));
  }

  @Override
  public void onDragEnd(float velocity)
  {
    final int content = contentHeight();
    final int max = Math.min(maxBodyHeight(), content);
    final int half = Math.min(mHalfHeight, max);
    settle(TransitSheet.settle(mBodyHeight, half, max, velocity, mPanel.getFlingVelocity()));
  }

  /** Animates the body to the height of {@code state}. */
  private void settle(@NonNull TransitSheet.State state)
  {
    cancelSettle();
    final boolean wasCollapsed = mSheetState == TransitSheet.State.COLLAPSED;
    final int max = maxBodyHeight();
    // The final limit; the list may stay lower if it has less content.
    final int limit = TransitSheet.bodyHeight(state, mHalfHeight, max);
    final int content = contentHeight();
    final int target = Math.min(limit, state == TransitSheet.State.COLLAPSED ? 0 : content);
    mSheetState = state;
    if (wasCollapsed && state != TransitSheet.State.COLLAPSED)
    {
      setBodyHeight(0);
      rerender();
    }
    final int from = Math.min(mBodyHeight, content);
    final ValueAnimator animator = ValueAnimator.ofInt(from, target);
    animator.setDuration(SETTLE_DURATION_MS);
    animator.setInterpolator(new DecelerateInterpolator());
    animator.addUpdateListener(a -> setBodyHeight((int) a.getAnimatedValue()));
    animator.addListener(new AnimatorListenerAdapter() {
      private boolean mCancelled;

      @Override
      public void onAnimationCancel(Animator animation)
      {
        mCancelled = true;
      }

      @Override
      public void onAnimationEnd(Animator animation)
      {
        if (mSettleAnimator == animation)
          mSettleAnimator = null;
        if (mCancelled)
          return;
        setBodyHeight(limit);
        rerender();
        fitMapAfterLayout();
      }
    });
    mSettleAnimator = animator;
    animator.start();
  }

  private boolean isSettling()
  {
    return mSettleAnimator != null;
  }

  private void cancelSettle()
  {
    if (mSettleAnimator != null)
    {
      final ValueAnimator animator = mSettleAnimator;
      mSettleAnimator = null;
      animator.cancel();
    }
  }

  /** Once the sheet has its new height: the map area above it changes and the connection is fitted into it. */
  private void fitMapAfterLayout()
  {
    mPanel.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
      @Override
      public void onGlobalLayout()
      {
        mPanel.getViewTreeObserver().removeOnGlobalLayoutListener(this);
        if (mShown && TransitMapViewport.fitAbove(mActivity, mPanel))
          mPresenter.refreshRouteDisplay();
      }
    });
    mPanel.requestLayout();
  }

  private void setBodyHeight(int height)
  {
    mBodyHeight = height;
    // 0 means "no limit" for the list.
    mList.setMaxHeight(Math.max(1, height));
    final ViewGroup.LayoutParams params = mGridScroll.getLayoutParams();
    if (params.height != height)
    {
      params.height = height;
      mGridScroll.setLayoutParams(params);
    }
  }

  @Nullable
  private View visibleBody()
  {
    if (UiUtils.isVisible(mGridScroll))
      return mGridScroll;
    return UiUtils.isVisible(mList) ? mList : null;
  }

  /** Height of everything in the body: the list may be lower than the limit. */
  private int contentHeight()
  {
    if (mPresenter.getState().getViewMode() == ConnectionsState.ViewMode.GRID
        && mPresenter.getState().getScreen() == ConnectionsState.Screen.LIST)
      return Integer.MAX_VALUE;
    if (mList.getAdapter() == null || mList.getAdapter().getItemCount() == 0)
      return 0;
    final int range = mList.computeVerticalScrollRange() + mList.getPaddingTop() + mList.getPaddingBottom();
    // Before the first layout the range is not known yet.
    return range > 0 ? range : Integer.MAX_VALUE;
  }

  /** The body may grow until the sheet reaches the route planner's toolbar (or the top of the screen). */
  private int maxBodyHeight()
  {
    final int[] location = new int[2];
    mPanel.getLocationOnScreen(location);
    // The sheet grows upwards from the bottom of the screen (above the navigation bar). Where it is placed higher
    // and grows downwards instead (e.g. in tests), the screen's bottom limits it.
    int bottom = location[1] + mPanel.getHeight();
    final View root = mPanel.getRootView();
    final WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(mPanel);
    final int[] rootLocation = new int[2];
    root.getLocationOnScreen(rootLocation);
    final int bottomInset = insets != null ? insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom : 0;
    bottom = Math.max(bottom, rootLocation[1] + root.getHeight() - bottomInset);
    int top = 0;
    final View toolbar = mActivity.findViewById(R.id.routing_plan_frame);
    if (toolbar != null && toolbar.isShown())
    {
      toolbar.getLocationOnScreen(location);
      top = location[1] + toolbar.getHeight();
    }
    else if (mPanel.getParent() instanceof View parent)
    {
      parent.getLocationOnScreen(location);
      top = location[1] + parent.getPaddingTop();
    }
    int chrome = 0;
    for (int i = 0; i < mPanel.getChildCount(); ++i)
    {
      final View child = mPanel.getChildAt(i);
      if (child != mList && child != mGridScroll && child != mSummary && child.getVisibility() != View.GONE)
        chrome += child.getHeight();
    }
    if (UiUtils.isVisible(mSummary))
      chrome += mHeader.getHeight();
    final int margin = mActivity.getResources().getDimensionPixelSize(R.dimen.margin_half);
    return Math.max(0, bottom - top - chrome - mPanel.getPaddingBottom() - margin);
  }

  private void bindGrid(@NonNull ConnectionsState state)
  {
    final List<ItineraryItem> items = state.getItems();
    mGrid.setData(items, state.getNow(), mFormatter, state.canLoadEarlier() || state.isLoadingEarlier(),
                  state.isLoadingEarlier(), state.canLoadLater() || state.isLoadingLater(), state.isLoadingLater());
    final String first = items.isEmpty() ? null : items.get(0).getKey();
    if (first != null && !first.equals(mGridFirstKey))
    {
      // Earlier connections were added in front: keep the former first one in place. Otherwise a new search.
      int shift = 0;
      for (int i = 0; i < items.size(); ++i)
        if (items.get(i).getKey().equals(mGridFirstKey))
          shift = i;
      final int x = shift * mGrid.getColumnStep();
      mGridScroll.post(() -> {
        if (x > 0)
          mGridScroll.scrollBy(x, 0);
        else
          mGridScroll.scrollTo(0, 0);
      });
    }
    mGridFirstKey = first;
  }
}
