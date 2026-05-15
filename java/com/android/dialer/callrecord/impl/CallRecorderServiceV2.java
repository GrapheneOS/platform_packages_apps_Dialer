package com.android.dialer.callrecord.impl;

import static com.android.dialer.callrecord.impl.CallRecorderService.DATE_FORMAT;

import static java.lang.Integer.parseInt;

import android.Manifest;
import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.support.annotation.VisibleForTesting;
import android.text.TextUtils;
import android.util.Log;

import com.android.dialer.R;
import com.android.dialer.callrecord.CallRecording;
import com.android.dialer.callrecord.CallRecordingPreferences;
import com.android.dialer.callrecord.CallRecordingPreferencesStore;
import com.android.dialer.callrecord.ICallRecorderService;
import com.android.dialer.callrecord.RecordingOutputFormat;

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

  };

  @Override
  public IBinder onBind(Intent intent) {
    Log.d(TAG, "onBind " + intent);
    return mBinder;
  }

  @VisibleForTesting
  void setCurrentRecordingForTesting(CallRecording currentRecording) {
    mCurrentRecording = currentRecording;
  }

  @VisibleForTesting
  boolean isRecordingForTesting() {
    return isRecordingInternal();
  }

  private int getAudioSource() {
    String def = getString(R.string.call_recording_audio_source_default);
    // This service starts the recording backend from a synchronous Binder method. DataStore owns
    // the in-memory cache; readBlocking is only the Java service bridge for choosing recorder
    // parameters before startRecording returns.
    CallRecordingPreferences preferences = CallRecordingPreferencesStore.readBlocking(this);
    return parseInt(
        preferences.hasCallRecordingAudioSource()
                && !TextUtils.isEmpty(preferences.getCallRecordingAudioSource())
            ? preferences.getCallRecordingAudioSource()
            : def);
  }

  public static boolean isV2Enabled(Context context) {
    return CallRecordingPreferencesStore.getSnapshot().getUseCallRecordingV2();
  }

  private OutputFormat getOutputFormat() {
    // See getAudioSource(): this synchronous bridge keeps service startup simple while avoiding a
    // separate preferences cache in the Java recorder service.
    CallRecordingPreferences preferences = CallRecordingPreferencesStore.readBlocking(this);
    return OutputFormat.fromRecordingOutputFormat(
        preferences.hasCallRecordingOutputFormatV2()
            ? preferences.getCallRecordingOutputFormatV2()
            : RecordingOutputFormat.AAC_MPEG_4);
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
    stopRecordingInternal();
  }
}
