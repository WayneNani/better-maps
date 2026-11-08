package app.organicmaps.search.route;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import app.organicmaps.R;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.search.QueryFilter;
import app.organicmaps.sdk.search.SearchEngine;
import app.organicmaps.sdk.search.SearchListener;
import app.organicmaps.sdk.search.SearchResult;
import app.organicmaps.sdk.util.Language;
import app.organicmaps.search.OpeningHoursFilterController;
import app.organicmaps.search.OpeningHoursFilterState;
import app.organicmaps.transit.ui.TransitMapViewport;
import app.organicmaps.transit.ui.TransitSheetLayout;
import app.organicmaps.util.UiUtils;
import app.organicmaps.widget.PlaceholderView;
import app.organicmaps.widget.SearchToolbarController;
import app.organicmaps.widget.placepage.PlacePageViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textview.MaterialTextView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Search along the route over the map, in the route planner and during the navigation: quick categories for the
 * router, a free text, the distance from the route and "Open on arrival" at the top. The results are marks on the map
 * first; a tap on a mark shows its place page with "Add to route". The list of the results is a panel at the bottom
 * that can be opened. Adding a stop keeps the search open, so that more stops can be added.
 * <p>
 * The map screen hosts this fragment over the map and implements {@link Host}.
 */
public class RouteSearchFragment extends Fragment
    implements RouteSearchPresenter.View, SearchListener, RouteSearchAdapter.Listener, TransitSheetLayout.DragListener
{
  public static final String TAG = "RouteSearchFragment";
  public static final String ARG_QUERY = "query";
  public static final String ARG_IS_CATEGORY = "is_category";
  public static final String ARG_ROUTER = "router";

  // Height of the open list, as a part of the screen: the map with the route stays visible above.
  private static final float LIST_HEIGHT_FRACTION = 0.45f;
  private static final int SETTLE_DURATION_MS = 200;

  /** The map screen. */
  public interface Host
  {
    /** The search was closed: remove it and show the route planner or the navigation again. */
    void onRouteSearchClosed();
  }

  private RouteSearchPresenter mPresenter;
  private RouteSearchTexts mTexts;
  private RouteToolbarController mToolbarController;
  private View mTop;
  private ChipGroup mCategories;
  private final Map<String, Chip> mCategoryChips = new HashMap<>();
  private Chip mHalfWidthChip;
  private OpeningHoursFilterController mOpeningHoursFilter;
  private RouteSearchAdapter mAdapter;
  private TransitSheetLayout mSheet;
  private View mBody;
  private RecyclerView mList;
  private PlaceholderView mPlaceholder;
  private MaterialTextView mSummary;
  private MaterialButton mDisplayButton;
  @Nullable
  private ValueAnimator mSettleAnimator;
  private int mBodyHeight;
  private boolean mSearching;
  private boolean mNoRoute;
  @NonNull
  private List<SearchResult> mResults = List.of();
  @NonNull
  private RouteSearchPresenter.Display mDisplay = RouteSearchPresenter.Display.MAP;
  // The user opened or closed the list: the map shows the results in the new free part once the list has settled.
  private boolean mFitAfterSettle;
  // Categories are set by the code, ignore the chip listeners meanwhile.
  private boolean mUpdatingChips;
  private boolean mClosed;

  private class RouteToolbarController extends SearchToolbarController
  {
    RouteToolbarController(@NonNull View root, @NonNull Activity activity)
    {
      super(root, activity);
    }

    @Override
    protected void onTextChanged(String query)
    {
      onQueryChanged(query, isCategory());
    }

    @Override
    public void onUpClick()
    {
      // Back to the route planner or the navigation.
      mPresenter.close();
    }
  }

  @NonNull
  public static Bundle arguments(@NonNull Router router, @Nullable String query, boolean isCategory)
  {
    final Bundle args = new Bundle();
    args.putInt(ARG_ROUTER, router.ordinal());
    args.putString(ARG_QUERY, query);
    args.putBoolean(ARG_IS_CATEGORY, isCategory);
    return args;
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState)
  {
    return inflater.inflate(R.layout.fragment_route_search, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
  {
    super.onViewCreated(view, savedInstanceState);
    final Context context = requireContext();
    mTexts = new AndroidRouteSearchTexts(context);

    final RouteSearchViewModel viewModel = new ViewModelProvider(requireActivity()).get(RouteSearchViewModel.class);
    RouteSearchPresenter presenter = viewModel.getPresenter();
    final boolean isNew = presenter == null;
    if (isNew && savedInstanceState != null)
    {
      // The app was restarted: the route and the search are gone.
      mPresenter = createPresenter(context, getArguments());
      mClosed = true;
      view.post(this::notifyClosed);
      return;
    }
    if (presenter == null)
    {
      presenter = createPresenter(context, getArguments());
      viewModel.setPresenter(presenter);
    }
    mPresenter = presenter;

    mToolbarController = new RouteToolbarController(view, requireActivity());
    mToolbarController.setHint(R.string.route_search);
    mTop = view.findViewById(R.id.route_search_top);
    mCategories = view.findViewById(R.id.categories);
    mHalfWidthChip = view.findViewById(R.id.half_width);
    initSheet(view);
    initOpeningHoursFilter(view);

    SearchEngine.INSTANCE.addListener(this);
    mPresenter.attach(this);

    if (isNew)
    {
      mPresenter.start(null, false);
      final Bundle args = getArguments();
      // The text field searches the query, also a category.
      if (args != null && !TextUtils.isEmpty(args.getString(ARG_QUERY)))
        mToolbarController.setQuery(args.getString(ARG_QUERY), args.getBoolean(ARG_IS_CATEGORY));
    }
    // Once the panels have their size, the map shows the route between them.
    view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
      if (mSettleAnimator == null)
        fitMap();
    });
    updateViews();
  }

  /** The search with the native search engine and the route; tests use other ones. */
  @VisibleForTesting
  @NonNull
  protected RouteSearchPresenter createPresenter(@NonNull Context context, @Nullable Bundle args)
  {
    final Router router = args != null && args.containsKey(ARG_ROUTER) ? Router.valueOf(args.getInt(ARG_ROUTER))
                                                                       : RoutingController.get().getLastRouterType();
    return new RouteSearchPresenter(
        router, RouteSearchSettings.from(context),
        new NativeRouteSearchEngine(Language.getKeyboardLocale(context), true /* withMap */), new RouteStops(),
        System::nanoTime);
  }

  private void initSheet(@NonNull View root)
  {
    mSheet = root.findViewById(R.id.route_search_sheet);
    mBody = root.findViewById(R.id.route_search_body);
    mSummary = root.findViewById(R.id.route_search_summary);
    mDisplayButton = root.findViewById(R.id.route_search_display);
    mPlaceholder = root.findViewById(R.id.placeholder);
    mList = root.findViewById(R.id.recycler);
    mList.setLayoutManager(new LinearLayoutManager(requireContext()));
    mAdapter = new RouteSearchAdapter(this, mTexts);
    mList.setAdapter(mAdapter);
    mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
      @Override
      public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState)
      {
        if (newState == RecyclerView.SCROLL_STATE_DRAGGING)
          mToolbarController.deactivate();
      }
    });
    mSheet.setScrollingChild(mList);
    mSheet.setDragListener(this);
    mDisplayButton.setOnClickListener(v -> toggleDisplay());
    root.findViewById(R.id.route_search_handle).setOnClickListener(v -> toggleDisplay());
    // Above the navigation bar.
    ViewCompat.setOnApplyWindowInsetsListener(mSheet, (v, insets) -> {
      final Insets bars =
          insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
      v.setPadding(bars.left, 0, bars.right, bars.bottom);
      return insets;
    });
  }

  @Override
  public void onDestroyView()
  {
    cancelSettle();
    if (mToolbarController != null)
    {
      SearchEngine.INSTANCE.removeListener(this);
      mPresenter.detach();
    }
    super.onDestroyView();
  }

  @VisibleForTesting
  @NonNull
  RouteSearchPresenter getPresenter()
  {
    return mPresenter;
  }

  /** The route was built again, e.g. with a new stop. */
  public void onRouteChanged()
  {
    if (!mClosed)
      mPresenter.onRouteChanged();
  }

  /** Closes the search, e.g. with the back button. */
  public void closeSearch()
  {
    mPresenter.close();
    if (mClosed)
      return;
    // Without a view (e.g. not yet created) the presenter cannot tell.
    mClosed = true;
    notifyClosed();
  }

  private void notifyClosed()
  {
    TransitMapViewport.reset(requireActivity());
    if (requireActivity() instanceof Host host)
      host.onRouteSearchClosed();
  }

  private void initOpeningHoursFilter(@NonNull View root)
  {
    // The filter of this search only, not of the normal search.
    final OpeningHoursFilterState state = new OpeningHoursFilterState();
    mOpeningHoursFilter = new OpeningHoursFilterController(root, state, mToolbarController::getQuery,
                                                           new OpeningHoursFilterController.Listener() {
                                                             @Override
                                                             public void onFilterChanged()
                                                             {
                                                               onOpeningHoursFilterChanged();
                                                             }

                                                             @Override
                                                             public void onQueryFilterRemoved(@NonNull String query)
                                                             {
                                                               mToolbarController.setQuery(query, false);
                                                             }
                                                           });
    mOpeningHoursFilter.setOpenNowText(R.string.route_search_filter_open_on_arrival);
  }

  private void onOpeningHoursFilterChanged()
  {
    mPresenter.onOpeningHoursFilterChanged(mOpeningHoursFilter.getFilter());
  }

  private void onQueryChanged(@NonNull String query, boolean isCategory)
  {
    final QueryFilter queryFilter =
        TextUtils.isEmpty(query) || isCategory
            ? null
            : SearchEngine.detectQueryFilter(query, Language.getKeyboardLocale(requireContext()));
    mOpeningHoursFilter.onQueryChanged(query, queryFilter);
    checkCategoryChip(isCategory ? query : null);
    // The filter first, so that the query is searched with it.
    mPresenter.onOpeningHoursFilterChanged(mOpeningHoursFilter.getFilter());
    mPresenter.onQueryChanged(query, isCategory);
    // The map shows the results: no keyboard over it.
    if (isCategory)
      mToolbarController.deactivate();
    updateViews();
  }

  private void checkCategoryChip(@Nullable String category)
  {
    mUpdatingChips = true;
    final Chip chip = category != null ? mCategoryChips.get(category) : null;
    if (chip != null)
      mCategories.check(chip.getId());
    else
      mCategories.clearCheck();
    mUpdatingChips = false;
  }

  private void toggleDisplay()
  {
    mToolbarController.deactivate();
    mFitAfterSettle = true;
    mPresenter.onDisplaySelected(mDisplay == RouteSearchPresenter.Display.MAP ? RouteSearchPresenter.Display.LIST
                                                                              : RouteSearchPresenter.Display.MAP);
  }

  private void updateViews()
  {
    if (!isAdded() || mToolbarController == null)
      return;
    final boolean hasQuery = mToolbarController.hasQuery();
    final int count = mResults.size();
    final String summary;
    if (!hasQuery)
      summary = getString(R.string.route_search_choose);
    else if (mNoRoute)
      summary = getString(R.string.route_search_no_route);
    else if (mSearching && count == 0)
      summary = getString(R.string.route_search_searching);
    else if (count == 0)
      summary = getString(R.string.route_search_not_found);
    else
      summary = getResources().getQuantityString(R.plurals.route_search_places, count, count);
    mSummary.setText(summary);

    final boolean isList = mDisplay == RouteSearchPresenter.Display.LIST;
    UiUtils.showIf(isList || count > 0, mDisplayButton);
    mDisplayButton.setText(isList ? R.string.route_search_show_map : R.string.route_search_show_list);
    mDisplayButton.setIconResource(isList ? R.drawable.ic_show_on_map : R.drawable.ic_transit_view_list);

    if (mNoRoute)
    {
      mPlaceholder.setContent(R.string.route_search, R.string.route_search_no_route, R.drawable.ic_search_fail);
      UiUtils.show(mPlaceholder);
    }
    else
    {
      mPlaceholder.setContent(R.string.route_search_not_found, R.string.route_search_not_found_hint,
                              R.drawable.ic_search_fail);
      UiUtils.showIf(hasQuery && !mSearching && count == 0, mPlaceholder);
    }
  }

  /** The map shows the route and the results between the search field and the list. */
  private void fitMap()
  {
    if (mClosed || !isAdded() || mTop == null || isPlacePageOpen())
      return;
    TransitMapViewport.fitBetween(requireActivity(), mTop, mSheet);
  }

  private boolean isPlacePageOpen()
  {
    return new ViewModelProvider(requireActivity()).get(PlacePageViewModel.class).getMapObject().getValue() != null;
  }

  private int listHeight()
  {
    final View root = getView();
    final int height = root != null ? root.getHeight() : 0;
    return (int) (height * LIST_HEIGHT_FRACTION);
  }

  private void setBodyHeight(int height)
  {
    mBodyHeight = Math.max(0, height);
    final ViewGroup.LayoutParams params = mBody.getLayoutParams();
    if (params.height != mBodyHeight)
    {
      params.height = mBodyHeight;
      mBody.setLayoutParams(params);
    }
  }

  /** Animates the list to its height for |display|. */
  private void settle(@NonNull RouteSearchPresenter.Display display, boolean animate)
  {
    cancelSettle();
    final int target = display == RouteSearchPresenter.Display.LIST ? listHeight() : 0;
    if (!animate || !isResumed())
    {
      mFitAfterSettle = false;
      setBodyHeight(target);
      return;
    }
    final ValueAnimator animator = ValueAnimator.ofInt(mBodyHeight, target);
    animator.setDuration(SETTLE_DURATION_MS);
    animator.setInterpolator(new DecelerateInterpolator());
    animator.addUpdateListener(a -> setBodyHeight((int) a.getAnimatedValue()));
    animator.addListener(new AnimatorListenerAdapter() {
      @Override
      public void onAnimationEnd(Animator animation)
      {
        if (mSettleAnimator == animation)
          mSettleAnimator = null;
        // The map between the panels, after the layout with the final height.
        mBody.requestLayout();
        if (mFitAfterSettle)
        {
          mFitAfterSettle = false;
          mBody.post(() -> {
            if (!mClosed)
              mPresenter.onMapAreaChanged();
          });
        }
      }
    });
    mSettleAnimator = animator;
    animator.start();
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

  // RouteSearchPresenter.View

  @Override
  public void showCategories(@NonNull List<RouteSearchCategory> categories)
  {
    mCategories.removeAllViews();
    mCategoryChips.clear();
    for (RouteSearchCategory category : categories)
    {
      final Chip chip = (Chip) getLayoutInflater().inflate(R.layout.item_route_search_category, mCategories, false);
      final String name = getString(category.mNameResId);
      chip.setId(View.generateViewId());
      chip.setText(name);
      chip.setChipIconResource(category.mIconResId);
      chip.setOnClickListener(v -> {
        if (mUpdatingChips)
          return;
        if (chip.isChecked())
          mToolbarController.setQuery(name, true);
        else
          mToolbarController.clear();
      });
      mCategories.addView(chip);
      mCategoryChips.put(name, chip);
    }
  }

  @Override
  public void showHalfWidth(int halfWidthMeters, @NonNull int[] options)
  {
    mHalfWidthChip.setText(getString(R.string.route_search_half_width, mTexts.width(halfWidthMeters)));
    final View.OnClickListener listener = v ->
    {
      final PopupMenu menu = new PopupMenu(requireContext(), mHalfWidthChip);
      final Menu items = menu.getMenu();
      for (int i = 0; i < options.length; ++i)
        items.add(Menu.NONE, i, i, getString(R.string.route_search_half_width, mTexts.width(options[i])))
            .setCheckable(true)
            .setChecked(options[i] == halfWidthMeters);
      menu.setOnMenuItemClickListener(item -> {
        mPresenter.onHalfWidthSelected(options[item.getItemId()]);
        return true;
      });
      menu.show();
    };
    mHalfWidthChip.setOnClickListener(listener);
    mHalfWidthChip.setOnCloseIconClickListener(listener);
  }

  @Override
  public void showResults(@NonNull List<SearchResult> results)
  {
    mResults = results;
    mAdapter.setResults(results);
    updateViews();
  }

  @Override
  public void showProgress(boolean show)
  {
    mSearching = show;
    if (show)
      mNoRoute = false;
    mToolbarController.showProgress(show);
    updateViews();
  }

  @Override
  public void showNoRoute()
  {
    mNoRoute = true;
    updateViews();
  }

  @Override
  public void showHiddenWithoutOpeningHours(int count)
  {
    mOpeningHoursFilter.setHiddenWithoutOpeningHours(count);
  }

  @Override
  public void showDisplay(@NonNull RouteSearchPresenter.Display display)
  {
    final boolean changed = display != mDisplay;
    mDisplay = display;
    final View root = getView();
    if (root != null && root.getHeight() == 0)
      root.post(() -> settle(mDisplay, false));
    else
      settle(display, changed);
    updateViews();
  }

  @Override
  public void prepareMap()
  {
    fitMap();
  }

  @Override
  public void showStopAdded(@NonNull SearchResult result)
  {
    Toast.makeText(requireContext(), R.string.route_search_stop_added, Toast.LENGTH_SHORT).show();
  }

  @Override
  public void close()
  {
    if (mClosed)
      return;
    mClosed = true;
    mToolbarController.deactivate();
    notifyClosed();
  }

  // SearchListener

  @Override
  public void onResultsUpdate(@NonNull SearchResult[] results, long timestamp)
  {
    mPresenter.onResultsUpdate(results, timestamp);
  }

  @Override
  public void onResultsEnd(long timestamp)
  {
    mPresenter.onResultsEnd(timestamp, 0);
  }

  @Override
  public void onResultsEnd(long timestamp, int hiddenWithoutOpeningHours)
  {
    mPresenter.onResultsEnd(timestamp, hiddenWithoutOpeningHours);
  }

  // RouteSearchAdapter.Listener

  @Override
  public void onResultClicked(int index)
  {
    mToolbarController.deactivate();
    mPresenter.onResultClicked(index);
  }

  @Override
  public void onAddStopClicked(@NonNull SearchResult result)
  {
    mPresenter.onAddStopClicked(result);
  }

  @Override
  public boolean canAddStop()
  {
    return mPresenter.canAddStop();
  }

  @Override
  public boolean isStop(@NonNull SearchResult result)
  {
    return mPresenter.isStop(result);
  }

  // TransitSheetLayout.DragListener

  @Override
  public boolean canDrag()
  {
    return !mResults.isEmpty() || mDisplay == RouteSearchPresenter.Display.LIST;
  }

  @Override
  public boolean canGrow()
  {
    return mBodyHeight < listHeight();
  }

  @Override
  public boolean canShrink()
  {
    return mBodyHeight > 0;
  }

  @Override
  public void onDragStart()
  {
    cancelSettle();
    mToolbarController.deactivate();
  }

  @Override
  public void onDrag(int delta)
  {
    setBodyHeight(Math.min(listHeight(), mBodyHeight + delta));
  }

  @Override
  public void onDragEnd(float velocity)
  {
    final RouteSearchPresenter.Display display =
        RouteSearchSheet.settle(mBodyHeight, listHeight(), velocity, mSheet.getFlingVelocity());
    mFitAfterSettle = true;
    if (display == mDisplay)
      settle(display, true);
    else
      mPresenter.onDisplaySelected(display);
  }
}
