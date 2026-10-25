package app.organicmaps.sdk.transit.provider;

import androidx.annotation.NonNull;

/** A value together with the provider that delivered it (for attribution and paging). */
public final class ProviderResult<T>
{
  @NonNull
  private final T mValue;
  @NonNull
  private final TransitProvider mProvider;

  public ProviderResult(@NonNull T value, @NonNull TransitProvider provider)
  {
    mValue = value;
    mProvider = provider;
  }

  @NonNull
  public T getValue()
  {
    return mValue;
  }

  @NonNull
  public TransitProvider getProvider()
  {
    return mProvider;
  }
}
