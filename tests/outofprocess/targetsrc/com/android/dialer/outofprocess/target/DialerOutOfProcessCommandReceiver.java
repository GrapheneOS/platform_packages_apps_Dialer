package com.android.dialer.outofprocess.target;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.android.dialer.callrecord.CallRecordingPreferences;
import com.android.dialer.callrecord.CallRecordingPreferencesStore;
import com.android.dialer.callrecord.RecordingOutputFormat;
import com.android.incallui.call.CallList;
import com.android.incallui.call.DialerCall;
import com.android.incallui.call.TelecomAdapter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;

/**
 * Test-only command receiver compiled into DialerForTesting.
 *
 * <p>The standalone out of process tests run outside Dialer so they can kill Dialer's process
 * while a Telecom call is still alive. Shell broadcasts guarded by android.permission.DUMP let
 * those tests seed and restore credential encrypted Dialer state and invoke call controls inside
 * Dialer's process without adding any production receiver or source dependency.
 */
public final class DialerOutOfProcessCommandReceiver extends BroadcastReceiver {
  public static final String ACTION_SAVE_CALL_RECORDING_PREFERENCES =
      "com.android.dialer.outofprocess.SAVE_CALL_RECORDING_PREFERENCES";
  public static final String ACTION_RESTORE_CALL_RECORDING_PREFERENCES =
      "com.android.dialer.outofprocess.RESTORE_CALL_RECORDING_PREFERENCES";
  public static final String ACTION_SEED_AUTO_RECORD_NON_CONTACTS =
      "com.android.dialer.outofprocess.SEED_AUTO_RECORD_NON_CONTACTS";
  public static final String ACTION_SEED_RECORDING_SWITCH_WITHOUT_AUTOMATIC_RULES =
      "com.android.dialer.outofprocess.SEED_RECORDING_SWITCH_WITHOUT_AUTOMATIC_RULES";
  public static final String ACTION_MERGE_ACTIVE_CALL_FOR_TESTING =
      "com.android.dialer.outofprocess.MERGE_ACTIVE_CALL_FOR_TESTING";

  private static final String SAVED_PREFERENCES_FILE =
      "dialer_outofprocess_original_call_recording.pb";

  @Override
  public void onReceive(Context context, Intent intent) {
    try {
      String action = intent.getAction();
      if (ACTION_SAVE_CALL_RECORDING_PREFERENCES.equals(action)) {
        saveCallRecordingPreferences(context);
      } else if (ACTION_RESTORE_CALL_RECORDING_PREFERENCES.equals(action)) {
        restoreCallRecordingPreferences(context);
      } else if (ACTION_SEED_AUTO_RECORD_NON_CONTACTS.equals(action)) {
        seedAutoRecordNonContacts(context);
      } else if (ACTION_SEED_RECORDING_SWITCH_WITHOUT_AUTOMATIC_RULES.equals(action)) {
        seedRecordingSwitchWithoutAutomaticRules(context);
      } else if (ACTION_MERGE_ACTIVE_CALL_FOR_TESTING.equals(action)) {
        mergeActiveCall();
      } else {
        throw new IllegalArgumentException("Unknown Dialer out of process command: " + action);
      }
    } catch (Exception e) {
      throw new IllegalStateException("Dialer out of process command failed", e);
    }
  }

  private static void saveCallRecordingPreferences(Context context) throws Exception {
    CallRecordingPreferences preferences =
        CallRecordingPreferencesStore.loadAsync(context).get(10, TimeUnit.SECONDS);
    try (FileOutputStream outputStream = new FileOutputStream(savedPreferencesFile(context))) {
      preferences.writeTo(outputStream);
    }
  }

  private static void restoreCallRecordingPreferences(Context context) throws Exception {
    File savedPreferences = savedPreferencesFile(context);
    if (savedPreferences.exists()) {
      try (FileInputStream inputStream = new FileInputStream(savedPreferences)) {
        writeCallRecordingPreferences(context, CallRecordingPreferences.parseFrom(inputStream));
      }
      savedPreferences.delete();
    }
    DialerOutOfProcessSessionStore.clearCallRecordingSessionState(context);
  }

  private static void seedAutoRecordNonContacts(Context context) throws Exception {
    writeCallRecordingPreferences(
        context,
        CallRecordingPreferences.newBuilder()
            .setSharedPreferencesMigrated(true)
            .setUseCallRecordingV2(true)
            .setCallRecordingOutputFormatV2(RecordingOutputFormat.LPCM_WAV)
            .setRecordingWarningPresented(true)
            .setAutoRecordingSetAtLeastOnce(true)
            .setAutoRecordNonContacts(true)
            .build());
    DialerOutOfProcessSessionStore.clearCallRecordingSessionState(context);
  }

  private static void seedRecordingSwitchWithoutAutomaticRules(Context context) throws Exception {
    writeCallRecordingPreferences(
        context,
        CallRecordingPreferences.newBuilder()
            .setSharedPreferencesMigrated(true)
            .setUseCallRecordingV2(true)
            .setCallRecordingOutputFormatV2(RecordingOutputFormat.LPCM_WAV)
            .setRecordingWarningPresented(true)
            .setAutoRecordingSetAtLeastOnce(true)
            .build());
    DialerOutOfProcessSessionStore.clearCallRecordingSessionState(context);
  }

  private static void writeCallRecordingPreferences(
      Context context, CallRecordingPreferences preferences) {
    CallRecordingPreferencesStore.updateBlocking(
        context,
        builder -> {
          builder.clear();
          builder.mergeFrom(preferences);
        });
  }

  private static void mergeActiveCall() {
    // The test APK cannot access Dialer's singleton call state. Merge in Dialer's process so
    // TelecomAdapter uses the live CallList and InCallService state.
    DialerCall activeCall = CallList.getInstance().getActiveCall();
    if (activeCall == null) {
      throw new IllegalStateException("No active call to merge");
    }
    TelecomAdapter.getInstance().merge(activeCall.getId());
  }

  private static File savedPreferencesFile(Context context) {
    return new File(context.getFilesDir(), SAVED_PREFERENCES_FILE);
  }
}
