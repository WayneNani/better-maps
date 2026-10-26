package app.organicmaps.transit.ui;

import android.app.Activity;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import app.organicmaps.R;
import app.organicmaps.base.BaseToolbarActivity;

/** The connections the user saved; opened from the route planner and the public transport settings. */
public class SavedConnectionsActivity extends BaseToolbarActivity
{
  public static void start(@NonNull Activity activity)
  {
    activity.startActivity(new Intent(activity, SavedConnectionsActivity.class));
  }

  @Override
  protected int getToolbarTitle()
  {
    return R.string.transit_saved_title;
  }

  @Override
  protected Class<? extends Fragment> getFragmentClass()
  {
    return SavedConnectionsFragment.class;
  }
}
