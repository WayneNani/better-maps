package app.organicmaps.settings;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Toast;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.preference.EditTextPreference;
import app.organicmaps.R;

/**
 * Server of the aerial imagery layer (better-maps). Values live in the "aerial_imagery" SharedPreferences, see
 * {@link AerialImagerySettings}; the layer itself is switched in the map layers menu. The token is shown as "set"/"not
 * set" only.
 */
@Keep
public class AerialImagerySettingsFragment extends BaseXmlSettingsFragment
{
  @Override
  protected int getXmlResources()
  {
    return R.xml.prefs_aerial_imagery;
  }

  @Override
  public void onCreatePreferences(Bundle bundle, String root)
  {
    getPreferenceManager().setSharedPreferencesName(AerialImagerySettings.PREFS_NAME);
    super.onCreatePreferences(bundle, root);
    initUrl();
    initToken();
  }

  @NonNull
  private AerialImagerySettings settings()
  {
    return AerialImagerySettings.get(requireContext());
  }

  /** The preference stores the new value after its listener returned: apply afterwards. */
  private void applyLater()
  {
    final Context context = requireContext().getApplicationContext();
    new Handler(Looper.getMainLooper()).post(() -> AerialImagerySettings.applyToCore(context));
  }

  private void initUrl()
  {
    final EditTextPreference pref = getPreference(AerialImagerySettings.KEY_URL);
    pref.setSummaryProvider(p -> {
      final String url = settings().getUrl();
      return url.isEmpty() ? getString(R.string.routing_server_not_set) : url;
    });
    pref.setOnBindEditTextListener(editText -> {
      editText.setSingleLine(true);
      editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
      editText.setHint(R.string.aerial_imagery_url_hint);
    });
    pref.setOnPreferenceChangeListener((p, value) -> {
      final String url = value == null ? "" : value.toString().trim();
      final RoutingServerSettings.UrlProblem problem = RoutingServerSettings.checkUrl(url);
      if (problem == RoutingServerSettings.UrlProblem.INVALID)
      {
        Toast.makeText(requireContext(), R.string.routing_server_url_invalid, Toast.LENGTH_LONG).show();
        return false;
      }
      if (problem == RoutingServerSettings.UrlProblem.INSECURE)
      {
        Toast.makeText(requireContext(), R.string.routing_server_url_insecure, Toast.LENGTH_LONG).show();
        return false;
      }
      applyLater();
      return true;
    });
  }

  private void initToken()
  {
    final EditTextPreference pref = getPreference(AerialImagerySettings.KEY_TOKEN);
    pref.setSummaryProvider(
        p -> getString(settings().hasToken() ? R.string.routing_server_token_set : R.string.routing_server_not_set));
    pref.setOnBindEditTextListener(editText -> {
      editText.setSingleLine(true);
      editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
      // Don't show the stored token again; an empty field keeps it.
      editText.setText("");
    });
    // Not persistent (prefs_aerial_imagery.xml): the token is stored here, trimmed; the preference keeps no copy.
    pref.setOnPreferenceChangeListener((p, value) -> {
      final String token = value == null ? "" : value.toString().trim();
      if (token.isEmpty())
        return false;  // Empty input keeps the stored token.
      settings().setToken(token);
      applyLater();
      return true;
    });
  }
}
