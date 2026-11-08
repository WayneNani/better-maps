package app.organicmaps.car.screens.search;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.Header;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.MapWithContentTemplate;
import androidx.lifecycle.LifecycleOwner;
import app.organicmaps.R;
import app.organicmaps.car.renderer.Renderer;
import app.organicmaps.car.screens.NavigationScreen;
import app.organicmaps.car.screens.base.BaseMapScreen;
import app.organicmaps.car.util.UiHelpers;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.search.SearchEngine;
import app.organicmaps.sdk.search.SearchListener;
import app.organicmaps.sdk.search.SearchResult;
import app.organicmaps.sdk.util.Language;
import app.organicmaps.search.route.AndroidRouteSearchTexts;
import app.organicmaps.search.route.NativeRouteSearchEngine;
import app.organicmaps.search.route.RouteSearchCategory;
import app.organicmaps.search.route.RouteSearchFormatter;
import app.organicmaps.search.route.RouteSearchPresenter;
import app.organicmaps.search.route.RouteSearchSettings;
import app.organicmaps.search.route.RouteSearchTexts;
import app.organicmaps.search.route.RouteStops;
import java.util.List;

/**
 * better-maps: results of a category along the route during the navigation, the next ones first. A tap adds the
 * place as a stop and goes back to the navigation, which goes on with the new route.
 */
public class RouteSearchResultsScreen extends BaseMapScreen implements RouteSearchPresenter.View, SearchListener
{
  private final int MAX_RESULTS_SIZE;

  @NonNull
  private final String mCategory;
  @NonNull
  private final RouteSearchPresenter mPresenter;
  @NonNull
  private final RouteSearchTexts mTexts;
  @NonNull
  private List<SearchResult> mResults = List.of();
  private boolean mLoading = true;
  private boolean mNoRoute;

  public RouteSearchResultsScreen(@NonNull CarContext carContext, @NonNull Renderer surfaceRenderer,
                                  @NonNull String category)
  {
    super(carContext, surfaceRenderer);
    final ConstraintManager constraintManager = getCarContext().getCarService(ConstraintManager.class);
    MAX_RESULTS_SIZE = constraintManager.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST);
    mCategory = category;
    mTexts = new AndroidRouteSearchTexts(carContext);
    mPresenter = new RouteSearchPresenter(
        Router.Vehicle, RouteSearchSettings.from(carContext),
        new NativeRouteSearchEngine(Language.getKeyboardLocale(carContext), false /* withMap */), new RouteStops(),
        System::nanoTime);
  }

  @NonNull
  @Override
  protected Template onGetTemplateImpl()
  {
    final MapWithContentTemplate.Builder builder = new MapWithContentTemplate.Builder();
    builder.setMapController(UiHelpers.createMapController(getCarContext(), getSurfaceRenderer()));
    builder.setContentTemplate(createResultsTemplate());
    return builder.build();
  }

  @NonNull
  private ListTemplate createResultsTemplate()
  {
    final ListTemplate.Builder builder = new ListTemplate.Builder();
    builder.setHeader(new Header.Builder().setStartHeaderAction(Action.BACK).setTitle(mCategory).build());
    if (mLoading)
      return builder.setLoading(true).build();

    final ItemList.Builder items = new ItemList.Builder();
    items.setNoItemsMessage(
        getCarContext().getString(mNoRoute ? R.string.route_search_no_route : R.string.route_search_not_found));
    for (int i = 0; i < Math.min(mResults.size(), MAX_RESULTS_SIZE); ++i)
      items.addItem(createRow(mResults.get(i)));
    return builder.setSingleList(items.build()).build();
  }

  @NonNull
  private Row createRow(@NonNull SearchResult result)
  {
    final Row.Builder builder = new Row.Builder();
    builder.setTitle(result.getTitle(getCarContext()));
    if (result.routePosition != null)
    {
      // "in 12 km · Detour ~2 min" with the distance as a distance span, as the car host requires.
      final String detour = RouteSearchFormatter.formatPosition(result.routePosition, mTexts);
      final int separator = detour.indexOf(RouteSearchFormatter.SEPARATOR);
      builder.addText(SearchUiHelpers.concatenateStrings(
          SearchUiHelpers.getRouteDistanceText(result),
          separator >= 0 ? detour.substring(separator + RouteSearchFormatter.SEPARATOR.length()) : ""));
    }
    final String open = RouteSearchFormatter.formatOpeningHours(result, mTexts);
    builder.addText(SearchUiHelpers.concatenateStrings(
        result.description != null ? result.description.localizedFeatureType : "", open != null ? open : ""));
    builder.setOnClickListener(() -> mPresenter.onAddStopClicked(result));
    return builder.build();
  }

  @Override
  public void onStart(@NonNull LifecycleOwner owner)
  {
    super.onStart(owner);
    SearchEngine.INSTANCE.addListener(this);
    mPresenter.attach(this);
    if (mPresenter.getQuery().isEmpty())
      mPresenter.onCategorySelected(mCategory);
  }

  @Override
  public void onStop(@NonNull LifecycleOwner owner)
  {
    super.onStop(owner);
    SearchEngine.INSTANCE.removeListener(this);
    mPresenter.detach();
  }

  // RouteSearchPresenter.View

  @Override
  public void showCategories(@NonNull List<RouteSearchCategory> categories)
  {}

  @Override
  public void showHalfWidth(int halfWidthMeters, @NonNull int[] options)
  {}

  @Override
  public void showResults(@NonNull List<SearchResult> results)
  {
    mResults = results;
  }

  @Override
  public void showProgress(boolean show)
  {
    mLoading = show;
    invalidate();
  }

  @Override
  public void showNoRoute()
  {
    mNoRoute = true;
    mLoading = false;
    invalidate();
  }

  @Override
  public void showHiddenWithoutOpeningHours(int count)
  {}

  @Override
  public void showDisplay(@NonNull RouteSearchPresenter.Display display)
  {}

  @Override
  public void prepareMap()
  {}

  @Override
  public void showStopAdded(@NonNull SearchResult result)
  {
    CarToast.makeText(getCarContext(), R.string.route_search_stop_added, CarToast.LENGTH_LONG).show();
    // Back to the navigation, which goes on with the new route.
    mPresenter.close();
  }

  @Override
  public void close()
  {
    getScreenManager().popTo(NavigationScreen.MARKER);
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
}
