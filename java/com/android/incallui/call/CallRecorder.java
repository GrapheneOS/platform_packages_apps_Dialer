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
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.android.dialer.R;
import com.android.dialer.callrecord.CallRecording;
import com.android.dialer.callrecord.CallRecordingPreferencesStore;
import com.android.dialer.callrecord.ICallRecorderService;
import com.android.dialer.callrecord.impl.CallRecorderService;
import com.android.dialer.callrecord.impl.CallRecorderServiceV2;
import com.android.dialer.common.LogUtil;
import com.android.dialer.location.GeoUtil;
import com.android.incallui.call.state.DialerCallState;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * InCall UI's interface to the call recorder
 *
 * Manages the call recorder service lifecycle.  We bind to the service whenever an active call
 * is established, and unbind when all calls have been disconnected.
 */
public class CallRecorder implements CallList.Listener {
  public static final String TAG = "CallRecorder";

  public static final String[] REQUIRED_PERMISSIONS = new String[] {
    android.Manifest.permission.RECORD_AUDIO,
  };

  private static final int UPDATE_INTERVAL = 500;

  private static CallRecorder instance = null;
  private Context context;
  private final CallRecorderServiceBinding serviceBinding;
  private CallRecordingCoordinator callRecordingCoordinator;
  private boolean waitingForPreferenceSnapshot;
  private final RecordingState recordingState = new RecordingState();

  private CopyOnWriteArraySet<RecordingProgressListener> progressListeners =
      new CopyOnWriteArraySet<RecordingProgressListener>();
  private CopyOnWriteArraySet<RecordingArmListener> recordingArmListeners =
      new CopyOnWriteArraySet<RecordingArmListener>();
  private final Handler handler;

  private final CallRecorderServiceBinding.Listener serviceBindingListener =
      new CallRecorderServiceBinding.Listener() {
        @Override
        public void onServiceConnected() {
          maybeStartArmedRecording();
          if (callRecordingCoordinator != null) {
            callRecordingCoordinator.onRecorderServiceConnected();
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

  public static CallRecorder getInstance() {
    if (instance == null) {
      instance = new CallRecorder();
    }
    return instance;
  }

  private CallRecorder() {
    this(
        true /* addCallListListener */,
        new Handler(),
        new DefaultCallRecorderServiceBinding());
  }

  @VisibleForTesting
  CallRecorder(
      boolean addCallListListener, Handler handler, CallRecorderServiceBinding serviceBinding) {
    this.handler = handler;
    this.serviceBinding = serviceBinding;
    if (addCallListListener) {
      CallList.getInstance().addListener(this);
    }
  }

  @VisibleForTesting
  static void resetInstanceForTesting() {
    instance = null;
  }

  private void setContext(Context context) {
    Context appContext =
        context.getApplicationContext() != null ? context.getApplicationContext() : context;
    // InCallService can bind again during a call; keep per call recording decisions.
    if (this.context != appContext || callRecordingCoordinator == null) {
      if (callRecordingCoordinator != null) {
        callRecordingCoordinator.destroy();
      }
      callRecordingCoordinator =
          new CallRecordingCoordinator(
              appContext,
              this,
              CallRecordingComponent.get(appContext).callRecordingDependencies());
    }
    this.context = appContext;
  }

  public void setUp(Context context) {
    setContext(context);
    maybeReinitialize();
  }

  private void initialize() {
    if (context == null) {
      return;
    }
    if (!serviceBinding.isBound()) {
      if (!CallRecordingPreferencesStore.isSnapshotReady(context)) {
        if (!waitingForPreferenceSnapshot) {
          waitingForPreferenceSnapshot = true;
          // The bound service class is chosen once per call. Refresh the DataStore snapshot
          // asynchronously so the main thread call path does not block before binding V1/V2.
          CallRecordingPreferencesStore.runWhenSnapshotReady(
              context,
              handler::post,
              "CallRecorder.initialize",
              () -> {
                waitingForPreferenceSnapshot = false;
                maybeReinitialize();
              },
              () -> waitingForPreferenceSnapshot = false);
        }
        return;
      }
      final boolean v2Enabled = CallRecorderServiceV2.isV2Enabled(context);
      LogUtil.i(TAG + ".initialize", "Using Call Recording V2: %b", v2Enabled);
      Intent serviceIntent = new Intent(context, v2Enabled ? CallRecorderServiceV2.class
              : CallRecorderService.class);
      serviceBinding.bind(context, serviceIntent, serviceBindingListener);
    }
  }

  private void uninitialize() {
    unbindRecorderService();
    handler.removeCallbacks(updateRecordingProgressTask);
    notifyRecordingStopped();
  }

  private void unbindRecorderService() {
    if (context != null) {
      serviceBinding.unbind(context);
    }
  }

  private void maybeReinitialize() {
    if (context != null && CallList.getInstance().getActiveCall() != null) {
      initialize();
    }
  }

  public void setIncomingCallRecordingEnabled(String callId, boolean enabled) {
    if (callRecordingCoordinator != null) {
      callRecordingCoordinator.setIncomingCallRecordingEnabled(callId, enabled);
    }
  }

  public void setCallRecordingDisabledByUser(String callId, boolean disabled) {
    if (disabled) {
      disarmRecording(callId);
    }
    if (callRecordingCoordinator != null) {
      callRecordingCoordinator.setCallRecordingDisabledByUser(callId, disabled);
    }
  }

  public void startManualRecording(ManualRecordingRequest request) {
    if (callRecordingCoordinator != null) {
      callRecordingCoordinator.startManualRecording(request);
    }
  }

  public void cancelManualRecordingStart() {
    if (callRecordingCoordinator != null) {
      callRecordingCoordinator.cancelManualRecordingStart();
    }
  }

  public void onManualRecordingPermissionsResult(boolean allGranted) {
    if (callRecordingCoordinator != null) {
      callRecordingCoordinator.onManualRecordingPermissionsResult(allGranted);
    }
  }

  public void stopRecordingFromUi(DialerCall call) {
    if (callRecordingCoordinator != null) {
      callRecordingCoordinator.stopRecordingFromUi(call);
      return;
    }
    if (call != null) {
      disarmRecording(call.getId());
    }
    if (isRecording()) {
      finishRecording();
    }
  }

  public void armRecording(String callId, boolean startedAutomatically) {
    if (TextUtils.isEmpty(callId)) {
      return;
    }
    // "Armed" means this call should be recorded once the recorder service is connected and the
    // call becomes active. No audio is captured and no timer starts while a recording is armed.
    recordingState.arm(callId, startedAutomatically);
    for (RecordingArmListener l : recordingArmListeners) {
      l.onRecordingArmed(callId, startedAutomatically);
    }
    maybeStartArmedRecording();
  }

  public void disarmRecording(String callId) {
    if (TextUtils.isEmpty(callId)) {
      return;
    }
    if (recordingState.disarm(callId)) {
      for (RecordingArmListener l : recordingArmListeners) {
        l.onRecordingDisarmed(callId);
      }
    }
  }

  public boolean isRecordingArmed(String callId) {
    return recordingState.isArmed(callId);
  }

  void clearArmedRecording() {
    ArmedRecording armed = recordingState.getArmed();
    if (armed != null) {
      disarmRecording(armed.callId);
    }
  }

  void clearAutomaticArmedRecording() {
    ArmedRecording armed = recordingState.getArmed();
    if (armed != null && armed.startedAutomatically) {
      disarmRecording(armed.callId);
    }
  }

  private void maybeStartArmedRecording() {
    ArmedRecording currentArmedRecording = recordingState.getArmed();
    ICallRecorderService service = serviceBinding.getService();
    if (service == null || currentArmedRecording == null || isRecording()) {
      return;
    }
    DialerCall call = CallList.getInstance().getCallById(currentArmedRecording.callId);
    if (call == null
        || call.getState() != DialerCallState.ACTIVE
        || call.isVideoCall()
        || !canStartArmedRecording(currentArmedRecording)) {
      return;
    }
    if (!startRecording(call, currentArmedRecording.startedAutomatically)
        && serviceBinding.getService() != null
        && recordingState.getArmed() == currentArmedRecording) {
      disarmRecording(currentArmedRecording.callId);
    }
  }

  private boolean startRecording(DialerCall call, boolean startedAutomatically) {
    return startRecording(
        call.getNumber(), call.getCreationTimeMillis(), startedAutomatically, call.getId());
  }

  public boolean startRecording(final String phoneNumber, final long creationTime) {
    return startRecording(phoneNumber, creationTime, false /* startedAutomatically */, null);
  }

  private boolean startRecording(
      final String phoneNumber,
      final long creationTime,
      boolean startedAutomatically,
      @Nullable String callId) {
    ICallRecorderService service = serviceBinding.getService();
    if (service == null) {
      return false;
    }

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
      recordingState.markStarted(
          callId, activeRecording, startedAutomatically, progressListeners.isEmpty());
      for (RecordingProgressListener l : progressListeners) {
        l.onStartRecording(startedAutomatically);
      }
      handler.removeCallbacks(updateRecordingProgressTask);
      updateRecordingProgressTask.run();
      return true;
    } catch (RemoteException e) {
      Log.w(TAG, "Failed to start recording", e);
      onRecorderServiceRemoteException();
    }

    return false;
  }

  public boolean isServiceConnected() {
    return serviceBinding.getService() != null;
  }

  public boolean startOrArmManualRecording(DialerCall call) {
    if (DialerCallState.isDialing(call.getState())) {
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

  public boolean isRecording() {
    return recordingState.getActiveRecording() != null;
  }

  public CallRecording getActiveRecording() {
    return recordingState.getActiveRecording();
  }

  public void finishRecording() {
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
    handler.removeCallbacks(updateRecordingProgressTask);
    notifyRecordingStopped();
  }

  private void onRecorderServiceRemoteException() {
    unbindRecorderService();
    handler.removeCallbacks(updateRecordingProgressTask);
    notifyRecordingStopped();
    maybeReinitialize();
  }

  private void notifyRecordingStopped() {
    if (!recordingState.markStopped()) {
      return;
    }
    for (RecordingProgressListener l : progressListeners) {
      l.onStopRecording();
    }
  }

  //
  // Call list listener methods.
  //
  @Override
  public void onIncomingCall(DialerCall call) {
    onCallListChange(CallList.getInstance());
  }

  @Override
  public void onCallListChange(final CallList callList) {
    if (context == null) {
      return;
    }
    if (!serviceBinding.isBound() && callList.getActiveCall() != null) {
      // we'll come here if this is the first active call
      initialize();
    }
    if (callRecordingCoordinator != null) {
      callRecordingCoordinator.onCallListChange(callList);
    }
    maybeStartArmedRecording();
  }

  @Override
  public void onDisconnect(final DialerCall call) {
    if (callRecordingCoordinator != null) {
      callRecordingCoordinator.onDisconnect(call);
    }
    boolean hasActiveOrBackgroundCall = CallList.getInstance().getActiveOrBackgroundCall() != null;

    // tear down the service if there are no more active calls
    if (!hasActiveOrBackgroundCall) {
      uninitialize();
    }
  }

  @Override
  public void onUpgradeToVideo(DialerCall call) { /* do nothing */ }

  @Override
  public void onSessionModificationStateChange(DialerCall call) { /* do nothing */ }

  @Override
  public void onWiFiToLteHandover(DialerCall call) { /* do nothing */ }

  @Override
  public void onHandoverToWifiFailed(DialerCall call) { /* do nothing */ }

  @Override
  public void onInternationalCallOnWifi(DialerCall call) { /* do nothing */ }

  // allow clients to listen for recording progress updates
  public interface RecordingProgressListener {
    void onStartRecording(boolean startedAutomatically);
    void onStopRecording();
    void onRecordingTimeProgress(long elapsedTimeMs);
  }

  public interface RecordingArmListener {
    void onRecordingArmed(String callId, boolean startedAutomatically);
    void onRecordingDisarmed(String callId);
  }

  @Nullable
  String getActiveRecordingCallId() {
    return recordingState.getActiveCallId();
  }

  private boolean canStartArmedRecording(ArmedRecording armedRecording) {
    return callRecordingCoordinator == null
        || callRecordingCoordinator.canStartArmedRecording(
            armedRecording.callId, armedRecording.startedAutomatically);
  }

  public void addRecordingProgressListener(RecordingProgressListener listener) {
    progressListeners.add(listener);
    notifyRecordingProgressListener(listener);
  }

  public void removeRecordingProgressListener(RecordingProgressListener listener) {
    progressListeners.remove(listener);
  }

  public void addRecordingArmListener(RecordingArmListener listener) {
    recordingArmListeners.add(listener);
    ArmedRecording armed = recordingState.getArmed();
    if (armed != null) {
      listener.onRecordingArmed(armed.callId, armed.startedAutomatically);
    }
  }

  public void removeRecordingArmListener(RecordingArmListener listener) {
    recordingArmListeners.remove(listener);
  }

  private void notifyRecordingProgressListener(RecordingProgressListener listener) {
    CallRecording active = getActiveRecording();
    if (active == null) {
      return;
    }

    boolean startedAutomatically = recordingState.consumePendingStartedAutomatically();
    listener.onStartRecording(startedAutomatically);
    listener.onRecordingTimeProgress(System.currentTimeMillis() - active.startRecordingTime);
  }

  private Runnable updateRecordingProgressTask = new Runnable() {
    @Override
    public void run() {
      CallRecording active = getActiveRecording();
      if (active != null) {
        long elapsed = System.currentTimeMillis() - active.startRecordingTime;
        for (RecordingProgressListener l : progressListeners) {
          l.onRecordingTimeProgress(elapsed);
        }
        handler.postDelayed(this, UPDATE_INTERVAL);
      } else {
        notifyRecordingStopped();
      }
    }
  };

  private static final class RecordingState {
    @Nullable private ActiveRecording active;
    @Nullable private ArmedRecording armed;

    void arm(String callId, boolean startedAutomatically) {
      armed = new ArmedRecording(callId, startedAutomatically);
    }

    boolean disarm(String callId) {
      if (armed == null || !TextUtils.equals(armed.callId, callId)) {
        return false;
      }
      armed = null;
      return true;
    }

    boolean isArmed(String callId) {
      return armed != null && TextUtils.equals(armed.callId, callId);
    }

    @Nullable
    ArmedRecording getArmed() {
      return armed;
    }

    void markStarted(
        @Nullable String callId,
        CallRecording recording,
        boolean startedAutomatically,
        boolean replayStartToNewListener) {
      active =
          new ActiveRecording(
              callId, recording, replayStartToNewListener, startedAutomatically);
      armed = null;
    }

    boolean markStopped() {
      if (active == null) {
        return false;
      }
      active = null;
      return true;
    }

    boolean consumePendingStartedAutomatically() {
      if (active == null || !active.replayStartToNewListener) {
        return false;
      }
      active.replayStartToNewListener = false;
      return active.startedAutomatically;
    }

    @Nullable
    String getActiveCallId() {
      return active == null ? null : active.callId;
    }

    @Nullable
    CallRecording getActiveRecording() {
      return active == null ? null : active.recording;
    }
  }

  private static final class ActiveRecording {
    // Private callers can have no number, so use the call id for call list transitions we observe.
    @Nullable final String callId;
    final CallRecording recording;
    boolean replayStartToNewListener;
    final boolean startedAutomatically;

    ActiveRecording(
        @Nullable String callId,
        CallRecording recording,
        boolean replayStartToNewListener,
        boolean startedAutomatically) {
      this.callId = callId;
      this.recording = recording;
      this.replayStartToNewListener = replayStartToNewListener;
      this.startedAutomatically = startedAutomatically;
    }
  }

  private static final class ArmedRecording {
    final String callId;
    final boolean startedAutomatically;

    ArmedRecording(String callId, boolean startedAutomatically) {
      this.callId = callId;
      this.startedAutomatically = startedAutomatically;
    }
  }
}
