package app.organicmaps.settings;

import android.app.Activity;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import app.organicmaps.R;
import app.organicmaps.base.BaseToolbarActivity;

/** Opens the public transport settings directly, e.g. from the route planner. */
public class TransitSettingsActivity extends BaseToolbarActivity
{
  public static void start(@NonNull Activity activity)
  {
    activity.startActivity(new Intent(activity, TransitSettingsActivity.class));
  }

  @Override
  protected int getToolbarTitle()
  {
    return R.string.transit_settings_title;
  }

  @Override
  protected Class<? extends Fragment> getFragmentClass()
  {
    return TransitSettingsFragment.class;
  }
}
