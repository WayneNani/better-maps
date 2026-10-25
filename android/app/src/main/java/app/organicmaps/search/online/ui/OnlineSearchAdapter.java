package app.organicmaps.search.online.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.recyclerview.widget.RecyclerView;
import app.organicmaps.R;
import app.organicmaps.sdk.search.online.OnlineSearchResult;
import app.organicmaps.search.online.OnlineSearchItem;
import app.organicmaps.search.online.OnlineSearchRows;
import app.organicmaps.search.online.OnlineSearchState;
import app.organicmaps.search.online.RegionInfo;
import app.organicmaps.util.UiUtils;
import com.google.android.material.button.MaterialButton;
import java.util.Collections;
import java.util.List;

/**
 * The online section below the offline results: a title with the provider and the OSM attribution, then the offer to
 * search online, a progress row, the results, or a message. Used together with the offline results in a
 * {@code ConcatAdapter}.
 */
public class OnlineSearchAdapter extends RecyclerView.Adapter<OnlineSearchAdapter.RowHolder>
{
  public interface Listener
  {
    /** The user tapped "Search online" (consent not given yet). */
    void onEnableOnlineSearch();

    void onRetry();

    void onShowResult(@NonNull OnlineSearchItem item);

    void onDownloadRegion(@NonNull OnlineSearchItem item);
  }

  @NonNull
  private final Listener mListener;
  @NonNull
  private OnlineSearchState mState = OnlineSearchState.HIDDEN;
  @NonNull
  private List<OnlineSearchRows.Row> mRows = Collections.emptyList();

  public OnlineSearchAdapter(@NonNull Listener listener)
  {
    mListener = listener;
  }

  @SuppressLint("NotifyDataSetChanged")
  public void setState(@NonNull OnlineSearchState state)
  {
    mState = state;
    mRows = OnlineSearchRows.build(state);
    notifyDataSetChanged();
  }

  @NonNull
  public OnlineSearchState getState()
  {
    return mState;
  }

  @Override
  public int getItemCount()
  {
    return mRows.size();
  }

  @Override
  public int getItemViewType(int position)
  {
    final OnlineSearchRows.Type type = mRows.get(position).type;
    return switch (type)
    {
      case HEADER -> R.layout.item_online_search_header;
      case RESULT -> R.layout.item_online_search_result;
      default -> R.layout.item_online_search_message;
    };
  }

  @NonNull
  @Override
  public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
  {
    final View view = LayoutInflater.from(parent.getContext()).inflate(viewType, parent, false);
    if (viewType == R.layout.item_online_search_header)
      return new HeaderHolder(view);
    if (viewType == R.layout.item_online_search_result)
      return new ResultHolder(view);
    return new MessageHolder(view);
  }

  @Override
  public void onBindViewHolder(@NonNull RowHolder holder, int position)
  {
    holder.bind(mRows.get(position), mState);
  }

  abstract static class RowHolder extends RecyclerView.ViewHolder
  {
    RowHolder(@NonNull View itemView)
    {
      super(itemView);
    }

    abstract void bind(@NonNull OnlineSearchRows.Row row, @NonNull OnlineSearchState state);
  }

  private static final class HeaderHolder extends RowHolder
  {
    @NonNull
    private final TextView mTitle;

    HeaderHolder(@NonNull View view)
    {
      super(view);
      mTitle = view.findViewById(R.id.online_search_title);
    }

    @Override
    void bind(@NonNull OnlineSearchRows.Row row, @NonNull OnlineSearchState state)
    {
      mTitle.setText(itemView.getContext().getString(R.string.online_search_section_title, state.getProviderName()));
    }
  }

  private final class MessageHolder extends RowHolder
  {
    @NonNull
    private final View mProgress;
    @NonNull
    private final TextView mMessage;
    @NonNull
    private final MaterialButton mAction;

    MessageHolder(@NonNull View view)
    {
      super(view);
      mProgress = view.findViewById(R.id.online_search_progress);
      mMessage = view.findViewById(R.id.online_search_message);
      mAction = view.findViewById(R.id.online_search_action);
    }

    @Override
    void bind(@NonNull OnlineSearchRows.Row row, @NonNull OnlineSearchState state)
    {
      UiUtils.showIf(row.type == OnlineSearchRows.Type.LOADING, mProgress);
      switch (row.type)
      {
      case OFFER ->
      {
        mMessage.setText(R.string.online_search_offer);
        showAction(R.string.online_search_offer_button, v -> mListener.onEnableOnlineSearch());
      }
      case LOADING ->
      {
        mMessage.setText(R.string.online_search_loading);
        UiUtils.hide(mAction);
      }
      case EMPTY ->
      {
        mMessage.setText(R.string.online_search_empty);
        UiUtils.hide(mAction);
      }
      case ERROR ->
      {
        final OnlineSearchState.Error error = state.getError();
        mMessage.setText(errorText(error));
        if (error == OnlineSearchState.Error.NETWORK || error == OnlineSearchState.Error.SERVER)
          showAction(R.string.online_search_retry, v -> mListener.onRetry());
        else
          UiUtils.hide(mAction);
      }
      default -> UiUtils.hide(mAction);
      }
    }

    private void showAction(@StringRes int text, @NonNull View.OnClickListener listener)
    {
      mAction.setText(text);
      mAction.setOnClickListener(listener);
      UiUtils.show(mAction);
    }
  }

  @StringRes
  static int errorText(@Nullable OnlineSearchState.Error error)
  {
    if (error == null)
      return R.string.online_search_error_unknown;
    return switch (error)
    {
      case NETWORK -> R.string.online_search_error_network;
      case SERVER -> R.string.online_search_error_server;
      case REQUEST -> R.string.online_search_error_request;
      case DISABLED -> R.string.online_search_error_disabled;
      case UNKNOWN -> R.string.online_search_error_unknown;
    };
  }

  private final class ResultHolder extends RowHolder
  {
    @NonNull
    private final TextView mTitle;
    @NonNull
    private final TextView mDescription;
    @NonNull
    private final TextView mRegion;
    @NonNull
    private final MaterialButton mDownload;
    @Nullable
    private OnlineSearchItem mItem;

    ResultHolder(@NonNull View view)
    {
      super(view);
      mTitle = view.findViewById(R.id.title);
      mDescription = view.findViewById(R.id.description);
      mRegion = view.findViewById(R.id.region);
      mDownload = view.findViewById(R.id.online_search_download);
      view.setOnClickListener(v -> {
        if (mItem != null)
          mListener.onShowResult(mItem);
      });
      mDownload.setOnClickListener(v -> {
        if (mItem != null)
          mListener.onDownloadRegion(mItem);
      });
    }

    @Override
    void bind(@NonNull OnlineSearchRows.Row row, @NonNull OnlineSearchState state)
    {
      mItem = row.item;
      if (mItem == null)
        return;
      final Context context = itemView.getContext();
      final OnlineSearchResult result = mItem.getResult();
      mTitle.setText(result.getTitle());
      UiUtils.setTextAndHideIfEmpty(mDescription, typeLabel(context, result));
      UiUtils.setTextAndHideIfEmpty(mRegion, result.getAddress());
      final RegionInfo region = mItem.getRegion();
      if (region != null && mItem.canDownloadRegion())
      {
        mDownload.setText(region.getSizeBytes() > 0
                              ? context.getString(R.string.online_search_download, region.getName(),
                                                  Formatter.formatShortFileSize(context, region.getSizeBytes()))
                              : context.getString(R.string.online_search_download_no_size, region.getName()));
        UiUtils.show(mDownload);
      }
      else
        UiUtils.hide(mDownload);
    }
  }

  /** Localized kind of place from the CoMaps type strings, e.g. "Pedestrian Street"; a readable fallback otherwise. */
  @NonNull
  @SuppressLint("DiscouragedApi")
  static String typeLabel(@NonNull Context context, @NonNull OnlineSearchResult result)
  {
    final Resources res = context.getResources();
    for (String key : OnlineSearchRows.typeStringKeys(result))
    {
      final int id = res.getIdentifier(key, "string", context.getPackageName());
      if (id != 0)
        return res.getString(id);
    }
    return OnlineSearchRows.humanize(result.getOsmValue());
  }
}
