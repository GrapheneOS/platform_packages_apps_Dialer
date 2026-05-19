package com.android.dialer.callrecord.impl;

import static com.android.dialer.callrecord.impl.CallRecorderService.DATE_FORMAT;

import static java.lang.Integer.parseInt;

import android.Manifest;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.text.TextUtils;
import android.util.Log;

import com.android.dialer.R;
import com.android.dialer.callrecord.CallRecording;
import com.android.dialer.callrecord.CallRecordingPreferences;
import com.android.dialer.callrecord.CallRecordingPreferencesStore;
import com.android.dialer.callrecord.ICallRecorderService;
import com.android.dialer.callrecord.RecordingOutputFormat;
import com.android.dialer.common.concurrent.DialerExecutorComponent;

import java.util.Date;
import java.util.Objects;
import java.util.concurrent.Executor;

public class CallRecorderServiceV2 extends AbstractCallRecorderService {
  private static final String TAG = "CallRecorderServiceV2";

  @Nullable private RecordingSession mRecordingSession;
  private Executor mFailedRecordingCleanupExecutor;

  static final class RecordingSession {
    @Nullable final RecordingBackend recorder;
    @Nullable final CallRecording recording;

    private RecordingSession(@Nullable RecordingBackend recorder, @Nullable CallRecording recording) {
      this.recorder = recorder;
      this.recording = recording;
    }

    static RecordingSession create(RecordingBackend recorder, CallRecording recording) {
      return new RecordingSession(
          Objects.requireNonNull(recorder), Objects.requireNonNull(recording));
    }

    @VisibleForTesting
    static RecordingSession partialForTesting(
        @Nullable RecordingBackend recorder, @Nullable CallRecording recording) {
      return new RecordingSession(recorder, recording);
    }
  }

  private final ICallRecorderService.Stub mBinder = new RecorderServiceBinder() {
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
  void setRecordingSessionForTesting(@Nullable RecordingSession session) {
    mRecordingSession = session;
  }

  @VisibleForTesting
  void setFailedRecordingCleanupExecutorForTesting(Executor executor) {
    mFailedRecordingCleanupExecutor = executor;
  }

  @VisibleForTesting
  boolean isRecordingForTesting() {
    return isRecordingSessionActive();
  }

  @VisibleForTesting
  @Nullable
  CallRecording getActiveRecordingForTesting() {
    return getActiveRecordingInternal();
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

  private boolean isRecordingSessionActive() {
    clearFailedSessionIfNeeded();
    synchronized (this) {
      return mRecordingSession != null;
    }
  }

  private CallRecording getActiveRecordingInternal() {
    clearFailedSessionIfNeeded();
    synchronized (this) {
      return mRecordingSession == null ? null : mRecordingSession.recording;
    }
  }

  private boolean clearFailedSessionIfNeeded() {
    return clearFailedSessionIfNeeded(null);
  }

  private boolean clearFailedSessionIfNeeded(@Nullable RecordingBackend expectedRecorder) {
    final RecordingBackend failedRecorder;
    final CallRecording failedRecording;
    synchronized (this) {
      final RecordingSession session = mRecordingSession;
      final RecordingBackend recorder = session == null ? null : session.recorder;
      if (session == null
          || recorder == null
          || (expectedRecorder != null && recorder != expectedRecorder)
          || !recorder.hasFailed()) {
        return false;
      }
      Log.e(
          TAG, "Recording backend failed, clearing active session", recorder.getRecordingFailure());
      failedRecorder = recorder;
      failedRecording = session.recording;
      mRecordingSession = null;
    }
    notifyRecordingError(TAG);
    // Keep failure observation cheap; blocking teardown and MediaStore cleanup run outside the
    // synchronized section.
    getFailedRecordingCleanupExecutor().execute(
        () -> {
          stopAndReleaseCallRecorder(failedRecorder);
          if (failedRecording != null) {
            // Failed async starts can leave an incomplete row; do not expose it as saved.
            Uri uri =
                ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, failedRecording.mediaId);
            getContentResolver().delete(uri, null, null);
          }
        });
    return true;
  }

  private Executor getFailedRecordingCleanupExecutor() {
    if (mFailedRecordingCleanupExecutor != null) {
      return mFailedRecordingCleanupExecutor;
    }
    return DialerExecutorComponent.get(this).backgroundExecutor();
  }

  private synchronized boolean startRecordingInternal(String phoneNumber, long creationTime) {
    Log.i(TAG, "startRecordingInternal");
    if (isRecordingSessionActive()) {
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

    RecordingBackend recorder = null;
    try {
      long mediaId = Long.parseLong(uri.getLastPathSegment());
      recorder = createRecordingBackend(audioSource, uri, outputFormat);
      final RecordingBackend activeRecorder = recorder;
      mRecordingSession =
          RecordingSession.create(
              recorder,
              new CallRecording(
                  phoneNumber, creationTime, fileName, System.currentTimeMillis(), mediaId));
      recorder.setFailureListener(() -> clearFailedSessionIfNeeded(activeRecorder));
      recorder.startRecording();

      return true;
    } catch (IllegalStateException | IllegalArgumentException e) {
      Log.e(TAG, "Could not start recording", e);
      getContentResolver().delete(uri, null, null);
      mRecordingSession = null;
      stopAndReleaseCallRecorder(recorder);
      return false;
    }
  }

  private RecordingBackend createRecordingBackend(
      int audioSource, Uri uri, OutputFormat outputFormat) {
    switch (outputFormat) {
      case AAC_MPEG_4: // fall-through
      case AMR_WB:
        return new MediaCodecRecorder(this, audioSource, uri, outputFormat);
      case LPCM_WAV:
        return new WavLPCMRecorder(this, audioSource, uri, outputFormat);
      default:
        throw new AssertionError(outputFormat);
    }
  }

  private synchronized CallRecording stopRecordingInternal() {
    Log.d(TAG, "stopRecordingInternal");
    if (clearFailedSessionIfNeeded()) {
      return null;
    }

    final RecordingSession session = mRecordingSession;
    mRecordingSession = null;
    stopAndReleaseCallRecorder(session == null ? null : session.recorder);

    final CallRecording recording = session == null ? null : session.recording;
    if (recording != null) {
      Uri uri = ContentUris.withAppendedId(
              MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, recording.mediaId);
      getContentResolver().update(uri, CallRecording.generateCompletedValues(), null, null);
    }
    return recording;
  }

  private static void stopAndReleaseCallRecorder(RecordingBackend recorder) {
    if (recorder == null) {
      return;
    }
    recorder.setFailureListener(null);
    try (RecordingBackend ignored = recorder) {
      recorder.stopRecordingBlocking();
    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    Log.d(TAG, "onDestroy");
    stopRecordingInternal();
  }
}
