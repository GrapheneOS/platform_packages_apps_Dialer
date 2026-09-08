package com.android.dialer.app.settings;

import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import com.android.dialer.R;

public class CallRecordingSettingsFragment extends PreferenceFragment {

  private Preference formatV1;
  private Preference formatV2;

  private void setOutputOptionsVisibility(boolean isV2Enabled) {
    if (isV2Enabled) {
      hideFirstShowSecond(formatV1, formatV2);
    } else {
      hideFirstShowSecond(formatV2, formatV1);
    }
  }
  private void hideFirstShowSecond(Preference first, Preference second) {
    PreferenceScreen screen = getPreferenceScreen();
    screen.removePreference(first);
    if (screen.findPreference(second.getKey()) == null) {
      screen.addPreference(second);
    }
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    addPreferencesFromResource(R.xml.call_recording_settings);

    final SwitchPreference useV2 = (SwitchPreference) findPreference("call_recording_use_v2");
    formatV1 = findPreference("call_recording_output_format");
    formatV2 = findPreference("call_recording_output_format_v2");

    setOutputOptionsVisibility(useV2.isChecked());

    useV2.setOnPreferenceChangeListener((preference, newValue) -> {
      boolean isV2Enabled = (Boolean) newValue;
      setOutputOptionsVisibility(isV2Enabled);
      return true;
    });
  }
}
