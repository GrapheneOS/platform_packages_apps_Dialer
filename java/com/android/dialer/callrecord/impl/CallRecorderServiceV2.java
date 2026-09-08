package com.android.dialer.callrecord.impl;

import static com.android.dialer.callrecord.impl.CallRecorderService.DATE_FORMAT;
import static com.android.dialer.callrecord.impl.CallRecorderService.KEY_CALL_RECORDING_AUDIO_SOURCE;
import static java.lang.Integer.parseInt;

import android.Manifest;
import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import com.android.dialer.R;
import com.android.dialer.callrecord.CallRecording;
import com.android.dialer.callrecord.ICallRecorderService;
import java.util.Date;

public class CallRecorderServiceV2 extends Service {
  private static final String TAG = "CallRecorderServiceV2";

  private BaseCallRecorder mCallRecorder;

  private CallRecording mCurrentRecording;

  private final ICallRecorderService.Stub mBinder = new ICallRecorderService.Stub() {
    @Override
    public boolean startRecording(String phoneNumber, long creationTime) throws RemoteException {
      return startRecordingInternal(phoneNumber, creationTime);
    }

    @Override
    public CallRecording stopRecording() throws RemoteException {
      return stopRecordingInternal();
    }

    @Override
    public boolean isRecording() throws RemoteException {
      return isRecordingInternal();
    }

    @Override
    public CallRecording getActiveRecording() throws RemoteException {
      return mCurrentRecording;
    }
  };

  @Override
  public IBinder onBind(Intent intent) {
    Log.d(TAG, "onBind " + intent);
    return mBinder;
  }

  private static SharedPreferences getPrefs(Context context) {
    // This replicates PreferenceManager.getDefaultSharedPreferences, except
    // that we need multi process preferences, as the pref is written in a separate
    // process (com.android.dialer vs. com.android.incallui)
    final String prefName = context.getPackageName() + "_preferences";
    // TODO: MODE_MULTI_PROCESS is deprecated, but the settings screen relies on preferences still
    return context.getSharedPreferences(prefName, MODE_MULTI_PROCESS);
  }

  private SharedPreferences getPrefs() {
    return getPrefs(this);
  }

  private int getAudioSource() {
    String def = getString(R.string.call_recording_audio_source_default);
    return parseInt(getPrefs().getString(KEY_CALL_RECORDING_AUDIO_SOURCE, def));
  }
  private static final String KEY_CALL_RECORDING_OUTPUT_FORMAT = "call_recording_output_format_v2";

  public static boolean isV2Enabled(Context context) {
    return getPrefs(context).getBoolean("call_recording_use_v2", false);
  }

  private OutputFormat getOutputFormat() {
    String def = getString(R.string.call_recording_output_format_default);
    int selectionId = parseInt(getPrefs().getString(KEY_CALL_RECORDING_OUTPUT_FORMAT, def));
    return OutputFormat.getOutputFormat(selectionId);
  }

  static String generateFilename(String number, OutputFormat outputFormat) {
    String timestamp = DATE_FORMAT.format(new Date());

    if (TextUtils.isEmpty(number)) {
      number = "unknown";
    }

    // CallRecord_yyyyMMdd-HHmmss_number.extension (.amr/.m4a/.wav)
    return "CallRecord_" + timestamp + "_" + number + outputFormat.extension;
  }

  private boolean isRecordingInternal() {
    final BaseCallRecorder callRecorder = mCallRecorder;
    return callRecorder != null && callRecorder.isRecording();
  }

  private synchronized boolean startRecordingInternal(String phoneNumber, long creationTime) {
    Log.i(TAG, "startRecordingInternal");
    if (isRecordingInternal()) {
      Log.i(TAG, "Start called with recording in progress, stopping current recording");
      stopRecordingInternal();
    }

    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
      Log.e(TAG, "Record audio permission not granted, can't record call");
      return false;
    }

    Log.i(TAG, "Starting recording");

    final int audioSource = getAudioSource();
    final OutputFormat outputFormat = getOutputFormat();

    String fileName = generateFilename(phoneNumber, outputFormat);
    final Uri uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            CallRecording.generateMediaInsertValues(fileName, creationTime));

    if (uri == null) {
      Log.e(TAG, "failed to get uri from MediaStore");
      mCallRecorder.close();
      return false;
    }

    try {
      switch (outputFormat) {
        case AAC_MPEG_4: // fall-through
        case AMR_WB:
          mCallRecorder = new MediaCodecRecorder(this, audioSource, uri, outputFormat);
          break;
        case LPCM_WAV:
          mCallRecorder = new WavLPCMRecorder(this, audioSource, uri, outputFormat);
          break;
      }
      mCallRecorder.startRecording();

      long mediaId = Long.parseLong(uri.getLastPathSegment());
      mCurrentRecording = new CallRecording(phoneNumber, creationTime,
              fileName, System.currentTimeMillis(), mediaId);

      return true;
    } catch (IllegalStateException | IllegalArgumentException e) {
      Log.e(TAG, "Could not start recording", e);
      getContentResolver().delete(uri, null, null);
      stopAndReleaseCallRecorder();
      return false;
    }
  }

  private synchronized CallRecording stopRecordingInternal() {
    Log.d(TAG, "stopRecordingInternal");
    stopAndReleaseCallRecorder();

    final CallRecording recording = mCurrentRecording;
    if (recording != null) {
      Uri uri = ContentUris.withAppendedId(
              MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mCurrentRecording.mediaId);
      getContentResolver().update(uri, CallRecording.generateCompletedValues(), null, null);

      mCurrentRecording = null;

    }
    return recording;
  }

  private void stopAndReleaseCallRecorder() {
    try (BaseCallRecorder recorder = mCallRecorder) {
      if (recorder != null) {
        recorder.stopRecordingBlocking();
      }
    } finally {
      mCallRecorder = null;
    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    Log.d(TAG, "onDestroy");
    stopAndReleaseCallRecorder();
  }
}
