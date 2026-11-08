package app.organicmaps.search.route;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import app.organicmaps.base.BaseMwmFragmentActivity;
import app.organicmaps.sdk.Router;

/** Search along the route, see {@link RouteSearchFragment}. */
public class RouteSearchActivity extends BaseMwmFragmentActivity
{
  public static void start(@NonNull Activity activity, @NonNull Router router)
  {
    start(activity, router, null, false);
  }

  /** @param query a query to search right away, e.g. a category name. */
  public static void start(@NonNull Activity activity, @NonNull Router router, @Nullable String query,
                           boolean isCategory)
  {
    final Bundle args = new Bundle();
    args.putInt(RouteSearchFragment.ARG_ROUTER, router.ordinal());
    args.putString(RouteSearchFragment.ARG_QUERY, query);
    args.putBoolean(RouteSearchFragment.ARG_IS_CATEGORY, isCategory);
    activity.startActivity(new Intent(activity, RouteSearchActivity.class).putExtras(args));
  }

  @Override
  protected Class<? extends Fragment> getFragmentClass()
  {
    return RouteSearchFragment.class;
  }
}
