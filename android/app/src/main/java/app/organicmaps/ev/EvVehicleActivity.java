package app.organicmaps.ev;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import app.organicmaps.R;
import app.organicmaps.base.BaseToolbarActivity;

/** Opens "My vehicle" directly, from the charging planner button of the route planner. */
public class EvVehicleActivity extends BaseToolbarActivity
{
  /** The setup for the charging planner of the current route: a hint and a button that enables the planner. */
  @NonNull
  public static Intent fromRoute(@NonNull Context context)
  {
    return new Intent(context, EvVehicleActivity.class).putExtra(EvVehicleFragment.ARG_FROM_ROUTE, true);
  }

  @Override
  protected int getToolbarTitle()
  {
    return R.string.ev_my_vehicle;
  }

  @Override
  protected Class<? extends Fragment> getFragmentClass()
  {
    return EvVehicleFragment.class;
  }
}
