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

package com.android.incallui.call;

import android.content.Context;
import android.content.Intent;
import android.content.res.XmlResourceParser;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.v4.os.UserManagerCompat;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.android.dialer.R;
import com.android.dialer.callrecord.CallRecording;
import com.android.dialer.callrecord.CallRecordingPreferences;
import com.android.dialer.callrecord.CallRecordingPreferencesStore;
import com.android.dialer.callrecord.ICallRecorderService;
import com.android.dialer.callrecord.ICallRecorderServiceCallback;
import com.android.dialer.callrecord.impl.CallRecorderService;
import com.android.dialer.callrecord.impl.CallRecorderServiceV2;
import com.android.dialer.common.LogUtil;
import com.android.dialer.location.GeoUtil;
import com.android.incallui.call.state.DialerCallState;
import com.google.common.util.concurrent.ListenableFuture;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;

/**
 * Service client and state publisher for call recording.
 *
 * Call recording policy lives in {@link CallRecordingController} and {@link
 * CallRecordingCoordinator}. This class owns binding to the recorder service, starting and
 * stopping the remote recorder, and notifying UI listeners about active or armed recordings.
 */
public class CallRecorder {
  private static final String TAG = "CallRecorder";

  static final String[] REQUIRED_PERMISSIONS = new String[] {
    android.Manifest.permission.RECORD_AUDIO,
  };

  private static final int UPDATE_INTERVAL = 500;

  private Context context;
  private final CallRecorderServiceBinding serviceBinding;
  @Nullable private ListenableFuture<CallRecordingPreferences> pendingPreferenceLoad;
  private final RecordingStateStore recordingState = new RecordingStateStore();
  @Nullable private ServiceConnectionListener serviceConnectionListener;
  private final Handler handler;

  private final ICallRecorderServiceCallback recorderServiceCallback =
      new ICallRecorderServiceCallback.Stub() {
        @Override
        public void onRecordingError() {
          handler.post(CallRecorder.this::onRecorderServiceRecordingError);
        }
      };

  private final CallRecorderServiceBinding.Listener serviceBindingListener =
      new CallRecorderServiceBinding.Listener() {
        @Override
        public void onServiceConnected() {
          if (!registerRecorderServiceCallback()) {
            return;
          }
          if (serviceConnectionListener != null) {
            serviceConnectionListener.onRecorderServiceConnected();
          }
        }

        @Override
        public void onServiceDisconnected() {
          onRecorderServiceDisconnected();
        }

        @Override
        public void onBindingDied() {
          onRecorderServiceRemoteException();
        }
      };

  CallRecorder() {
    this(new Handler(), new DefaultCallRecorderServiceBinding());
  }

  @VisibleForTesting
  CallRecorder(Handler handler, CallRecorderServiceBinding serviceBinding) {
    this.handler = handler;
    this.serviceBinding = serviceBinding;
  }

  /**
   * Attaches the app Context without binding.
   *
   * The controller calls this during incallui setup. Binding is delayed until the call list has an
   * active call so service startup follows call lifecycle, not process setup.
   */
  void attachContext(Context context) {
    Context appContext =
        context.getApplicationContext() != null ? context.getApplicationContext() : context;
    this.context = appContext;
  }

  void bindIfNeeded() {
    if (context == null) {
      return;
    }
    // The recorder service writes to credential encrypted media storage.
    if (!UserManagerCompat.isUserUnlocked(context)) {
      return;
    }
    if (!serviceBinding.isBound()) {
      loadPreferencesBeforeBinding();
    }
  }

  private void loadPreferencesBeforeBinding() {
    if (pendingPreferenceLoad != null || serviceConnectionListener == null) {
      return;
    }
    final Context loadContext = context;
    final ListenableFuture<CallRecordingPreferences> future =
        CallRecordingPreferencesStore.loadAsync(loadContext);
    pendingPreferenceLoad = future;
    // Java service binding still uses callbacks. DataStore keeps its own in-memory cache, so this
    // Future bridge waits for the cached Flow value without introducing a separate preferences
    // snapshot in CallRecorder.
    // TODO: If incallui moves to coroutine lifecycles, replace this Future callback with a suspend
    // setup path.
    CallRecordingPreferencesStore.addLoadCallback(
        future,
        handler::post,
        result -> {
          boolean isCurrentLoad = pendingPreferenceLoad == future;
          if (isCurrentLoad) {
            pendingPreferenceLoad = null;
          }
          if (isCurrentLoad
              && context == loadContext
              && serviceConnectionListener != null
              && !serviceBinding.isBound()) {
            bindRecorderService(result);
          }
        },
        t -> {
          if (pendingPreferenceLoad == future) {
            pendingPreferenceLoad = null;
          }
          LogUtil.e(TAG + ".bindIfNeeded", "failed to load call recording preferences", t);
        });
  }

  private void bindRecorderService(CallRecordingPreferences preferences) {
    if (context == null || serviceBinding.isBound()) {
      return;
    }
    final Class<?> serviceClass = recorderServiceClass(preferences);
    LogUtil.i(
        TAG + ".bindIfNeeded",
        "Using Call Recording V2: %b",
        serviceClass == CallRecorderServiceV2.class);
    serviceBinding.bind(
        context, new Intent(context, serviceClass), serviceBindingListener);
  }

  static Class<?> recorderServiceClass(CallRecordingPreferences preferences) {
    // CallRecorder waits for DataStore before binding. Keep service selection tied to that loaded
    // proto instead of reading a global snapshot again during bind.
    return preferences.getUseCallRecordingV2()
        ? CallRecorderServiceV2.class
        : CallRecorderService.class;
  }

  void unbindAndReset() {
    pendingPreferenceLoad = null;
    unbindRecorderService();
    handler.removeCallbacks(updateRecordingProgressTask);
    notifyRecordingStopped();
  }

  private void unbindRecorderService() {
    if (context != null) {
      clearRecorderServiceCallback();
      serviceBinding.unbind(context);
    }
  }

  private boolean registerRecorderServiceCallback() {
    ICallRecorderService service = serviceBinding.getService();
    if (service == null) {
      return false;
    }
    try {
      service.setCallback(recorderServiceCallback);
      return true;
    } catch (RemoteException e) {
      Log.w(TAG, "Failed to register recorder service callback", e);
      onRecorderServiceRemoteException();
      return false;
    }
  }

  private void clearRecorderServiceCallback() {
    ICallRecorderService service = serviceBinding.getService();
    if (service == null) {
      return;
    }
    try {
      service.setCallback(null);
    } catch (RemoteException e) {
      Log.w(TAG, "Failed to clear recorder service callback", e);
    }
  }

  void setServiceConnectionListener(@Nullable ServiceConnectionListener listener) {
    serviceConnectionListener = listener;
  }

  void armRecording(String callId, boolean startedAutomatically) {
    if (TextUtils.isEmpty(callId)) {
      return;
    }
    // "Armed" means this call should be recorded once the recorder service is connected and the
    // call becomes active. No audio is captured and no timer starts while a recording is armed.
    recordingState.arm(callId, startedAutomatically);
  }

  void disarmRecording(String callId) {
    if (TextUtils.isEmpty(callId)) {
      return;
    }
    recordingState.disarm(callId);
  }

  boolean isRecordingArmed(String callId) {
    return recordingState.isArmed(callId);
  }

  void clearArmedRecording() {
    recordingState.clearArmedRecording();
  }

  void clearAutomaticArmedRecording() {
    recordingState.clearAutomaticArmedRecording();
  }

  void maybeStartArmedRecording(@Nullable DialerCall call) {
    RecordingStateStore.ArmedRecording currentArmedRecording = recordingState.getArmedRecording();
    ICallRecorderService service = serviceBinding.getService();
    if (service == null || currentArmedRecording == null || isRecording()) {
      return;
    }
    if (call == null
        || !TextUtils.equals(call.getId(), currentArmedRecording.callId)
        || call.getState() != DialerCallState.ACTIVE
        || call.isVideoCall()) {
      return;
    }
    // An armed request is a pending start for one call. If starting fails while the service is
    // still connected, clear only that same pending start; a later call event may have replaced it.
    if (!startRecording(call, currentArmedRecording.startedAutomatically)
        && serviceBinding.getService() != null
        && recordingState.getArmedRecording() == currentArmedRecording) {
      disarmRecording(currentArmedRecording.callId);
    }
  }

  private boolean startRecording(
      DialerCall call, boolean startedAutomatically) {
    ICallRecorderService service = serviceBinding.getService();
    if (service == null) {
      return false;
    }
    if (!registerRecorderServiceCallback()) {
      return false;
    }

    final String phoneNumber = call.getNumber();
    final long creationTime = call.getCreationTimeMillis();
    try {
      if (!service.startRecording(phoneNumber, creationTime)) {
        Toast.makeText(context, R.string.call_recording_failed_message, Toast.LENGTH_SHORT)
            .show();
        return false;
      }
      // The service owns file metadata returned by stopRecording(). The client copy is only for UI
      // state, timer progress, and matching the active call while the recording is running.
      CallRecording activeRecording =
          new CallRecording(
              phoneNumber,
              creationTime,
              "" /* fileName */,
              System.currentTimeMillis(),
              0L /* mediaId */);
      recordingState.markStarted(call.getId(), activeRecording, startedAutomatically);
      handler.removeCallbacks(updateRecordingProgressTask);
      updateRecordingProgressTask.run();
      return true;
    } catch (RemoteException e) {
      Log.w(TAG, "Failed to start recording", e);
      onRecorderServiceRemoteException();
    }

    return false;
  }

  boolean isServiceConnected() {
    return serviceBinding.getService() != null;
  }

  boolean startOrArmManualRecording(DialerCall call) {
    if (call.getState() == DialerCallState.CONNECTING
        || DialerCallState.isDialing(call.getState())) {
      armRecording(call.getId(), false /* startedAutomatically */);
      return true;
    }
    if (call.getState() != DialerCallState.ACTIVE) {
      return false;
    }
    if (isServiceConnected()) {
      return startRecording(call, false /* startedAutomatically */);
    }
    armRecording(call.getId(), false /* startedAutomatically */);
    return true;
  }

  boolean isRecording() {
    return recordingState.getActiveRecording() != null;
  }

  CallRecording getActiveRecording() {
    return recordingState.getActiveRecording();
  }

  void finishRecording() {
    ICallRecorderService service = serviceBinding.getService();
    if (service != null) {
      try {
        final CallRecording recording = service.stopRecording();
        if (recording != null && !TextUtils.isEmpty(recording.phoneNumber)) {
          String msg =
              context
                  .getResources()
                  .getString(R.string.call_recording_file_location, recording.fileName);
          Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
        }
      } catch (RemoteException e) {
        Log.w(TAG, "Failed to stop recording", e);
        onRecorderServiceRemoteException();
      }
    }

    notifyRecordingStopped();
    handler.removeCallbacks(updateRecordingProgressTask);
  }

  private void onRecorderServiceDisconnected() {
    // onServiceDisconnected means the recorder service process died while this client is still
    // bound. Treat it like a dead binder so the controller can bind again for the live call.
    onRecorderServiceRemoteException();
  }

  private void onRecorderServiceRecordingError() {
    Log.w(TAG, "Recorder service reported recording error");
    handler.removeCallbacks(updateRecordingProgressTask);
    notifyRecordingStopped();
    if (context != null) {
      Toast.makeText(context, R.string.call_recording_failed_message, Toast.LENGTH_SHORT).show();
    }
  }

  private void onRecorderServiceRemoteException() {
    unbindRecorderService();
    handler.removeCallbacks(updateRecordingProgressTask);
    notifyRecordingStopped();
    if (serviceConnectionListener != null) {
      serviceConnectionListener.onRecorderServiceRemoteException();
    }
  }

  private void notifyRecordingStopped() {
    recordingState.markStopped();
  }

  // allow clients to listen for recording progress updates
  public interface RecordingProgressListener {
    void onStartRecording();
    void onStopRecording();
    void onRecordingTimeProgress(long elapsedTimeMs);
  }

  public interface AutomaticRecordingStartListener {
    void onAutomaticRecordingStarted();
  }

  public interface RecordingArmListener {
    void onRecordingArmed(String callId, boolean startedAutomatically);
    void onRecordingDisarmed(String callId);
  }

  interface ServiceConnectionListener {
    void onRecorderServiceConnected();

    void onRecorderServiceRemoteException();
  }

  @Nullable
  String getActiveRecordingCallId() {
    return recordingState.getActiveCallId();
  }

  void addRecordingProgressListener(RecordingProgressListener listener) {
    recordingState.addRecordingProgressListener(listener, getActiveRecording());
  }

  void removeRecordingProgressListener(RecordingProgressListener listener) {
    recordingState.removeRecordingProgressListener(listener);
  }

  void addRecordingArmListener(RecordingArmListener listener) {
    recordingState.addRecordingArmListener(listener);
  }

  void removeRecordingArmListener(RecordingArmListener listener) {
    recordingState.removeRecordingArmListener(listener);
  }

  void addAutomaticRecordingStartListener(AutomaticRecordingStartListener listener) {
    recordingState.addAutomaticRecordingStartListener(listener);
  }

  void removeAutomaticRecordingStartListener(AutomaticRecordingStartListener listener) {
    recordingState.removeAutomaticRecordingStartListener(listener);
  }

  private Runnable updateRecordingProgressTask = new Runnable() {
    @Override
    public void run() {
      CallRecording active = getActiveRecording();
      if (active != null) {
        long elapsed = System.currentTimeMillis() - active.startRecordingTime;
        recordingState.notifyRecordingTimeProgress(elapsed);
        handler.postDelayed(this, UPDATE_INTERVAL);
      } else {
        notifyRecordingStopped();
      }
    }
  };
}
