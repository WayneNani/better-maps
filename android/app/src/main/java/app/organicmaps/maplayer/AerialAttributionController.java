package app.organicmaps.maplayer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.R;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Map;
import app.organicmaps.sdk.util.log.Logger;
import app.organicmaps.settings.AerialImagerySettings;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows the attribution of the aerial imagery layer (better-maps) on the map while the layer is on: a small text with
 * the sources of the visible area, tappable for the full list with licenses. The sources come from the imagery server
 * (/v1/attribution), asked again when the view changed noticeably (checked once a second). The token is never logged.
 */
public final class AerialAttributionController
{
  private static final String TAG = AerialAttributionController.class.getSimpleName();
  private static final long POLL_MS = 1000;
  private static final int TIMEOUT_MS = 10000;
  private static final int MAX_BYTES = 64 * 1024;

  @NonNull
  private final TextView mView;
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
  private final Runnable mPoll = this::poll;

  @NonNull
  private List<AerialAttribution.Source> mSources = Collections.emptyList();
  @Nullable
  private String mLastKey;
  private boolean mFailed;
  private boolean mResumed;

  public AerialAttributionController(@NonNull TextView view)
  {
    mView = view;
    mView.setOnClickListener(v -> showDetails(v.getContext()));
  }

  public void onResume()
  {
    mResumed = true;
    mHandler.removeCallbacks(mPoll);
    poll();
  }

  public void onPause()
  {
    mResumed = false;
    mHandler.removeCallbacks(mPoll);
  }

  public void onDestroy()
  {
    onPause();
    mExecutor.shutdownNow();
  }

  private void poll()
  {
    if (!mResumed)
      return;
    mHandler.postDelayed(mPoll, POLL_MS);
    final AerialImagerySettings settings = AerialImagerySettings.get(mView.getContext());
    if (!settings.isActive() || !Map.isEngineCreated())
    {
      mView.setVisibility(View.GONE);
      mLastKey = null;
      mSources = Collections.emptyList();
      return;
    }
    final double[] rect = Framework.nativeGetViewportLatLonRect();
    final int zoom = Framework.nativeGetDrawScale();
    final String view = AerialAttribution.viewKey(rect, zoom);
    final String baseUrl = AerialImagerySettings.baseUrl(settings.getUrl());
    if (view != null)
    {
      final String key = baseUrl + '|' + view;
      if (!key.equals(mLastKey))
      {
        mLastKey = key;
        fetch(key, AerialAttribution.requestUrl(baseUrl, rect, zoom), settings.getToken());
      }
    }
    updateView();
  }

  private void fetch(@NonNull String key, @NonNull String url, @NonNull String token)
  {
    if (mExecutor.isShutdown())
      return;
    mExecutor.execute(() -> {
      final String body = get(url, token);
      final List<AerialAttribution.Source> sources = AerialAttribution.parse(body);
      mHandler.post(() -> {
        if (!key.equals(mLastKey))
          return;
        mFailed = body == null;
        if (body != null)
          mSources = sources;
        updateView();
      });
    });
  }

  @Nullable
  private static String get(@NonNull String url, @NonNull String token)
  {
    HttpURLConnection connection = null;
    try
    {
      connection = (HttpURLConnection) new URL(url).openConnection();
      connection.setConnectTimeout(TIMEOUT_MS);
      connection.setReadTimeout(TIMEOUT_MS);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty("Authorization", "Bearer " + token);
      connection.setRequestProperty("Accept", "application/json");
      final int code = connection.getResponseCode();
      if (code != HttpURLConnection.HTTP_OK)
      {
        Logger.w(TAG, "Attribution request failed: HTTP " + code);
        return null;
      }
      try (InputStream in = connection.getInputStream())
      {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() <= MAX_BYTES)
          out.write(buf, 0, n);
        return out.size() > MAX_BYTES ? null : out.toString(StandardCharsets.UTF_8.name());
      }
    }
    catch (IOException | RuntimeException e)
    {
      // Without the URL: it holds the viewed area.
      Logger.w(TAG, "Attribution request failed: " + e.getClass().getSimpleName());
      return null;
    }
    finally
    {
      if (connection != null)
        connection.disconnect();
    }
  }

  private void updateView()
  {
    final String text = AerialAttribution.shortText(mSources);
    if (!text.isEmpty())
      mView.setText(text);
    else if (mFailed)
      mView.setText(R.string.aerial_imagery_attribution_fallback);
    else
      mView.setText("");
    mView.setVisibility(mView.getText().length() > 0 ? View.VISIBLE : View.GONE);
  }

  private void showDetails(@NonNull Context context)
  {
    if (mSources.isEmpty())
      return;
    final StringBuilder sb = new StringBuilder();
    for (AerialAttribution.Source s : mSources)
    {
      if (sb.length() > 0)
        sb.append("\n\n");
      if (!s.name.isEmpty())
        sb.append(s.name).append('\n');
      sb.append(s.attribution);
      if (!s.license.isEmpty())
        sb.append('\n').append(context.getString(R.string.aerial_imagery_license, s.license));
      if (!s.licenseUrl.isEmpty())
        sb.append('\n').append(s.licenseUrl);
    }
    final TextView message = new TextView(context);
    final int padding = context.getResources().getDimensionPixelSize(R.dimen.margin_base);
    message.setPadding(padding * 3 / 2, padding, padding * 3 / 2, 0);
    message.setTextIsSelectable(true);
    message.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
    message.setText(sb.toString());
    message.setMovementMethod(LinkMovementMethod.getInstance());
    new MaterialAlertDialogBuilder(context)
        .setTitle(R.string.aerial_imagery_attribution_title)
        .setView(message)
        .setPositiveButton(R.string.ok, null)
        .show();
  }
}
