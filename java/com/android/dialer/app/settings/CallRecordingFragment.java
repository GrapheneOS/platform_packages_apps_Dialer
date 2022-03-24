package com.android.dialer.app.settings;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.widget.Toast;
import com.android.dialer.app.R;
import com.android.dialer.callrecord.impl.CallRecorderService;
import com.android.dialer.util.SettingsUtil;

public class CallRecordingFragment extends PreferenceFragment
    implements Preference.OnPreferenceChangeListener {

  @Override
  public Context getContext() {
    return getActivity();
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    addPreferencesFromResource(R.xml.call_recording_settings);

    Context context = getActivity();

    if (!CallRecorderService.isEnabled(getActivity())) {
      getPreferenceScreen().removePreference(
          findPreference(context.getString(R.string.call_recording_category_key)));
    }
  }

  @Override
  public void onResume() {
    super.onResume();

    if (!Settings.System.canWrite(getContext())) {
      // If the user launches this setting fragment, then toggles the WRITE_SYSTEM_SETTINGS
      // AppOp, then close the fragment since there is nothing useful to do.
      getActivity().onBackPressed();
      return;
    }
  }

  /**
   * Supports onPreferenceChangeListener to look for preference changes.
   *
   * @param preference The preference to be changed
   * @param objValue The value of the selection, NOT its localized display value.
   */
  @Override
  public boolean onPreferenceChange(Preference preference, Object objValue) {
    if (!Settings.System.canWrite(getContext())) {
      // A user shouldn't be able to get here, but this protects against monkey crashes.
      Toast.makeText(
              getContext(),
              getResources().getString(R.string.toast_cannot_write_system_settings),
              Toast.LENGTH_SHORT)
          .show();
      return true;
    }
    return true;
  }

  /** Click listener for toggle events. */
  @Override
  public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
    if (!Settings.System.canWrite(getContext())) {
      // A user shouldn't be able to get here, but this protects against monkey crashes.
      Toast.makeText(
              getContext(),
              getResources().getString(R.string.toast_cannot_write_system_settings),
              Toast.LENGTH_SHORT)
          .show();
      return true;
    }
    return true;
  }
}