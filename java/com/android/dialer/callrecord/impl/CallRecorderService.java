/*
 * Copyright (C) 2014 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dialer.callrecord.impl;

import static java.lang.Integer.parseInt;

import android.app.Service;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
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

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;

@Deprecated
public class CallRecorderService extends Service {

  private static final String TAG = "CallRecorderService";
  private static final boolean DBG = false;

  private MediaRecorder mMediaRecorder = null;
  private CallRecording mCurrentRecording = null;

  static SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyyMMdd-HHmmss");

  private final ICallRecorderService.Stub mBinder = new ICallRecorderService.Stub() {
    @Override
    public CallRecording stopRecording() {
      return stopRecordingInternal();
    }

    @Override
    public boolean startRecording(String phoneNumber, long creationTime) throws RemoteException {
      return startRecordingInternal(phoneNumber, creationTime);
    }

  };

  @Override
  public void onCreate() {
    if (DBG) Log.d(TAG, "Creating CallRecorderService");
  }

  @Override
  public IBinder onBind(Intent intent) {
    return mBinder;
  }

  @VisibleForTesting
  void setMediaRecorderForTesting(MediaRecorder mediaRecorder) {
    mMediaRecorder = mediaRecorder;
  }

  @VisibleForTesting
  MediaRecorder getMediaRecorderForTesting() {
    return mMediaRecorder;
  }

  @VisibleForTesting
  void setCurrentRecordingForTesting(CallRecording currentRecording) {
    mCurrentRecording = currentRecording;
  }

  @VisibleForTesting
  boolean isRecordingForTesting() {
    return mMediaRecorder != null;
  }

  private int getAudioSource() {
    String def = getString(R.string.call_recording_audio_source_default);
    // This service starts MediaRecorder from a synchronous Binder method. DataStore owns the
    // in-memory cache; readBlocking is only the Java service bridge for choosing recorder
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
        preferences.hasCallRecordingOutputFormat()
            ? preferences.getCallRecordingOutputFormat()
            : RecordingOutputFormat.AAC_MPEG_4);
  }

  private synchronized boolean startRecordingInternal(String phoneNumber, long creationTime) {
    if (mMediaRecorder != null) {
      Log.i(TAG, "Start called with recording in progress, stopping current recording");
      stopRecordingInternal();
    }

    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      Log.e(TAG, "Record audio permission not granted, can't record call");
      return false;
    }

    Log.i(TAG, "Starting recording");

    final int audioSource = getAudioSource();
    final OutputFormat outputFormat = getOutputFormat();

    mMediaRecorder = new MediaRecorder();
    try {
      Log.d(TAG, "Creating media recorder with audio source " + audioSource);

      mMediaRecorder.setAudioSource(audioSource);
      if (outputFormat == OutputFormat.AAC_MPEG_4) {
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
      } else if (outputFormat == OutputFormat.AMR_WB) {
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_WB);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_WB);
      } else {
        throw new IllegalStateException("unexpected output format " + outputFormat);
      }
    } catch (IllegalStateException e) {
      Log.e(TAG, "Error initializing media recorder", e);

      releaseMediaRecorder();

      return false;
    }

    String fileName = generateFilename(phoneNumber, outputFormat);
    Uri uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            CallRecording.generateMediaInsertValues(fileName, creationTime));
    if (uri == null) {
      Log.e(TAG, "failed to get uri from MediaStore");
      releaseMediaRecorder();
      return false;
    }

    try {
      try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w")) {
        if (pfd == null) {
          throw new IOException("Opening file for URI " + uri + " failed");
        }
        mMediaRecorder.setOutputFile(pfd.getFileDescriptor());
        mMediaRecorder.prepare();
      }
      mMediaRecorder.start();

      long mediaId = Long.parseLong(uri.getLastPathSegment());
      mCurrentRecording = new CallRecording(phoneNumber, creationTime,
              fileName, System.currentTimeMillis(), mediaId);
      return true;
    } catch (IOException | IllegalStateException e) {
      Log.e(TAG, "Could not start recording", e);
      getContentResolver().delete(uri, null, null);
    } catch (RuntimeException e) {
      getContentResolver().delete(uri, null, null);
      // only catch exceptions thrown by the MediaRecorder JNI code
      if (e.getMessage().indexOf("start failed") >= 0) {
        Log.e(TAG, "Could not start recording", e);
      } else {
        throw e;
      }
    }

    releaseMediaRecorder();

    return false;
  }

  private synchronized CallRecording stopRecordingInternal() {
    CallRecording recording = mCurrentRecording;
    Log.d(TAG, "Stopping current recording");
    if (mMediaRecorder != null) {
      try {
        mMediaRecorder.stop();
      } catch (IllegalStateException e) {
        Log.e(TAG, "Exception closing media recorder", e);
      }

      releaseMediaRecorder();

      if (recording != null) {
        Uri uri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, recording.mediaId);
        getContentResolver().update(uri, CallRecording.generateCompletedValues(), null, null);
      }

      mCurrentRecording = null;
    }
    return recording;
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    if (DBG) Log.d(TAG, "Destroying CallRecorderService");
    stopRecordingInternal();
  }

  static String generateFilename(String number, OutputFormat outputFormat) {
    String timestamp = DATE_FORMAT.format(new Date());

    if (TextUtils.isEmpty(number)) {
      number = "unknown";
    }

    // CallRecord_yyyyMMdd-HHmmss_numberextension.amr/m4a
    return "CallRecord_" + timestamp + "_" + number + outputFormat.extension;
  }

  private void releaseMediaRecorder() {
    Objects.requireNonNull(mMediaRecorder);
    try {
      mMediaRecorder.reset();
    } catch (Exception e) {
      Log.e(TAG, "unable to reset media recorder", e);
    }

    mMediaRecorder.release();

    mMediaRecorder = null;
  }
}
