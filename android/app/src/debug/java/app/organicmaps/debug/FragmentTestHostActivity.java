package app.organicmaps.debug;

import android.os.Bundle;
import android.widget.FrameLayout;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Debug builds only: an empty AppCompat screen for instrumented tests of fragments that need an AppCompatActivity
 * (e.g. for their toolbar), which FragmentScenario's host is not. Tests add their fragment to {@link #CONTAINER_ID}.
 */
public class FragmentTestHostActivity extends AppCompatActivity
{
  public static final int CONTAINER_ID = android.R.id.content;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    if (findViewById(CONTAINER_ID) == null)
      setContentView(new FrameLayout(this));
  }
}
