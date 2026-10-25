package app.organicmaps.transit.ui;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import app.organicmaps.R;
import app.organicmaps.settings.TransitSettingsActivity;
import app.organicmaps.transit.ConnectionsState;
import app.organicmaps.transit.ItineraryItem;
import app.organicmaps.transit.TransitConnectionsPresenter;
import app.organicmaps.transit.TransitServices;
import app.organicmaps.util.UiUtils;
import com.google.android.material.button.MaterialButton;

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
  private final ConnectionsAdapter mConnections;
  private final LegsAdapter mLegs;
  private boolean mShown;
  private ConnectionsState.Screen mScreen;

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

    mConnections = new ConnectionsAdapter(mPresenter);
    mLegs = new LegsAdapter(mPresenter);
    mList.setLayoutManager(new LinearLayoutManager(activity));
    mList.setMaxHeight((int) (activity.getResources().getDisplayMetrics().heightPixels * MAX_HEIGHT_FRACTION));

    mBack.setOnClickListener(v -> mPresenter.back());
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
    return mShown && mPresenter.back();
  }

  @Override
  public void render(@NonNull ConnectionsState state)
  {
    final boolean detail = state.getScreen() == ConnectionsState.Screen.DETAIL;
    UiUtils.showIf(detail, mBack, mDetailTitle);
    UiUtils.showIf(!detail, mTime);
    mTime.setText(state.getTimeLabel());

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

    if (detail)
    {
      if (mList.getAdapter() != mLegs)
        mList.setAdapter(mLegs);
      mLegs.setLegs(state.getLegs());
    }
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
}
