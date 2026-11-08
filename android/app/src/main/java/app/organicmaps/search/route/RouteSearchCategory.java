package app.organicmaps.search.route;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import app.organicmaps.R;
import app.organicmaps.sdk.Router;
import java.util.List;

/**
 * Quick categories of the search along the route. The text of a category is searched as a category name, so it must
 * be a full category name in data/categories.txt (in English and in the language of the translation).
 */
public enum RouteSearchCategory
{
  FUEL(R.string.category_fuel, R.drawable.ic_category_fuel),
  CHARGING(R.string.category_charging_station, R.drawable.ic_category_charging),
  RESTAURANT(R.string.category_restaurant, R.drawable.ic_category_restaurant),
  CAFE(R.string.category_cafe, R.drawable.ic_category_cafe),
  BAKERY(R.string.category_bakery, R.drawable.ic_category_bakery),
  SUPERMARKET(R.string.category_supermarket, R.drawable.ic_category_food),
  TOILET(R.string.category_toilet, R.drawable.ic_category_toilet),
  DRINKING_WATER(R.string.category_water, R.drawable.ic_category_water),
  PARKING(R.string.category_parking, R.drawable.ic_category_parking),
  RECYCLING_CONTAINER(R.string.category_recycling_container, R.drawable.ic_category_recycling);

  @StringRes
  public final int mNameResId;
  @DrawableRes
  public final int mIconResId;

  RouteSearchCategory(@StringRes int nameResId, @DrawableRes int iconResId)
  {
    mNameResId = nameResId;
    mIconResId = iconResId;
  }

  /** The categories which make sense for the router, in order of their use on the way. */
  @NonNull
  public static List<RouteSearchCategory> forRouter(@NonNull Router router)
  {
    return switch (router)
    {
      case Vehicle ->
        List.of(FUEL, CHARGING, RESTAURANT, CAFE, BAKERY, SUPERMARKET, TOILET, PARKING, RECYCLING_CONTAINER);
      case Bicycle, Pedestrian, Transit ->
        List.of(CAFE, BAKERY, RESTAURANT, SUPERMARKET, TOILET, DRINKING_WATER, RECYCLING_CONTAINER);
      case Ruler -> List.of();
    };
  }
}
