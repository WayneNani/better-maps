package app.organicmaps.search;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import app.organicmaps.BuildConfig;
import app.organicmaps.MwmActivity;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.base.BaseMwmFragment;
import app.organicmaps.downloader.CountrySuggestFragment;
import app.organicmaps.downloader.MapManagerHelper;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Map;
import app.organicmaps.sdk.api.RequestType;
import app.organicmaps.sdk.bookmarks.data.FeatureId;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.downloader.MapManager;
import app.organicmaps.sdk.location.LocationListener;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.search.OpeningHoursFilter;
import app.organicmaps.sdk.search.QueryFilter;
import app.organicmaps.sdk.search.SearchEngine;
import app.organicmaps.sdk.search.SearchListener;
import app.organicmaps.sdk.search.SearchRecents;
import app.organicmaps.sdk.search.SearchResult;
import app.organicmaps.sdk.search.online.OnlineSearchResult;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.sdk.util.Language;
import app.organicmaps.sdk.util.SharedPropertiesUtils;
import app.organicmaps.sdk.util.log.Logger;
import app.organicmaps.search.online.OfflinePlace;
import app.organicmaps.search.online.OnlineSearchItem;
import app.organicmaps.search.online.OnlineSearchMapLink;
import app.organicmaps.search.online.OnlineSearchPresenter;
import app.organicmaps.search.online.OnlineSearchServices;
import app.organicmaps.search.online.RegionInfo;
import app.organicmaps.search.online.ui.OnlineSearchAdapter;
import app.organicmaps.search.online.ui.OnlineSearchConsentDialog;
import app.organicmaps.transit.TransitDebug;
import app.organicmaps.util.UiUtils;
import app.organicmaps.util.Utils;
import app.organicmaps.util.WindowInsetUtils;
import app.organicmaps.widget.PlaceholderView;
import app.organicmaps.widget.SearchToolbarController;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SearchFragment extends BaseMwmFragment implements SearchListener, CategoriesAdapter.CategoriesUiListener
{
  private static final String STATE_CATEGORY_QUERY = "category_query";

  private long mLastQueryTimestamp;
  private boolean compatibleContactsAppInstalled = true;
  @NonNull
  private final List<HiddenCommand> mHiddenCommands = new ArrayList<>();

  private static class LastPosition
  {
    double lat;
    double lon;
    boolean valid;

    public void set(double lat, double lon)
    {
      this.lat = lat;
      this.lon = lon;
      valid = true;
    }
  }

  private class ToolbarController extends SearchToolbarController
  {
    public ToolbarController(View root)
    {
      super(root, SearchFragment.this.requireActivity());
    }

    @Override
    protected void onTextChanged(String query)
    {
      if (!isAdded())
        return;

      updateQueryFilter(query);

      if (TextUtils.isEmpty(query))
      {
        setHiddenWithoutOpeningHours(0);
        mSearchAdapter.clear();
        mOnlinePresenter.clear();
        stopSearch();
        return;
      }

      if (tryRecognizeHiddenCommand(query))
      {
        mSearchAdapter.clear();
        mOnlinePresenter.clear();
        stopSearch();
        closeSearch();
        return;
      }

      runSearch();
    }

    @Override
    protected boolean onStartSearchClick()
    {
      deactivate();
      return true;
    }

    @Override
    protected int getVoiceInputPrompt()
    {
      return R.string.search_map;
    }

    @Override
    protected void startVoiceRecognition(Intent intent)
    {
      startVoiceRecognitionForResult.launch(intent);
    }

    @Override
    protected boolean supportsVoiceSearch()
    {
      return true;
    }

    @Override
    public void onUpClick()
    {
      if (!onBackPressed())
        super.onUpClick();
    }

    @Override
    public void clear()
    {
      super.clear();
    }
  }

  private View mResultsFrame;
  private View mTabFrame;
  // better-maps: "Open now" chips and the hint of places hidden by them.
  @Nullable
  private OpeningHoursFilterController mOpeningHoursFilter;
  private PlaceholderView mResultsPlaceholder;
  private ExtendedFloatingActionButton mShowOnMapFab;
  private FloatingActionButton mOpenContactPicker;

  @NonNull
  private SearchToolbarController mToolbarController;

  @SuppressWarnings("NullableProblems")
  @NonNull
  private SearchAdapter mSearchAdapter;
  // better-maps: online results (Photon) below the offline results, see OnlineSearchPolicy.
  @SuppressWarnings("NullableProblems")
  @NonNull
  private OnlineSearchAdapter mOnlineAdapter;
  @SuppressWarnings("NullableProblems")
  @NonNull
  private OnlineSearchPresenter mOnlinePresenter;

  private final List<RecyclerView> mAttachedRecyclers = new ArrayList<>();
  private final RecyclerView.OnScrollListener mRecyclerListener = new RecyclerView.OnScrollListener() {
    @Override
    public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState)
    {
      if (newState == RecyclerView.SCROLL_STATE_DRAGGING)
        mToolbarController.deactivate();
    }
  };

  private final LastPosition mLastPosition = new LastPosition();
  private boolean mSearchRunning;
  private String mInitialQuery;
  // better-maps: the initial query is a search category, e.g. opened again from the search bar on the map.
  private boolean mInitialIsCategory;
  @Nullable
  private String mInitialLocale;
  private boolean mInitialSearchOnMap = false;

  private final ActivityResultLauncher<Intent> startVoiceRecognitionForResult =
      registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                                activityResult -> mToolbarController.onVoiceRecognitionResult(activityResult));

  private final ActivityResultLauncher<Intent> mContactPickerLauncher =
          registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
          result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null)
            {
              mToolbarController.setQuery(Utils.getContactAddress(requireContext(), result.getData().getData()));
            }
          }
  );

  private final LocationListener mLocationListener = new LocationListener() {
    @Override
    public void onLocationUpdated(@NonNull Location location)
    {
      mLastPosition.set(location.getLatitude(), location.getLongitude());
    }
  };

  private static boolean doShowDownloadSuggest()
  {
    return (MapManager.nativeGetDownloadedCount() == 0 && !MapManager.nativeIsDownloading());
  }

  private void showDownloadSuggest()
  {
    final FragmentManager fm = getChildFragmentManager();
    final String fragmentName = CountrySuggestFragment.class.getName();
    Fragment fragment = fm.findFragmentByTag(fragmentName);

    if (fragment == null || fragment.isDetached() || fragment.isRemoving())
    {
      fragment = fm.getFragmentFactory().instantiate(requireActivity().getClassLoader(), fragmentName);
      fm.beginTransaction().add(R.id.download_suggest_frame, fragment, fragmentName).commit();
    }
  }

  private void hideDownloadSuggest()
  {
    if (!isAdded())
      return;

    final FragmentManager manager = getChildFragmentManager();
    final Fragment fragment = manager.findFragmentByTag(CountrySuggestFragment.class.getName());
    if (fragment != null && !fragment.isDetached() && !fragment.isRemoving())
      manager.beginTransaction().remove(fragment).commitAllowingStateLoss();
  }

  private void updateFrames()
  {
    final boolean hasQuery = mToolbarController.hasQuery();
    MaterialToolbar toolbar = mToolbarController.getToolbar();
    AppBarLayout.LayoutParams lp = (AppBarLayout.LayoutParams) toolbar.getLayoutParams();
    lp.setScrollFlags(hasQuery ? AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS
                                     | AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
                               : 0);
    toolbar.setLayoutParams(lp);

    UiUtils.showIf(hasQuery, mResultsFrame);
    UiUtils.showIf(hasQuery, mShowOnMapFab);

    if (hasQuery)
      mTabFrame.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    else
      mTabFrame.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    UiUtils.showIf(!hasQuery && compatibleContactsAppInstalled, mOpenContactPicker);
    if (hasQuery)
      hideDownloadSuggest();
    else if (doShowDownloadSuggest())
      showDownloadSuggest();
    else
      hideDownloadSuggest();
  }

  private void updateResultsPlaceholder()
  {
    final boolean show = !mSearchRunning && mSearchAdapter.getItemCount() == 0 && mToolbarController.hasQuery()
                      && !mOnlineAdapter.getState().isVisible();

    UiUtils.showIf(show, mResultsPlaceholder);
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
  {
    return inflater.inflate(R.layout.fragment_search, container, false);
  }

  @CallSuper
  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
  {
    super.onViewCreated(view, savedInstanceState);
    mSearchAdapter = new SearchAdapter(this);
    initOnlineSearch();
    // A recreated screen keeps the query of its text field; the arguments would replace it.
    if (savedInstanceState == null)
    {
      readArguments();
      // better-maps: a new search starts without the "Open now" filter of a previous one.
      OpeningHoursFilterState.SESSION.onSearchOpened(mInitialQuery);
    }

    ViewGroup root = (ViewGroup) view;
    mTabFrame = root.findViewById(R.id.tab_frame);
    ViewPager2 pager = mTabFrame.findViewById(R.id.pages);

    mToolbarController = new ToolbarController(view);
    // better-maps: the text field restores its text after this; a restored category text stays categorial.
    if (savedInstanceState != null)
      mToolbarController.restoreCategory(savedInstanceState.getString(STATE_CATEGORY_QUERY));
    TabLayout tabLayout = root.findViewById(R.id.tabs);

    if (Config.isSearchHistoryEnabled())
      tabLayout.setVisibility(View.VISIBLE);
    else
      tabLayout.setVisibility(View.GONE);

    final TabAdapter tabAdapter = new TabAdapter(this, pager, tabLayout);

    mResultsFrame = root.findViewById(R.id.results_frame);
    RecyclerView mResults = mResultsFrame.findViewById(R.id.recycler);
    setRecyclerScrollListener(mResults);
    mResultsPlaceholder = mResultsFrame.findViewById(R.id.placeholder);
    mResultsPlaceholder.setContent(R.string.search_not_found, R.string.search_not_found_query,
                                   R.drawable.ic_search_fail);
    mSearchAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver()

                                               {
                                                 @Override
                                                 public void onChanged()
                                                 {
                                                   updateResultsPlaceholder();
                                                 }
                                               });
    initOpeningHoursFilter(mResultsFrame);
    mShowOnMapFab = root.findViewById(R.id.show_on_map_fab);
    mShowOnMapFab.setOnClickListener(v -> showAllResultsOnMap());
    mOpenContactPicker = root.findViewById(R.id.open_contact_picker);
    mOpenContactPicker.setOnClickListener(v -> {
      if (Utils.openContactPicker().resolveActivity(getContext().getPackageManager()) != null) {
        mContactPickerLauncher.launch(Utils.openContactPicker());
      }
      else {
        Logger.i("mOpenContactPicker", "No compatible contacts app (needs to support postal-address_v2) installed, hiding contacts picker...");
        mOpenContactPicker.hide();
      }
    });

    if (Utils.openContactPicker().resolveActivity(getContext().getPackageManager()) == null) {
      Logger.i("mOpenContactPicker", "No compatible contacts app (needs to support postal-address_v2) installed, hiding contacts picker...");
      compatibleContactsAppInstalled = false;
    }

    ViewCompat.setOnApplyWindowInsetsListener(mResultsPlaceholder, new WindowInsetUtils.ScrollableContentInsetsListener(mResultsPlaceholder, mOpenContactPicker));

    mResults.setLayoutManager(new LinearLayoutManager(view.getContext()));
    mResults.setAdapter(new ConcatAdapter(mSearchAdapter, mOnlineAdapter));

    updateFrames();
    updateResultsPlaceholder();
    ViewCompat.setOnApplyWindowInsetsListener(
        mResults, new WindowInsetUtils.ScrollableContentInsetsListener(mResults, mShowOnMapFab));

    mToolbarController.activate();

    SearchEngine.INSTANCE.addListener(this);

    tabAdapter.setTabSelectedListener(tab -> mToolbarController.deactivate());

    if (mInitialSearchOnMap)
      showAllResultsOnMap();
  }

  @Override
  public void onViewStateRestored(@Nullable Bundle savedInstanceState)
  {
    super.onViewStateRestored(savedInstanceState);
    mToolbarController.onViewStateRestored();
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState)
  {
    super.onSaveInstanceState(outState);
    if (getView() != null)
      outState.putString(STATE_CATEGORY_QUERY, mToolbarController.getCategoryQuery());
  }

  private void initOpeningHoursFilter(@NonNull View root)
  {
    final OpeningHoursFilterController.Listener listener = new OpeningHoursFilterController.Listener() {
      @Override
      public void onFilterChanged()
      {
        onOpeningHoursFilterChanged();
      }

      @Override
      public void onQueryFilterRemoved(@NonNull String query)
      {
        setQuery(query, false);
      }
    };
    mOpeningHoursFilter =
        new OpeningHoursFilterController(root, OpeningHoursFilterState.SESSION, this::getQuery, listener);
  }

  /**
   * @param count the number of places hidden by the "Open now" filter because of unknown opening hours.
   */
  private void setHiddenWithoutOpeningHours(int count)
  {
    if (mOpeningHoursFilter != null)
      mOpeningHoursFilter.setHiddenWithoutOpeningHours(count);
  }

  private void updateQueryFilter(@Nullable String query)
  {
    if (mOpeningHoursFilter == null)
      return;
    final QueryFilter queryFilter =
        TextUtils.isEmpty(query) || isCategory()
            ? null
            : SearchEngine.detectQueryFilter(query, Language.getKeyboardLocale(requireContext()));
    mOpeningHoursFilter.onQueryChanged(query, queryFilter);
  }

  private void onOpeningHoursFilterChanged()
  {
    if (!isAdded() || !mToolbarController.hasQuery())
      return;

    mSearchAdapter.clear();
    runSearch();
    // Hide "not found" placeholder, shown by clear(), while the new search is running.
    updateResultsPlaceholder();
  }

  @NonNull
  private OpeningHoursFilter getOpeningHoursFilter()
  {
    return mOpeningHoursFilter != null ? mOpeningHoursFilter.getFilter() : OpeningHoursFilter.OFF;
  }

  @Override
  public void onStart()
  {
    super.onStart();
    mToolbarController.attach(requireActivity());
  }

  public void onResume()
  {
    super.onResume();
    MwmApplication.from(requireContext()).getLocationHelper().addListener(mLocationListener);
    if (mInitialQuery != null)
    {
      setQuery(mInitialQuery, mInitialIsCategory);
      mInitialQuery = null;
    }
  }

  @Override
  public void onPause()
  {
    MwmApplication.from(requireContext()).getLocationHelper().removeListener(mLocationListener);
    super.onPause();
  }

  @Override
  public void onStop()
  {
    super.onStop();
    mToolbarController.detach();
  }

  @Override
  public void onDestroyView()
  {
    mOnlinePresenter.setListener(null);
    mOnlinePresenter.clear();
    super.onDestroyView();
  }

  @Override
  public void onDestroy()
  {
    for (RecyclerView v : mAttachedRecyclers)
      v.removeOnScrollListener(mRecyclerListener);

    mAttachedRecyclers.clear();
    SearchEngine.INSTANCE.removeListener(this);
    super.onDestroy();
  }

  private String getQuery()
  {
    return mToolbarController.getQuery();
  }
  private boolean isCategory()
  {
    return mToolbarController.isCategory();
  }
  void setQuery(String text, boolean isCategory)
  {
    mToolbarController.setQuery(text, isCategory);
  }

  private void readArguments()
  {
    final Bundle arguments = getArguments();
    if (arguments == null)
      return;

    mInitialQuery = arguments.getString(SearchActivity.EXTRA_QUERY);
    mInitialIsCategory = arguments.getBoolean(SearchActivity.EXTRA_IS_CATEGORY);
    mInitialLocale = arguments.getString(SearchActivity.EXTRA_LOCALE);
    mInitialSearchOnMap = arguments.getBoolean(SearchActivity.EXTRA_SEARCH_ON_MAP);
  }

  private boolean tryRecognizeHiddenCommand(@NonNull String query)
  {
    for (HiddenCommand command : getHiddenCommands())
    {
      if (command.execute(query))
        return true;
    }

    return false;
  }

  @NonNull
  private List<HiddenCommand> getHiddenCommands()
  {
    if (mHiddenCommands.isEmpty())
    {
      mHiddenCommands.addAll(Arrays.asList(
          new BadStorageCommand("?emulateBadStorage", requireContext()), new JavaCrashCommand("?emulateJavaCrash"),
          new NativeCrashCommand("?emulateNativeCrash"), new PushTokenCommand("?pushToken")));
      if (BuildConfig.DEBUG)
        mHiddenCommands.add(new TransitPlanCommand("?transit-plan", requireActivity()));
    }

    return mHiddenCommands;
  }

  void showSingleResultOnMap(@NonNull SearchResult result, int resultIndex)
  {
    final String query = getQuery();
    if (Config.isSearchHistoryEnabled())
      SearchRecents.add(query, requireContext());
    SearchEngine.INSTANCE.cancel();
    SearchEngine.INSTANCE.setQuery(query, isCategory());

    if (RoutingController.get().isWaitingPoiPick())
    {
      final String subtitle = (result.description != null) ? result.description.localizedFeatureType : "";
      final String title = TextUtils.isEmpty(result.name) ? subtitle : result.name;

      final MapObject point =
          MapObject.createMapObject(FeatureId.EMPTY, MapObject.SEARCH, title, subtitle, result.lat, result.lon);
      RoutingController.get().onPoiSelected(point);
    }
    else
    {
      SearchEngine.INSTANCE.showResult(resultIndex);
    }

    mToolbarController.deactivate();

    if (requireActivity() instanceof SearchActivity)
      Utils.navigateToParent(requireActivity());
  }

  void showAllResultsOnMap()
  {
    SearchEngine.INSTANCE.updateViewportWithLastResults();

    // The previous search should be cancelled before the new one is started, since previous search
    // results are no longer needed.
    SearchEngine.INSTANCE.cancel();

    final String query = getQuery();
    if (Config.isSearchHistoryEnabled())
      SearchRecents.add(query, requireContext());
    mLastQueryTimestamp = System.nanoTime();

    SearchEngine.INSTANCE.searchInteractive(
        query, isCategory(),
        !TextUtils.isEmpty(mInitialLocale) ? mInitialLocale : Language.getKeyboardLocale(requireContext()),
        mLastQueryTimestamp, false /* isMapAndTable */, getOpeningHoursFilter());

    SearchEngine.INSTANCE.setQuery(query, isCategory());
    Utils.navigateToParent(requireActivity());
  }

  private void onSearchEnd()
  {
    if (mSearchRunning && isAdded())
      updateSearchView();
  }

  private void updateSearchView()
  {
    mSearchRunning = false;
    mToolbarController.showProgress(false);
    updateFrames();
    updateResultsPlaceholder();
  }

  private void stopSearch()
  {
    SearchEngine.INSTANCE.cancel();
    updateSearchView();
  }

  private boolean isTabletSearch()
  {
    // TODO @yunitsky Implement more elegant solution.
    return requireActivity() instanceof MwmActivity;
  }

  private void runSearch()
  {
    // The previous search should be cancelled before the new one is started, since previous search
    // results are no longer needed.
    SearchEngine.INSTANCE.cancel();

    mLastQueryTimestamp = System.nanoTime();
    mOnlinePresenter.onQueryChanged(getQuery(), isCategory(), Language.getKeyboardLocale(requireContext()),
                                    getMapCenter(),
                                    mLastPosition.valid ? new double[] {mLastPosition.lat, mLastPosition.lon} : null);
    if (isTabletSearch())
    {
      SearchEngine.INSTANCE.searchInteractive(requireContext(), getQuery(), isCategory(), mLastQueryTimestamp,
                                              true /* isMapAndTable */, getOpeningHoursFilter());
    }
    else
    {
      if (!SearchEngine.INSTANCE.search(requireContext(), getQuery(), isCategory(), mLastQueryTimestamp,
                                        mLastPosition.valid, mLastPosition.lat, mLastPosition.lon,
                                        getOpeningHoursFilter()))
      {
        // No new offline search (e.g. the same query again): decide with the results already shown.
        mOnlinePresenter.onOfflineResults(toOfflinePlaces(mSearchAdapter.getResults()));
        mOnlinePresenter.onOfflineSearchFinished();
        return;
      }
    }

    // The hint is shown again at the end of the new search.
    setHiddenWithoutOpeningHours(0);
    mSearchRunning = true;
    mToolbarController.showProgress(true);

    updateFrames();
  }

  @Override
  public void onResultsUpdate(@NonNull SearchResult[] results, long timestamp)
  {
    if (!isAdded() || !mToolbarController.hasQuery())
      return;

    refreshSearchResults(results);
    if (timestamp == mLastQueryTimestamp)
      mOnlinePresenter.onOfflineResults(toOfflinePlaces(results));
  }

  @Override
  public void onResultsEnd(long timestamp)
  {
    onSearchEnd();
    if (isAdded() && timestamp == mLastQueryTimestamp && mToolbarController.hasQuery())
      mOnlinePresenter.onOfflineSearchFinished();
  }

  @Override
  public void onResultsEnd(long timestamp, int hiddenWithoutOpeningHours)
  {
    onResultsEnd(timestamp);
    if (isAdded() && timestamp == mLastQueryTimestamp && mToolbarController.hasQuery())
      setHiddenWithoutOpeningHours(hiddenWithoutOpeningHours);
  }

  @Override
  public void onSearchCategorySelected(@Nullable String category)
  {
    mToolbarController.setQuery(category, true);
  }

  private void refreshSearchResults(@NonNull SearchResult[] results)
  {
    mSearchRunning = true;
    updateFrames();
    mSearchAdapter.refreshData(results);
    mToolbarController.showProgress(true);
  }

  @Override
  public boolean onBackPressed()
  {
    if (mToolbarController.hasQuery())
    {
      mToolbarController.clear();
      return true;
    }

    boolean isSearchActivity = requireActivity() instanceof SearchActivity;
    mToolbarController.deactivate();
    if (RoutingController.get().isWaitingPoiPick())
    {
      RoutingController.get().onPoiSelected(null);
      if (isSearchActivity)
        closeSearch();
      return !isSearchActivity;
    }

    if (isSearchActivity)
      closeSearch();
    return isSearchActivity;
  }

  private void initOnlineSearch()
  {
    mOnlinePresenter = OnlineSearchServices.get(requireContext()).createPresenter();
    mOnlineAdapter = new OnlineSearchAdapter(new OnlineSearchAdapter.Listener() {
      @Override
      public void onEnableOnlineSearch()
      {
        OnlineSearchConsentDialog.show(requireContext(), granted -> {
          if (isAdded())
            mOnlinePresenter.onConsentChanged();
        });
      }

      @Override
      public void onRetry()
      {
        mOnlinePresenter.retry();
      }

      @Override
      public void onShowResult(@NonNull OnlineSearchItem item)
      {
        showOnlineResultOnMap(item.getResult());
      }

      @Override
      public void onDownloadRegion(@NonNull OnlineSearchItem item)
      {
        downloadRegion(item);
      }
    });
    mOnlinePresenter.setListener(state -> {
      if (!isAdded())
        return;
      mOnlineAdapter.setState(state);
      updateResultsPlaceholder();
    });
  }

  /** Map center {lat, lon} for the online search, null if the map is not ready. */
  @Nullable
  private static double[] getMapCenter()
  {
    try
    {
      final double[] center = Framework.nativeGetScreenRectCenter();
      return center != null && center.length >= 2 ? center : null;
    }
    catch (RuntimeException | UnsatisfiedLinkError e)
    {
      return null;
    }
  }

  @NonNull
  private List<OfflinePlace> toOfflinePlaces(@Nullable SearchResult[] results)
  {
    final String query = getQuery();
    final List<OfflinePlace> places = new ArrayList<>();
    if (results == null)
      return places;
    for (SearchResult result : results)
    {
      if (result.type != SearchResult.TYPE_RESULT)
        continue;
      final String name = !TextUtils.isEmpty(result.name)
                            ? result.name
                            : (result.description != null ? result.description.localizedFeatureType : "");
      places.add(new OfflinePlace(name != null ? name : "", result.lat, result.lon, null,
                                  OfflinePlace.match(query, result.highlightRanges, result.descHighlightRanges)));
    }
    return places;
  }

  /** Shows an online result as a marked point with a place page (like a map link), or picks it as a route point. */
  void showOnlineResultOnMap(@NonNull OnlineSearchResult result)
  {
    final String query = getQuery();
    if (Config.isSearchHistoryEnabled())
      SearchRecents.add(query, requireContext());
    // Also removes earlier marked points.
    SearchEngine.INSTANCE.cancel();

    if (RoutingController.get().isWaitingPoiPick())
    {
      final MapObject point = MapObject.createMapObject(FeatureId.EMPTY, MapObject.API_POINT, result.getTitle(),
                                                        result.getAddress(), result.getLat(), result.getLon());
      RoutingController.get().onPoiSelected(point);
    }
    else if (Framework.nativeParseAndSetApiUrl(OnlineSearchMapLink.toApiUrl(result)) == RequestType.MAP)
    {
      // Without a downloaded map at this place the map shows its download button.
      Map.executeMapApiRequest();
    }

    mToolbarController.deactivate();

    if (requireActivity() instanceof SearchActivity)
      Utils.navigateToParent(requireActivity());
  }

  private void downloadRegion(@NonNull OnlineSearchItem item)
  {
    final RegionInfo region = item.getRegion();
    if (region == null || region.isDownloaded())
      return;
    MapManagerHelper.warn3gAndDownload(requireActivity(), region.getId(), () -> {
      if (isAdded())
        Toast
            .makeText(requireContext(), getString(R.string.online_search_download_started, region.getName()),
                      Toast.LENGTH_SHORT)
            .show();
    });
  }

  private void closeSearch()
  {
    final Activity activity = requireActivity();
    activity.finish();
  }

  public void setRecyclerScrollListener(RecyclerView recycler)
  {
    recycler.addOnScrollListener(mRecyclerListener);
    mAttachedRecyclers.add(recycler);
  }

  @NonNull
  public SearchToolbarController requireController()
  {
    return mToolbarController;
  }

  private static class BadStorageCommand extends HiddenCommand.BaseHiddenCommand
  {
    @NonNull
    Context mContext;

    BadStorageCommand(@NonNull String command, @NonNull Context context)
    {
      super(command);
      mContext = context;
    }

    @Override
    void executeInternal()
    {
      SharedPropertiesUtils.setShouldShowEmulateBadStorageSetting(true);
    }
  }

  /** Requests public transport connections Berlin Hbf -> Alexanderplatz from Transitous (debug only). */
  private static class TransitPlanCommand extends HiddenCommand.BaseHiddenCommand
  {
    @NonNull
    private final Context mContext;

    TransitPlanCommand(@NonNull String command, @NonNull Context context)
    {
      super(command);
      mContext = context;
    }

    @Override
    void executeInternal()
    {
      TransitDebug.planBerlinHbfToAlexanderplatz(mContext);
    }
  }

  private static class JavaCrashCommand extends HiddenCommand.BaseHiddenCommand
  {
    JavaCrashCommand(@NonNull String command)
    {
      super(command);
    }

    @Override
    void executeInternal()
    {
      throw new RuntimeException("Diagnostic java crash!");
    }
  }

  private static class NativeCrashCommand extends HiddenCommand.BaseHiddenCommand
  {
    NativeCrashCommand(@NonNull String command)
    {
      super(command);
    }

    @Override
    void executeInternal()
    {
      Framework.nativeMakeCrash();
    }
  }

  private static class PushTokenCommand extends HiddenCommand.BaseHiddenCommand
  {
    PushTokenCommand(@NonNull String command)
    {
      super(command);
    }

    @Override
    void executeInternal()
    {}
  }
}
