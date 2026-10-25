package app.organicmaps.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.TransitServiceConfig;
import app.organicmaps.sdk.transit.provider.TransitousProvider;
import app.organicmaps.transit.TransitServices;
import app.organicmaps.transit.TransitSettings;
import app.organicmaps.transit.ui.TransitConsentDialog;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Settings of online public transport (better-maps). Values live in the "transit" SharedPreferences. */
@Keep
public class TransitSettingsFragment extends BaseXmlSettingsFragment
{
  @Override
  protected int getXmlResources()
  {
    return R.xml.prefs_transit;
  }

  @Override
  public void onCreatePreferences(Bundle bundle, String root)
  {
    getPreferenceManager().setSharedPreferencesName(TransitSettings.PREFS_NAME);
    super.onCreatePreferences(bundle, root);
    initOnline();
    initAttribution();
    initMaxWalk();
    initExcludedModes();
    initServer();
    for (String key : new String[] {TransitSettings.KEY_TRANSFER_TIME, TransitSettings.KEY_WALKING_SPEED,
                                    TransitSettings.KEY_WHEELCHAIR, TransitSettings.KEY_BIKE})
    {
      final Preference pref = findPreference(key);
      if (pref != null)
        pref.setOnPreferenceChangeListener((p, value) -> {
          notifyChangedLater();
          return true;
        });
    }
  }

  private TransitServices services()
  {
    return TransitServices.get(requireContext());
  }

  /** The switch shows the consent; switching it on asks for consent with the explanation dialog. */
  private void initOnline()
  {
    final TwoStatePreference online = getPreference(TransitSettings.KEY_ONLINE);
    online.setChecked(services().getConsent().isGranted());
    online.setOnPreferenceChangeListener((pref, value) -> {
      if ((Boolean) value)
      {
        TransitConsentDialog.show(requireContext(), granted -> {
          online.setChecked(granted);
          updateEnabledState();
        });
        // Checked only after the user agreed in the dialog.
        return false;
      }
      services().getConsent().decline();
      services().onConsentChanged();
      online.setChecked(false);
      updateEnabledState();
      return false;
    });
    updateEnabledState();
  }

  private void updateEnabledState()
  {
    final boolean granted = services().getConsent().isGranted();
    final Preference route = findPreference("transit_category_route");
    if (route != null)
      route.setEnabled(granted);
  }

  private void initAttribution()
  {
    final Preference pref = getPreference("transit_attribution");
    pref.setOnPreferenceClickListener(p -> {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TransitousProvider.SOURCES_URL)));
      return true;
    });
  }

  private void initMaxWalk()
  {
    final ListPreference pref = getPreference(TransitSettings.KEY_MAX_WALK);
    final CharSequence[] values = pref.getEntryValues();
    final CharSequence[] entries = new CharSequence[values.length];
    for (int i = 0; i < values.length; ++i)
      entries[i] = getString(R.string.transit_minutes, Integer.parseInt(values[i].toString()));
    pref.setEntries(entries);
    pref.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
    pref.setOnPreferenceChangeListener((p, value) -> {
      notifyChangedLater();
      return true;
    });
  }

  private void initExcludedModes()
  {
    final MultiSelectListPreference pref = getPreference(TransitSettings.KEY_EXCLUDED_MODES);
    pref.setSummaryProvider(
        p -> summarizeExcluded((MultiSelectListPreference) p, ((MultiSelectListPreference) p).getValues()));
    pref.setOnPreferenceChangeListener((p, value) -> {
      @SuppressWarnings("unchecked")
      final Set<String> excluded = (Set<String>) value;
      if (!TransitSettings.isValidExclusion(excluded))
      {
        Toast.makeText(requireContext(), R.string.transit_pref_excluded_all, Toast.LENGTH_LONG).show();
        return false;
      }
      notifyChangedLater();
      return true;
    });
  }

  @NonNull
  private String summarizeExcluded(@NonNull MultiSelectListPreference pref, @NonNull Set<String> values)
  {
    if (values.isEmpty())
      return getString(R.string.transit_pref_excluded_none);
    final List<String> names = new ArrayList<>();
    final CharSequence[] entryValues = pref.getEntryValues();
    for (int i = 0; i < entryValues.length; ++i)
      if (values.contains(entryValues[i].toString()))
        names.add(pref.getEntries()[i].toString());
    return String.join(", ", names);
  }

  private void initServer()
  {
    final EditTextPreference pref = getPreference(TransitSettings.KEY_SERVER_URL);
    pref.setSummaryProvider(p -> services().getSettings().getServerUrl());
    pref.setOnBindEditTextListener(editText -> {
      editText.setSingleLine(true);
      editText.setHint(TransitServiceConfig.TRANSITOUS_URL);
    });
    pref.setOnPreferenceChangeListener((p, value) -> {
      final String url = value == null ? "" : value.toString().trim();
      if (!url.isEmpty() && !TransitSettings.isValidServerUrl(url))
      {
        Toast.makeText(requireContext(), R.string.transit_pref_server_invalid, Toast.LENGTH_LONG).show();
        return false;
      }
      notifyChangedLater();
      return true;
    });
  }

  /** Preferences are stored after the change listener returns, so apply them afterwards. */
  private void notifyChangedLater()
  {
    if (getView() != null)
      getView().post(() -> {
        if (isAdded())
          services().onSettingsChanged();
      });
  }
}
