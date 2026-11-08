package app.organicmaps.search.route;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

/**
 * The open search along the route of the map screen, if any. It survives a rotation, and the place page uses it to
 * show "Add to route" for the results of the search.
 */
public class RouteSearchViewModel extends ViewModel implements RouteSearchPresenter.ChangeListener
{
  @Nullable
  private RouteSearchPresenter mPresenter;
  // Changes whenever the search or its results change.
  private final MutableLiveData<Integer> mChanges = new MutableLiveData<>(0);

  /** @return the open search, or null if there is none. */
  @Nullable
  public RouteSearchPresenter getPresenter()
  {
    return mPresenter != null && !mPresenter.isClosed() ? mPresenter : null;
  }

  public void setPresenter(@NonNull RouteSearchPresenter presenter)
  {
    if (mPresenter != null)
      mPresenter.removeListener(this);
    mPresenter = presenter;
    presenter.addListener(this);
    onRouteSearchChanged();
  }

  @NonNull
  public LiveData<Integer> getChanges()
  {
    return mChanges;
  }

  @Override
  public void onRouteSearchChanged()
  {
    final Integer value = mChanges.getValue();
    mChanges.setValue(value == null ? 1 : value + 1);
  }

  @Override
  protected void onCleared()
  {
    // The map screen is gone for good: no results left on the map.
    if (mPresenter != null)
      mPresenter.close();
    mPresenter = null;
  }
}
