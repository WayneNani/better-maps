package app.organicmaps.car.screens.search;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.GridItem;
import androidx.car.app.model.GridTemplate;
import androidx.car.app.model.Header;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.MapWithContentTemplate;
import androidx.core.graphics.drawable.IconCompat;
import app.organicmaps.R;
import app.organicmaps.car.renderer.Renderer;
import app.organicmaps.car.screens.base.BaseMapScreen;
import app.organicmaps.car.util.UiHelpers;
import app.organicmaps.sdk.Router;
import app.organicmaps.search.route.RouteSearchCategory;
import java.util.List;

/** better-maps: categories of the search along the route during the navigation. */
public class RouteSearchCategoriesScreen extends BaseMapScreen
{
  private final int MAX_CATEGORIES_SIZE;

  public RouteSearchCategoriesScreen(@NonNull CarContext carContext, @NonNull Renderer surfaceRenderer)
  {
    super(carContext, surfaceRenderer);
    final ConstraintManager constraintManager = getCarContext().getCarService(ConstraintManager.class);
    MAX_CATEGORIES_SIZE = constraintManager.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID);
  }

  @NonNull
  @Override
  protected Template onGetTemplateImpl()
  {
    final MapWithContentTemplate.Builder builder = new MapWithContentTemplate.Builder();
    builder.setMapController(UiHelpers.createMapController(getCarContext(), getSurfaceRenderer()));
    builder.setContentTemplate(createCategoriesTemplate());
    return builder.build();
  }

  @NonNull
  private GridTemplate createCategoriesTemplate()
  {
    final ItemList.Builder builder = new ItemList.Builder();
    final List<RouteSearchCategory> categories = RouteSearchCategory.forRouter(Router.Vehicle);
    for (int i = 0; i < Math.min(categories.size(), MAX_CATEGORIES_SIZE); ++i)
    {
      final RouteSearchCategory category = categories.get(i);
      final String name = getCarContext().getString(category.mNameResId);
      builder.addItem(
          new GridItem.Builder()
              .setTitle(name)
              .setImage(
                  new CarIcon.Builder(IconCompat.createWithResource(getCarContext(), category.mIconResId)).build())
              .setOnClickListener(()
                                      -> getScreenManager().push(
                                          new RouteSearchResultsScreen(getCarContext(), getSurfaceRenderer(), name)))
              .build());
    }
    final Header header = new Header.Builder()
                              .setStartHeaderAction(Action.BACK)
                              .setTitle(getCarContext().getString(R.string.route_search))
                              .build();
    return new GridTemplate.Builder().setHeader(header).setSingleList(builder.build()).build();
  }
}
