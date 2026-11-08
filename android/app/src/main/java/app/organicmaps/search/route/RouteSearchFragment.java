package app.organicmaps.search.route;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import app.organicmaps.R;
import app.organicmaps.base.OnBackPressListener;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.search.QueryFilter;
import app.organicmaps.sdk.search.SearchEngine;
import app.organicmaps.sdk.search.SearchListener;
import app.organicmaps.sdk.search.SearchResult;
import app.organicmaps.sdk.util.Language;
import app.organicmaps.search.OpeningHoursFilterController;
import app.organicmaps.search.OpeningHoursFilterState;
import app.organicmaps.util.UiUtils;
import app.organicmaps.util.WindowInsetUtils;
import app.organicmaps.widget.PlaceholderView;
import app.organicmaps.widget.SearchToolbarController;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Search along the route, from the route planner and during the navigation: quick categories for the router, a free
 * text, the distance from the route and "Open on arrival". Results are ordered along the route; a tap shows a result
 * with its place page, the button adds it as a stop.
 */
public class RouteSearchFragment extends Fragment
    implements RouteSearchPresenter.View, SearchListener, RouteSearchAdapter.Listener, OnBackPressListener
{
  public static final String ARG_QUERY = "query";
  public static final String ARG_IS_CATEGORY = "is_category";
  public static final String ARG_ROUTER = "router";

  private RouteSearchPresenter mPresenter;
  private RouteSearchTexts mTexts;
  private RouteToolbarController mToolbarController;
  private ChipGroup mCategories;
  private final Map<String, Chip> mCategoryChips = new HashMap<>();
  private Chip mHalfWidthChip;
  private OpeningHoursFilterController mOpeningHoursFilter;
  private RouteSearchAdapter mAdapter;
  private PlaceholderView mPlaceholder;
  private ExtendedFloatingActionButton mShowOnMap;
  private boolean mSearching;
  private boolean mNoRoute;
  private int mResultsCount;
  // Categories are set by the code, ignore the chip listeners meanwhile.
  private boolean mUpdatingChips;

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
      close(true /* cancel */);
    }
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

    final Bundle args = getArguments();
    final Router router = args != null && args.containsKey(ARG_ROUTER) ? Router.valueOf(args.getInt(ARG_ROUTER))
                                                                       : RoutingController.get().getLastRouterType();
    final RouteSearchSettings settings = RouteSearchSettings.from(context);
    mPresenter = new RouteSearchPresenter(
        router, settings, new NativeRouteSearchEngine(Language.getKeyboardLocale(context)),
        new RouteStops(
            () -> Toast.makeText(requireContext(), R.string.route_search_stop_added, Toast.LENGTH_SHORT).show()),
        System::nanoTime);

    mToolbarController = new RouteToolbarController(view, requireActivity());
    mToolbarController.setHint(R.string.route_search);
    mCategories = view.findViewById(R.id.categories);
    mHalfWidthChip = view.findViewById(R.id.half_width);
    mPlaceholder = view.findViewById(R.id.placeholder);
    mShowOnMap = view.findViewById(R.id.show_on_map_fab);
    mShowOnMap.setOnClickListener(v -> mPresenter.onShowOnMapClicked());

    final RecyclerView results = view.findViewById(R.id.recycler);
    results.setLayoutManager(new LinearLayoutManager(context));
    mAdapter = new RouteSearchAdapter(this, mTexts);
    results.setAdapter(mAdapter);
    results.addOnScrollListener(new RecyclerView.OnScrollListener() {
      @Override
      public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState)
      {
        if (newState == RecyclerView.SCROLL_STATE_DRAGGING)
          mToolbarController.deactivate();
      }
    });
    ViewCompat.setOnApplyWindowInsetsListener(
        results, new WindowInsetUtils.ScrollableContentInsetsListener(results, mShowOnMap));

    initOpeningHoursFilter(view);
    SearchEngine.INSTANCE.addListener(this);
    mPresenter.attach(this);

    if (savedInstanceState == null && args != null && !TextUtils.isEmpty(args.getString(ARG_QUERY)))
      mToolbarController.setQuery(args.getString(ARG_QUERY), args.getBoolean(ARG_IS_CATEGORY));
    else if (savedInstanceState == null)
      mToolbarController.activate();
    updateViews();
  }

  @Override
  public void onDestroyView()
  {
    SearchEngine.INSTANCE.removeListener(this);
    mPresenter.detach();
    super.onDestroyView();
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

  private void updateViews()
  {
    if (!isAdded())
      return;
    final boolean hasQuery = mToolbarController.hasQuery();
    UiUtils.showIf(hasQuery && mResultsCount > 0, mShowOnMap);
    if (mNoRoute)
    {
      mPlaceholder.setContent(R.string.route_search, R.string.route_search_no_route, R.drawable.ic_search_fail);
      UiUtils.show(mPlaceholder);
    }
    else
    {
      mPlaceholder.setContent(R.string.route_search_not_found, R.string.route_search_not_found_hint,
                              R.drawable.ic_search_fail);
      UiUtils.showIf(hasQuery && !mSearching && mResultsCount == 0, mPlaceholder);
    }
  }

  private void close(boolean cancel)
  {
    if (cancel)
      mPresenter.onCancel();
    final Activity activity = getActivity();
    if (activity != null)
      activity.finish();
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
    mResultsCount = results.size();
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
  public void close()
  {
    close(false /* cancel */);
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

  // OnBackPressListener

  @Override
  public boolean onBackPressed()
  {
    mPresenter.onCancel();
    return false;
  }
}
