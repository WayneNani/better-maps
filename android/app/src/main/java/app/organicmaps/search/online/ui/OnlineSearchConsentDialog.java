package app.organicmaps.search.online.ui;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import app.organicmaps.R;
import app.organicmaps.sdk.search.online.PhotonSearchProvider;
import app.organicmaps.search.online.OnlineSearchServices;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Opt-in dialog of the online search. It explains which data is sent to whom (search text and approximate map
 * position to the Photon server). "Allow" and "Not now" are stored; dismissing the dialog stores nothing.
 */
public final class OnlineSearchConsentDialog
{
  public interface Listener
  {
    void onResult(boolean granted);
  }

  private OnlineSearchConsentDialog() {}

  @NonNull
  public static AlertDialog show(@NonNull Context context, @NonNull Listener listener)
  {
    final OnlineSearchServices services = OnlineSearchServices.get(context);
    final boolean[] answered = {false};
    final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                                   .setTitle(R.string.online_search_consent_title)
                                   .setMessage(message(context))
                                   .setPositiveButton(R.string.online_search_consent_accept,
                                                      (d, which) -> {
                                                        answered[0] = true;
                                                        services.getConsent().grant(System.currentTimeMillis());
                                                        services.onConsentChanged();
                                                        listener.onResult(true);
                                                      })
                                   .setNegativeButton(R.string.online_search_consent_decline,
                                                      (d, which) -> {
                                                        answered[0] = true;
                                                        services.getConsent().decline();
                                                        services.onConsentChanged();
                                                        listener.onResult(false);
                                                      })
                                   .setOnDismissListener(d -> {
                                     // Closed without an answer (back button, outside): nothing is stored.
                                     if (!answered[0])
                                       listener.onResult(false);
                                   })
                                   .show();
    final TextView messageView = dialog.findViewById(android.R.id.message);
    if (messageView != null)
      messageView.setMovementMethod(LinkMovementMethod.getInstance());
    return dialog;
  }

  @NonNull
  static CharSequence message(@NonNull Context context)
  {
    final SpannableStringBuilder sb =
        new SpannableStringBuilder(context.getString(R.string.online_search_consent_message));
    sb.append("\n\n");
    final int start = sb.length();
    sb.append(context.getString(R.string.online_search_consent_learn_more));
    sb.setSpan(new URLSpan(PhotonSearchProvider.WEBSITE_URL), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    return sb;
  }
}
