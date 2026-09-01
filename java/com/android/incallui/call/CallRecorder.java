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
import com.android.dialer.common.concurrent.DialerExecutorComponent;
import com.android.dialer.location.GeoUtil;
import com.android.incallui.call.state.DialerCallState;
import com.google.common.util.concurrent.ListenableFuture;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

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
  @Nullable private RecorderServiceListener recorderServiceListener;
  private final Handler handler;
  @Nullable private final Executor startExecutor;
  @Nullable private PendingStart pendingStart;
  // Stop completion is asynchronous; this also records whether completion should unbind.
  private StopDisposition stopDisposition = StopDisposition.NONE;

  private final ICallRecorderServiceCallback recorderServiceCallback =
      new ICallRecorderServiceCallback.Stub() {
        @Override
        public void onRecordingStopped(CallRecording recording) {
          handler.post(() -> onRecorderServiceRecordingStopped(recording));
        }

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
    this(new Handler(), new DefaultCallRecorderServiceBinding(), null);
  }

  @VisibleForTesting
  CallRecorder(Handler handler, CallRecorderServiceBinding serviceBinding) {
    this(handler, serviceBinding, Runnable::run);
  }

  @VisibleForTesting
  CallRecorder(
      Handler handler,
      CallRecorderServiceBinding serviceBinding,
      @Nullable Executor startExecutor) {
    this.handler = handler;
    this.serviceBinding = serviceBinding;
    this.startExecutor = startExecutor;
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
    // A new active call can arrive while a previous call's pending start is being stopped.
    if (pendingStart != null) {
      pendingStart.keepBinding();
    }
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
      pendingStart.cancelAndReset();
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
        pendingStart.cancel();
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
    Context currentContext = context;
    if (service == null || currentContext == null || pendingStart != null) {
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
    Executor executor =
        startExecutor != null
            ? startExecutor
            : DialerExecutorComponent.get(currentContext).backgroundExecutor();
    PendingStart start = new PendingStart(service, armedRecording, phoneNumber, creationTime);
    pendingStart = start;
    try {
      executor.execute(() -> startRecordingInBackground(start));
    } catch (RejectedExecutionException e) {
      pendingStart = null;
      Log.w(TAG, "Failed to dispatch recording start", e);
      return false;
    }
    return true;
  }

  private void startRecordingInBackground(PendingStart start) {
    // Most canceled requests are still queued here and never need to reach the recorder service.
    // Cancellation after this check is handled by discarding a successful late start below.
    if (!start.shouldPublish()) {
      handler.post(() -> onRecordingStartCanceled(start));
      return;
    }
    try {
      boolean started = start.service.startRecording(start.phoneNumber, start.creationTime);
      handler.post(() -> onRecordingStartCompleted(start, started));
    } catch (RemoteException e) {
      handler.post(() -> onRecordingStartRemoteException(start, e));
    } catch (RuntimeException e) {
      handler.post(() -> onRecordingStartRuntimeException(start, e));
    }
  }

  private void onRecordingStartCanceled(PendingStart start) {
    if (pendingStart != start) {
      return;
    }
    pendingStart = null;
    if (start.shouldReset()) {
      unbindNow();
    } else if (recorderServiceListener != null) {
      recorderServiceListener.onRecorderServiceIdle();
    }
  }

  private void onRecordingStartCompleted(PendingStart start, boolean started) {
    if (pendingStart != start) {
      return;
    }
    // Use request identity because the same call can receive a newer recording request.
    boolean recordingStillArmed = recordingState.getArmedRecording() == start.armedRecording;
    if (!started) {
      pendingStart = null;
      if (recordingStillArmed) {
        recordingState.disarm(start.armedRecording.callId);
        if (context != null) {
          Toast.makeText(context, R.string.call_recording_failed_message, Toast.LENGTH_SHORT)
              .show();
        }
      }
      if (start.shouldReset()) {
        unbindNow();
      } else if (recorderServiceListener != null) {
        recorderServiceListener.onRecorderServiceIdle();
      }
      return;
    }
    if (!recordingStillArmed) {
      start.cancel();
    }
    if (!start.shouldPublish()) {
      // Keep this pending until the stop callback so teardown cannot unbind a late recording.
      discardUnwantedRecording(start.service);
      return;
    }

    pendingStart = null;
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

  private void onRecordingStartRemoteException(PendingStart start, RemoteException exception) {
    if (pendingStart != start) {
      return;
    }
    pendingStart = null;
    Log.w(TAG, "Failed to start recording", exception);
    onRecorderServiceRemoteException();
  }

  private void onRecordingStartRuntimeException(PendingStart start, RuntimeException exception) {
    if (pendingStart != start) {
      return;
    }
    Log.w(TAG, "Recorder rejected recording start", exception);
    // A deterministic service failure should fail this request, not trigger an endless rebind and
    // retry cycle intended for a dead Binder. Discard in case it failed after starting its backend.
    if (recordingState.getArmedRecording() == start.armedRecording) {
      recordingState.disarm(start.armedRecording.callId);
      if (context != null) {
        Toast.makeText(context, R.string.call_recording_failed_message, Toast.LENGTH_SHORT).show();
      }
    }
    start.cancel();
    discardUnwantedRecording(start.service);
  }

  private void discardUnwantedRecording(ICallRecorderService service) {
    stopDisposition = StopDisposition.KEEP_BOUND;
    try {
      service.discardRecording();
    } catch (RemoteException e) {
      Log.w(TAG, "Failed to discard recording after late start", e);
      stopDisposition = StopDisposition.NONE;
      onRecorderServiceRemoteException();
    } catch (RuntimeException e) {
      Log.w(TAG, "Recorder failed to discard recording after late start", e);
      onRecorderServiceRecordingError();
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
        && pendingStart.shouldPublish()
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
      pendingStart.cancel();
      recordingState.disarm(pendingStart.armedRecording.callId);
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
        service.stopRecording();
      } catch (RemoteException e) {
        Log.w(TAG, "Failed to stop recording", e);
        stopDisposition = StopDisposition.NONE;
        onRecorderServiceRemoteException();
        return;
      }
    }

    // stopRecording() only asks the service to stop; file cleanup finishes later through the
    // callback. Clear local UI state now, but keep new starts blocked until cleanup completes.
    notifyRecordingStopped();
    handler.removeCallbacks(updateRecordingProgressTask);
    if (disposition == StopDisposition.UNBIND && stopDisposition == StopDisposition.NONE) {
      unbindNow();
    }
  }

  private void onRecorderServiceDisconnected() {
    // onServiceDisconnected means the recorder service process died while this client is still
    // bound. Treat it like a dead binder so the controller can bind again for the live call.
    onRecorderServiceRemoteException();
  }

  private void onRecorderServiceRecordingStopped(@Nullable CallRecording recording) {
    PendingStart start = pendingStart;
    boolean shouldUnbind =
        stopDisposition == StopDisposition.UNBIND || (start != null && start.shouldReset());
    pendingStart = null;
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
    if (recording != null && !TextUtils.isEmpty(recording.phoneNumber)) {
      String msg =
          context
              .getResources()
              .getString(R.string.call_recording_file_location, recording.fileName);
      Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
    }
  }

  private void onRecorderServiceRecordingError() {
    Log.w(TAG, "Recorder service reported recording error");
    PendingStart start = pendingStart;
    pendingStart = null;
    if (start != null && recordingState.getArmedRecording() == start.armedRecording) {
      recordingState.disarm(start.armedRecording.callId);
    }
    boolean wasStopPending = isRecordingStopPending();
    boolean shouldUnbind =
        stopDisposition == StopDisposition.UNBIND || (start != null && start.shouldReset());
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
    private enum Action {
      PUBLISH,
      DISCARD,
      DISCARD_AND_RESET
    }

    final ICallRecorderService service;
    final RecordingStateStore.ArmedRecording armedRecording;
    @Nullable final String phoneNumber;
    final long creationTime;
    private volatile Action action = Action.PUBLISH;

    PendingStart(
        ICallRecorderService service,
        RecordingStateStore.ArmedRecording armedRecording,
        @Nullable String phoneNumber,
        long creationTime) {
      this.service = service;
      this.armedRecording = armedRecording;
      this.phoneNumber = phoneNumber;
      this.creationTime = creationTime;
    }

    void cancel() {
      if (action == Action.PUBLISH) {
        action = Action.DISCARD;
      }
    }

    void cancelAndReset() {
      action = Action.DISCARD_AND_RESET;
    }

    void keepBinding() {
      if (action == Action.DISCARD_AND_RESET) {
        action = Action.DISCARD;
      }
    }

    boolean shouldPublish() {
      return action == Action.PUBLISH;
    }

    boolean shouldReset() {
      return action == Action.DISCARD_AND_RESET;
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
