package app.organicmaps.transit.ui;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.settings.TransitSettingsActivity;
import app.organicmaps.transit.ConnectionsState;
import app.organicmaps.transit.ItineraryItem;
import app.organicmaps.transit.TransitConnectionsPresenter;
import app.organicmaps.transit.TransitFormatter;
import app.organicmaps.transit.TransitServices;
import app.organicmaps.util.UiUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import java.util.List;

/** Binds the online connection panel in the route planner to {@link TransitConnectionsPresenter}. */
public final class OnlineTransitPanel implements TransitConnectionsPresenter.View
{
  /** Share of the screen height the list may take. */
  private static final float MAX_HEIGHT_FRACTION = 0.45f;

  @NonNull
  private final Activity mActivity;
  @NonNull
  private final View mPanel;
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

  public OnlineTransitPanel(@NonNull Activity activity, @NonNull View panel)
  {
    mActivity = activity;
    mPanel = panel;
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
    final int maxHeight = (int) (activity.getResources().getDisplayMetrics().heightPixels * MAX_HEIGHT_FRACTION);
    mList.setMaxHeight(maxHeight);
    final ViewGroup.LayoutParams gridParams = mGridScroll.getLayoutParams();
    gridParams.height = maxHeight;
    mGridScroll.setLayoutParams(gridParams);

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
    }
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
   * Back from the detail view to the list or time grid. A saved connection was opened from the saved connections:
   * the route planner closes and that list opens again.
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
    SavedConnectionsActivity.start(activity);
    return true;
  }

  @Override
  public void render(@NonNull ConnectionsState state)
  {
    final boolean detail = state.getScreen() == ConnectionsState.Screen.DETAIL;
    final boolean grid = !detail && state.getViewMode() == ConnectionsState.ViewMode.GRID;
    UiUtils.showIf(detail, mBack, mDetailTitle, mSave);
    UiUtils.showIf(!detail, mTime, mViewToggle, mSavedList);
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
      mDetailTitle.setText(selected.getDeparture() + " – " + selected.getArrival() + " · " + selected.getDuration()
                           + " · " + selected.getTransfers());

    UiUtils.showIf(state.isLoading(), mProgress);

    if (state.getError() != null)
    {
      mStatusText.setText(TransitViews.errorText(state.getError()));
      UiUtils.showIf(state.getError().isRetryable(), mRetry);
      UiUtils.show(mStatus);
    }
    else if (state.isEmpty() && state.getScreen() != ConnectionsState.Screen.NONE)
    {
      mStatusText.setText(R.string.transit_no_connections);
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
    UiUtils.showIf(showGrid, mGridScroll);
    UiUtils.showIf(!grid, mList);
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
