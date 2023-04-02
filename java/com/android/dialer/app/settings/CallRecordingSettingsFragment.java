package com.android.dialer.app.settings;

import android.os.Bundle;
import android.preference.PreferenceFragment;
import com.android.dialer.app.R;
import com.android.dialer.callrecord.impl.CallRecorderService;

public class CallRecordingSettingsFragment extends PreferenceFragment {

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    addPreferencesFromResource(R.xml.call_recording_settings);
  }
}
