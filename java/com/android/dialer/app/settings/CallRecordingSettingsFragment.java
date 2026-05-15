package com.android.dialer.app.settings;

import android.content.Context;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;

import com.android.dialer.app.R;
import com.android.dialer.callrecord.CallRecordingPreferences;
import com.android.dialer.callrecord.CallRecordingPreferencesStore;
import com.android.dialer.callrecord.RecordingOutputFormat;
import com.android.dialer.common.LogUtil;
import com.android.dialer.common.concurrent.DialerExecutorComponent;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

public class CallRecordingSettingsFragment extends PreferenceFragment {

  private static final RecordingOutputFormat DEFAULT_OUTPUT_FORMAT =
      RecordingOutputFormat.AAC_MPEG_4;

  private Context appContext;
  private ListeningExecutorService backgroundExecutor;
  private ListeningExecutorService uiExecutor;
  private SwitchPreference useV2;
  private ListPreference audioSource;
  private ListPreference formatV1;
  private ListPreference formatV2;
  private boolean applyingPreferences;

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

  private static RecordingOutputFormat parseOutputFormatPreferenceValue(Object value) {
    if (!(value instanceof String)) {
      return null;
    }
    try {
      switch (Integer.parseInt((String) value)) {
        case 0:
          return RecordingOutputFormat.AAC_MPEG_4;
        case 1:
          return RecordingOutputFormat.AMR_WB;
        case 2:
          return RecordingOutputFormat.LPCM_WAV;
        default:
          return null;
      }
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String toOutputFormatPreferenceValue(RecordingOutputFormat outputFormat) {
    switch (outputFormat) {
      case AAC_MPEG_4:
        return "0";
      case AMR_WB:
        return "1";
      case LPCM_WAV:
        return "2";
      default:
        return "0";
    }
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    addPreferencesFromResource(R.xml.call_recording_settings);

    appContext = getActivity().getApplicationContext();
    DialerExecutorComponent executorComponent = DialerExecutorComponent.get(appContext);
    backgroundExecutor = executorComponent.backgroundExecutor();
    uiExecutor = executorComponent.uiExecutor();

    useV2 = (SwitchPreference) findPreference("call_recording_use_v2");
    formatV1 = (ListPreference) findPreference("call_recording_output_format");
    formatV2 = (ListPreference) findPreference("call_recording_output_format_v2");
    audioSource = (ListPreference) findPreference("call_recording_audio_source");

    setPreferencesEnabled(CallRecordingPreferencesStore.isLoaded(appContext));
    configureListeners();
    loadPreferences();
  }

  @Override
  public void onResume() {
    super.onResume();
    loadPreferences();
  }

  private void loadPreferences() {
    Futures.addCallback(
        CallRecordingPreferencesStore.loadAsync(appContext),
        new FutureCallback<CallRecordingPreferences>() {
          @Override
          public void onSuccess(CallRecordingPreferences preferences) {
            if (!isAdded()) {
              return;
            }
            renderPreferences(preferences);
            setPreferencesEnabled(CallRecordingPreferencesStore.isLoaded(appContext));
          }

          @Override
          public void onFailure(Throwable throwable) {
            LogUtil.e(
                "CallRecordingSettingsFragment.loadPreferences",
                "failed to load call recording preferences",
                throwable);
          }
        },
        uiExecutor);
  }

  private void renderPreferences(CallRecordingPreferences preferences) {
    applyingPreferences = true;
    try {
      boolean isV2Enabled = preferences.getUseCallRecordingV2();
      useV2.setChecked(isV2Enabled);
      formatV1.setValue(
          toOutputFormatPreferenceValue(
              preferences.hasCallRecordingOutputFormat()
                  ? preferences.getCallRecordingOutputFormat()
                  : DEFAULT_OUTPUT_FORMAT));
      formatV2.setValue(
          toOutputFormatPreferenceValue(
              preferences.hasCallRecordingOutputFormatV2()
                  ? preferences.getCallRecordingOutputFormatV2()
                  : DEFAULT_OUTPUT_FORMAT));
      audioSource.setValue(
          preferences.hasCallRecordingAudioSource()
                  && !preferences.getCallRecordingAudioSource().isEmpty()
              ? preferences.getCallRecordingAudioSource()
              : getString(R.string.call_recording_audio_source_default));
      setOutputOptionsVisibility(isV2Enabled);
    } finally {
      applyingPreferences = false;
    }
  }

  private void configureListeners() {
    useV2.setOnPreferenceChangeListener(
        (preference, newValue) -> {
          if (applyingPreferences) {
            return true;
          }
          boolean isV2Enabled = (Boolean) newValue;
          updatePreference(
              () ->
                  CallRecordingPreferencesStore.setCallRecordingV2Enabled(
                      appContext, isV2Enabled));
          setOutputOptionsVisibility(isV2Enabled);
          return true;
        });
    formatV1.setOnPreferenceChangeListener(
        (preference, newValue) -> {
          RecordingOutputFormat outputFormat = parseOutputFormatPreferenceValue(newValue);
          if (outputFormat == null) {
            return false;
          }
          updatePreference(
              () -> CallRecordingPreferencesStore.setOutputFormat(appContext, outputFormat));
          return true;
        });
    formatV2.setOnPreferenceChangeListener(
        (preference, newValue) -> {
          RecordingOutputFormat outputFormat = parseOutputFormatPreferenceValue(newValue);
          if (outputFormat == null) {
            return false;
          }
          updatePreference(
              () -> CallRecordingPreferencesStore.setOutputFormatV2(appContext, outputFormat));
          return true;
        });
    audioSource.setOnPreferenceChangeListener(
        (preference, newValue) -> {
          updatePreference(
              () -> CallRecordingPreferencesStore.setAudioSource(appContext, (String) newValue));
          return true;
        });
  }

  private void updatePreference(Runnable update) {
    ListenableFuture<Object> updateFuture =
        backgroundExecutor.submit(
            () -> {
              update.run();
              return null;
            });
    Futures.addCallback(
        updateFuture,
        new FutureCallback<Object>() {
          @Override
          public void onSuccess(Object result) {}

          @Override
          public void onFailure(Throwable throwable) {
            LogUtil.e(
                "CallRecordingSettingsFragment.updatePreference",
                "failed to update call recording preferences",
                throwable);
            loadPreferences();
          }
        },
        uiExecutor);
  }

  private void setPreferencesEnabled(boolean enabled) {
    useV2.setEnabled(enabled);
    formatV1.setEnabled(enabled);
    formatV2.setEnabled(enabled);
    audioSource.setEnabled(enabled);
  }
}
