package app.organicmaps.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import androidx.annotation.Keep;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;
import app.organicmaps.R;
import app.organicmaps.sdk.search.online.PhotonConfig;
import app.organicmaps.sdk.search.online.PhotonSearchProvider;
import app.organicmaps.search.online.OnlineSearchServices;
import app.organicmaps.search.online.OnlineSearchSettings;
import app.organicmaps.search.online.ui.OnlineSearchConsentDialog;

/** Settings of the online search (better-maps). Values live in the "online_search" SharedPreferences. */
@Keep
public class OnlineSearchSettingsFragment extends BaseXmlSettingsFragment
{
  @Override
  protected int getXmlResources()
  {
    return R.xml.prefs_online_search;
  }

  @Override
  public void onCreatePreferences(Bundle bundle, String root)
  {
    getPreferenceManager().setSharedPreferencesName(OnlineSearchSettings.PREFS_NAME);
    super.onCreatePreferences(bundle, root);
    initOnline();
    initAttribution();
    initServer();
  }

  private OnlineSearchServices services()
  {
    return OnlineSearchServices.get(requireContext());
  }

  /** The switch shows the consent; switching it on asks for consent with the explanation dialog. */
  private void initOnline()
  {
    final TwoStatePreference online = getPreference(OnlineSearchSettings.KEY_ONLINE);
    online.setChecked(services().getConsent().isGranted());
    online.setOnPreferenceChangeListener((pref, value) -> {
      if ((Boolean) value)
      {
        // Checked only after the user agreed in the dialog.
        OnlineSearchConsentDialog.show(requireContext(), online::setChecked);
        return false;
      }
      services().getConsent().decline();
      services().onConsentChanged();
      online.setChecked(false);
      return false;
    });
  }

  private void initAttribution()
  {
    final Preference pref = getPreference("online_search_attribution");
    pref.setOnPreferenceClickListener(p -> {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PhotonSearchProvider.ATTRIBUTION_URL)));
      return true;
    });
  }

  private void initServer()
  {
    final EditTextPreference pref = getPreference(OnlineSearchSettings.KEY_SERVER_URL);
    pref.setSummaryProvider(p -> services().getSettings().getServerUrl());
    pref.setOnBindEditTextListener(editText -> {
      editText.setSingleLine(true);
      editText.setHint(PhotonConfig.DEFAULT_URL);
    });
    pref.setOnPreferenceChangeListener((p, value) -> {
      final String url = value == null ? "" : value.toString().trim();
      if (!url.isEmpty() && !OnlineSearchSettings.isValidServerUrl(url))
      {
        Toast.makeText(requireContext(), R.string.online_search_pref_server_invalid, Toast.LENGTH_LONG).show();
        return false;
      }
      // Preferences are stored after the listener returns, so apply them afterwards.
      if (getView() != null)
        getView().post(() -> {
          if (isAdded())
            services().onSettingsChanged();
        });
      return true;
    });
  }
}
