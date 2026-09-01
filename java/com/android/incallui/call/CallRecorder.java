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
import android.os.SystemClock;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service client and state publisher for call recording.
 *
 * Call recording policy lives in {@link CallRecordingEngine}. This class owns binding to the
 * recorder service, starting and stopping the remote recorder, and notifying UI listeners about
 * active or armed recordings.
 */
public class CallRecorder {
  private static final String TAG = "CallRecorder";

  static final String[] REQUIRED_PERMISSIONS = new String[] {
    android.Manifest.permission.RECORD_AUDIO,
  };

  private static final int UPDATE_INTERVAL = 500;
  // Controller recreation can replace the callback while an old request is still completing.
  // Elapsed realtime keeps new request IDs distinct across process recreation as well.
  private static final AtomicLong NEXT_REQUEST_ID =
      new AtomicLong(SystemClock.elapsedRealtimeNanos());

  private Context context;
  private final CallRecorderServiceBinding serviceBinding;
  @Nullable private ListenableFuture<CallRecordingPreferences> pendingPreferenceLoad;
  private final RecordingStateStore recordingState = new RecordingStateStore();
  @Nullable private RecorderServiceListener recorderServiceListener;
  private final Handler handler;
  @Nullable private PendingStart pendingStart;
  private long activeRequestId;
  // A non-NONE value means a stop or discard was sent and its callback is still pending.
  private StopDisposition stopDisposition = StopDisposition.NONE;

  private final ICallRecorderServiceCallback recorderServiceCallback =
      new ICallRecorderServiceCallback.Stub() {
        @Override
        public void onRecordingStarted(long requestId) {
          handler.post(() -> onRecorderServiceRecordingStarted(requestId));
        }

        @Override
        public void onRecordingStartFailed(long requestId) {
          handler.post(() -> onRecorderServiceRecordingStartFailed(requestId));
        }

        @Override
        public void onRecordingStopped(long requestId, CallRecording recording) {
          handler.post(() -> onRecorderServiceRecordingStopped(requestId, recording));
        }

        @Override
        public void onRecordingError(long requestId) {
          handler.post(() -> onRecorderServiceRecordingError(requestId));
        }
      };

  private final CallRecorderServiceBinding.Listener serviceBindingListener =
      new CallRecorderServiceBinding.Listener() {
        @Override
        public void onServiceConnected() {
          if (!registerRecorderServiceCallback()) {
            return;
          }
          if (recorderServiceListener != null) {
            recorderServiceListener.onRecorderServiceConnected();
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
   * The engine calls this during incallui setup. Binding is delayed until the call list has an
   * active call so service startup follows call lifecycle, not process setup.
   */
  void attachContext(Context context) {
    Context appContext =
        context.getApplicationContext() != null ? context.getApplicationContext() : context;
    this.context = appContext;
  }

  void bindIfNeeded() {
    // A new active call can arrive while a previous recorder request is being cleaned up.
    if (stopDisposition == StopDisposition.UNBIND) {
      stopDisposition = StopDisposition.KEEP_BOUND;
    }
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
    if (pendingPreferenceLoad != null || recorderServiceListener == null) {
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
              && recorderServiceListener != null
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
    recordingState.clearArmedRecording();
    if (pendingStart != null) {
      cancelPendingStart(true /* reset */);
      return;
    }
    if (stopDisposition != StopDisposition.NONE) {
      stopDisposition = StopDisposition.UNBIND;
      return;
    }
    if (isRecording()) {
      requestRecordingStop(StopDisposition.UNBIND);
      return;
    }
    unbindNow();
  }

  private void unbindNow() {
    activeRequestId = 0L;
    stopDisposition = StopDisposition.NONE;
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

  void setRecorderServiceListener(@Nullable RecorderServiceListener listener) {
    recorderServiceListener = listener;
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
    if (pendingStart != null) {
      if (!pendingStart.matches(currentArmedRecording, call)) {
        cancelPendingStart(false /* reset */);
      }
      return;
    }
    ICallRecorderService service = serviceBinding.getService();
    if (service == null
        || currentArmedRecording == null
        || isRecording()
        || isRecordingStopPending()) {
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
    if (!startRecording(call, currentArmedRecording)
        && serviceBinding.getService() != null
        && recordingState.getArmedRecording() == currentArmedRecording) {
      disarmRecording(currentArmedRecording.callId);
    }
  }

  private boolean startRecording(
      DialerCall call, RecordingStateStore.ArmedRecording armedRecording) {
    ICallRecorderService service = serviceBinding.getService();
    if (service == null || context == null || pendingStart != null) {
      return false;
    }
    if (isRecordingStopPending()) {
      Log.i(TAG, "Ignoring start while recording stop is pending");
      return false;
    }
    if (!registerRecorderServiceCallback()) {
      return false;
    }

    final String phoneNumber = call.getNumber();
    final long creationTime = call.getCreationTimeMillis();
    PendingStart start =
        new PendingStart(
            NEXT_REQUEST_ID.getAndIncrement(),
            service,
            armedRecording,
            phoneNumber,
            creationTime);
    pendingStart = start;
    try {
      service.startRecording(start.requestId, phoneNumber, creationTime);
    } catch (RemoteException e) {
      pendingStart = null;
      Log.w(TAG, "Failed to request recording start", e);
      onRecorderServiceRemoteException();
      return false;
    } catch (RuntimeException e) {
      Log.w(TAG, "Recorder rejected recording start", e);
      if (recordingState.getArmedRecording() == armedRecording) {
        recordingState.disarm(armedRecording.callId);
        if (context != null) {
          Toast.makeText(context, R.string.call_recording_failed_message, Toast.LENGTH_SHORT)
              .show();
        }
      }
      cancelPendingStart(false /* reset */);
    }
    return true;
  }

  private void onRecorderServiceRecordingStarted(long requestId) {
    PendingStart start = pendingStart;
    if (start == null || start.requestId != requestId) {
      return;
    }
    if (recordingState.getArmedRecording() != start.armedRecording) {
      cancelPendingStart(false /* reset */);
    }
    if (stopDisposition != StopDisposition.NONE) {
      return;
    }
    pendingStart = null;
    activeRequestId = requestId;
    // The service owns file metadata returned by stopRecording(). The client copy is only for UI
    // state, timer progress, and matching the active call while the recording is running.
    CallRecording activeRecording =
        new CallRecording(
            start.phoneNumber,
            start.creationTime,
            "" /* fileName */,
            System.currentTimeMillis(),
            0L /* mediaId */);
    recordingState.markStarted(
        start.armedRecording.callId, activeRecording, start.armedRecording.startedAutomatically);
    handler.removeCallbacks(updateRecordingProgressTask);
    updateRecordingProgressTask.run();
  }

  private void onRecorderServiceRecordingStartFailed(long requestId) {
    PendingStart start = pendingStart;
    if (start == null || start.requestId != requestId) {
      return;
    }
    pendingStart = null;
    boolean wasCanceled = stopDisposition != StopDisposition.NONE;
    boolean shouldUnbind = stopDisposition == StopDisposition.UNBIND;
    stopDisposition = StopDisposition.NONE;
    if (!wasCanceled
        && recordingState.getArmedRecording() == start.armedRecording) {
      recordingState.disarm(start.armedRecording.callId);
      if (context != null) {
        Toast.makeText(context, R.string.call_recording_failed_message, Toast.LENGTH_SHORT).show();
      }
    }
    if (shouldUnbind) {
      unbindNow();
    } else if (recorderServiceListener != null) {
      recorderServiceListener.onRecorderServiceIdle();
    }
  }

  private void cancelPendingStart(boolean reset) {
    PendingStart start = pendingStart;
    if (start == null) {
      return;
    }
    if (stopDisposition == StopDisposition.NONE) {
      stopDisposition = reset ? StopDisposition.UNBIND : StopDisposition.KEEP_BOUND;
      requestPendingStartDiscard(start);
    } else if (reset) {
      stopDisposition = StopDisposition.UNBIND;
    }
  }

  private void requestPendingStartDiscard(PendingStart start) {
    try {
      start.service.discardRecording(start.requestId);
    } catch (RemoteException e) {
      Log.w(TAG, "Failed to discard recording after late start", e);
      onRecorderServiceRemoteException();
    } catch (RuntimeException e) {
      Log.w(TAG, "Recorder failed to discard recording after late start", e);
      onRecorderServiceRecordingError(start.requestId);
    }
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
    if (isRecording() || isRecordingStopPending()) {
      return false;
    }
    RecordingStateStore.ArmedRecording armedRecording = recordingState.getArmedRecording();
    // Repeated requests are idempotent. A rearmed request replaces the pending one after cleanup.
    if (pendingStart != null
        && stopDisposition == StopDisposition.NONE
        && pendingStart.matches(armedRecording, call)) {
      return true;
    }
    armRecording(call.getId(), false /* startedAutomatically */);
    if (isServiceConnected()) {
      maybeStartArmedRecording(call);
    }
    return isRecordingArmed(call.getId())
        || (pendingStart != null
            && TextUtils.equals(pendingStart.armedRecording.callId, call.getId()))
        || TextUtils.equals(recordingState.getActiveCallId(), call.getId());
  }

  boolean isRecording() {
    return recordingState.getActiveRecording() != null;
  }

  boolean isRecordingStopPending() {
    return stopDisposition != StopDisposition.NONE;
  }

  CallRecording getActiveRecording() {
    return recordingState.getActiveRecording();
  }

  void finishRecording() {
    if (pendingStart != null) {
      recordingState.disarm(pendingStart.armedRecording.callId);
      cancelPendingStart(false /* reset */);
      return;
    }
    if (isRecordingStopPending()) {
      return;
    }
    requestRecordingStop(StopDisposition.KEEP_BOUND);
  }

  private void requestRecordingStop(StopDisposition disposition) {
    if (stopDisposition != StopDisposition.NONE) {
      if (disposition == StopDisposition.UNBIND) {
        stopDisposition = StopDisposition.UNBIND;
      }
      return;
    }
    ICallRecorderService service = serviceBinding.getService();
    if (service != null && recordingState.getActiveRecording() != null) {
      stopDisposition = disposition;
      try {
        service.stopRecording(activeRequestId);
      } catch (RemoteException e) {
        Log.w(TAG, "Failed to stop recording", e);
        stopDisposition = StopDisposition.NONE;
        onRecorderServiceRemoteException();
        return;
      } catch (RuntimeException e) {
        Log.w(TAG, "Recorder rejected recording stop", e);
        onRecorderServiceRecordingError(activeRequestId);
        return;
      }
    }

    // stopRecording() only asks the service to stop; file cleanup finishes later through the
    // callback. Clear local UI state now, but keep new starts blocked until cleanup completes.
    notifyRecordingStopped();
    handler.removeCallbacks(updateRecordingProgressTask);
    if (disposition == StopDisposition.UNBIND && stopDisposition == StopDisposition.NONE) {
      unbindNow();
    } else if (stopDisposition == StopDisposition.NONE) {
      activeRequestId = 0L;
    }
  }

  private void onRecorderServiceDisconnected() {
    // onServiceDisconnected means the recorder service process died while this client is still
    // bound. Treat it like a dead binder so the engine can bind again for the live call.
    onRecorderServiceRemoteException();
  }

  private void onRecorderServiceRecordingStopped(
      long requestId, @Nullable CallRecording recording) {
    PendingStart start =
        pendingStart != null && pendingStart.requestId == requestId ? pendingStart : null;
    boolean activeRequest = activeRequestId == requestId && requestId != 0L;
    if (start == null && !activeRequest) {
      return;
    }
    boolean shouldUnbind = stopDisposition == StopDisposition.UNBIND;
    if (start != null) {
      pendingStart = null;
    }
    if (activeRequest) {
      activeRequestId = 0L;
    }
    stopDisposition = StopDisposition.NONE;
    handler.removeCallbacks(updateRecordingProgressTask);
    notifyRecordingStopped();
    if (shouldUnbind) {
      unbindNow();
      return;
    }
    if (recorderServiceListener != null) {
      recorderServiceListener.onRecorderServiceIdle();
    }
    if (activeRequest && recording != null && !TextUtils.isEmpty(recording.phoneNumber)) {
      String msg =
          context
              .getResources()
              .getString(R.string.call_recording_file_location, recording.fileName);
      Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
    }
  }

  private void onRecorderServiceRecordingError(long requestId) {
    PendingStart start =
        pendingStart != null && pendingStart.requestId == requestId ? pendingStart : null;
    boolean activeRequest = activeRequestId == requestId && requestId != 0L;
    if (start == null && !activeRequest) {
      return;
    }
    Log.w(TAG, "Recorder service reported recording error");
    if (start != null) {
      pendingStart = null;
    }
    if (activeRequest) {
      activeRequestId = 0L;
    }
    if (start != null && recordingState.getArmedRecording() == start.armedRecording) {
      recordingState.disarm(start.armedRecording.callId);
    }
    boolean wasStopPending = isRecordingStopPending();
    boolean shouldUnbind = stopDisposition == StopDisposition.UNBIND;
    stopDisposition = StopDisposition.NONE;
    handler.removeCallbacks(updateRecordingProgressTask);
    boolean wasRecording = notifyRecordingStopped();
    if (!wasRecording && !wasStopPending && start == null) {
      return;
    }
    // finishRecording() clears active UI state before the async service callback arrives. A
    // pending stop error is still a failure of the recording that was just active.
    recordingState.notifyRecordingError();
    if (context != null) {
      Toast.makeText(context, R.string.call_recording_error_message, Toast.LENGTH_SHORT).show();
      CallRecordingErrorNotifier.show(context);
    }
    if (shouldUnbind) {
      unbindNow();
    } else if ((start != null || wasStopPending) && recorderServiceListener != null) {
      recorderServiceListener.onRecorderServiceIdle();
    }
  }

  private void onRecorderServiceRemoteException() {
    pendingStart = null;
    activeRequestId = 0L;
    stopDisposition = StopDisposition.NONE;
    unbindRecorderService();
    handler.removeCallbacks(updateRecordingProgressTask);
    notifyRecordingStopped();
    if (recorderServiceListener != null) {
      recorderServiceListener.onRecorderServiceRemoteException();
    }
  }

  private boolean notifyRecordingStopped() {
    return recordingState.markStopped();
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

  public interface RecordingErrorListener {
    void onRecordingError();
  }

  public interface RecordingArmListener {
    void onRecordingArmed(String callId, boolean startedAutomatically);
    void onRecordingDisarmed(String callId);
  }

  interface RecorderServiceListener {
    void onRecorderServiceConnected();

    void onRecorderServiceIdle();

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

  void addRecordingErrorListener(RecordingErrorListener listener) {
    recordingState.addRecordingErrorListener(listener);
  }

  void removeRecordingErrorListener(RecordingErrorListener listener) {
    recordingState.removeRecordingErrorListener(listener);
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

  private static final class PendingStart {
    final long requestId;
    final ICallRecorderService service;
    final RecordingStateStore.ArmedRecording armedRecording;
    @Nullable final String phoneNumber;
    final long creationTime;

    PendingStart(
        long requestId,
        ICallRecorderService service,
        RecordingStateStore.ArmedRecording armedRecording,
        @Nullable String phoneNumber,
        long creationTime) {
      this.requestId = requestId;
      this.service = service;
      this.armedRecording = armedRecording;
      this.phoneNumber = phoneNumber;
      this.creationTime = creationTime;
    }

    boolean matches(@Nullable RecordingStateStore.ArmedRecording armed, @Nullable DialerCall call) {
      return armed == armedRecording
          && call != null
          && TextUtils.equals(call.getId(), armedRecording.callId)
          && call.getState() == DialerCallState.ACTIVE
          && !call.isVideoCall();
    }
  }

  private enum StopDisposition {
    NONE,
    KEEP_BOUND,
    UNBIND
  }
}
