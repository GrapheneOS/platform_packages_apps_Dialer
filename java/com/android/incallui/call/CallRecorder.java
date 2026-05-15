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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.XmlResourceParser;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
  private boolean initialized = false;
  private ICallRecorderService service = null;
  private CallRecordingCoordinator callRecordingCoordinator;
  // "Armed" means a call has been selected for recording, but recording has not started yet.
  // It starts only after both the recorder service is connected and that call becomes active.
  @Nullable private String armedRecordingCallId;
  private boolean armedRecordingStartedAutomatically;
  @Nullable private CallRecording activeRecording;
  private boolean shouldNotifyAutomaticRecordingStarted;
  private boolean waitingForPreferenceSnapshot;

  private CopyOnWriteArraySet<RecordingProgressListener> progressListeners =
      new CopyOnWriteArraySet<RecordingProgressListener>();
  private final Handler handler;

  private ServiceConnection connection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
      CallRecorder.this.service = ICallRecorderService.Stub.asInterface(service);
      maybeStartArmedRecording();
      if (callRecordingCoordinator != null) {
        callRecordingCoordinator.onRecorderServiceConnected();
      }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      onRecorderServiceDisconnected();
    }

    @Override
    public void onBindingDied(ComponentName name) {
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
    this(true /* addCallListListener */, new Handler());
  }

  @VisibleForTesting
  CallRecorder(boolean addCallListListener) {
    this(addCallListListener, new Handler(Looper.getMainLooper()));
  }

  @VisibleForTesting
  CallRecorder(boolean addCallListListener, Handler handler) {
    this.handler = handler;
    if (addCallListListener) {
      CallList.getInstance().addListener(this);
    }
  }

  @VisibleForTesting
  static void resetInstanceForTesting() {
    instance = null;
  }

  @VisibleForTesting
  void setContextForTesting(Context context) {
    this.context = context;
  }

  @VisibleForTesting
  void setServiceForTesting(ICallRecorderService service) {
    this.service = service;
  }

  @VisibleForTesting
  ICallRecorderService getServiceForTesting() {
    return service;
  }

  @VisibleForTesting
  void setInitializedForTesting(boolean initialized) {
    this.initialized = initialized;
  }

  @VisibleForTesting
  boolean isInitializedForTesting() {
    return initialized;
  }

  @VisibleForTesting
  CallRecordingCoordinator getCallRecordingCoordinatorForTesting() {
    return callRecordingCoordinator;
  }

  @VisibleForTesting
  void setRecordingStartedForTesting(boolean isRecordingStarted) {
    activeRecording =
        isRecordingStarted
            ? new CallRecording(
                "" /* phoneNumber */,
                1L /* creationTime */,
                "" /* fileName */,
                System.currentTimeMillis(),
                0L /* mediaId */)
            : null;
  }

  @VisibleForTesting
  void notifyRecordingStoppedForTesting() {
    notifyRecordingStopped();
  }

  public void setUp(Context context) {
    Context appContext =
        context.getApplicationContext() != null ? context.getApplicationContext() : context;
    // InCallService can bind again during a call; keep per call recording decisions.
    if (this.context != appContext || callRecordingCoordinator == null) {
      callRecordingCoordinator =
          new CallRecordingCoordinator(
              appContext,
              this,
              CallRecordingComponent.get(appContext).callRecordingDependencies());
    }
    this.context = appContext;
    maybeReinitialize();
  }

  private void initialize() {
    if (context == null) {
      return;
    }
    if (!initialized) {
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
      initialized = context.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
    }
  }

  private void uninitialize() {
    unbindRecorderService();
    handler.removeCallbacks(updateRecordingProgressTask);
    notifyRecordingStopped();
  }

  private void unbindRecorderService() {
    if (initialized && context != null) {
      try {
        context.unbindService(connection);
      } catch (IllegalArgumentException e) {
        Log.w(TAG, "Failed to unbind call recorder service", e);
      }
    }
    initialized = false;
    service = null;
  }

  private void maybeReinitialize() {
    if (context != null && CallList.getInstance().getActiveCall() != null) {
      initialize();
    }
  }

  public boolean startRecording(final String phoneNumber, final long creationTime) {
    return startRecording(phoneNumber, creationTime, false /* startedAutomatically */);
  }

  boolean startAutomaticRecording(final String phoneNumber, final long creationTime) {
    return startRecording(phoneNumber, creationTime, true /* startedAutomatically */);
  }

  public void setIncomingCallRecordingEnabled(String callId, boolean enabled) {
    if (callRecordingCoordinator != null) {
      callRecordingCoordinator.setIncomingCallRecordingEnabled(callId, enabled);
    }
  }

  private boolean startRecording(
      final String phoneNumber, final long creationTime, boolean startedAutomatically) {
    if (service == null) {
      return false;
    }

    try {
      if (!service.startRecording(phoneNumber, creationTime)) {
        Toast.makeText(context, R.string.call_recording_failed_message, Toast.LENGTH_SHORT)
            .show();
        return false;
      }
      armedRecordingCallId = null;
      armedRecordingStartedAutomatically = false;
      // The service owns file metadata returned by stopRecording(). The client copy is only for UI
      // state, timer progress, and matching the active call while the recording is running.
      activeRecording =
          new CallRecording(
              phoneNumber,
              creationTime,
              "" /* fileName */,
              System.currentTimeMillis(),
              0L /* mediaId */);
      shouldNotifyAutomaticRecordingStarted = startedAutomatically && progressListeners.isEmpty();
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

  boolean isServiceConnected() {
    return service != null;
  }

  public void armRecording(String callId, boolean startedAutomatically) {
    if (TextUtils.isEmpty(callId)) {
      return;
    }
    armedRecordingCallId = callId;
    armedRecordingStartedAutomatically = startedAutomatically;
    maybeStartArmedRecording();
  }

  public void clearArmedRecording() {
    armedRecordingCallId = null;
    armedRecordingStartedAutomatically = false;
  }

  public void disarmRecording(String callId) {
    if (TextUtils.equals(armedRecordingCallId, callId)) {
      clearArmedRecording();
    }
  }

  private void maybeStartArmedRecording() {
    if (service == null || TextUtils.isEmpty(armedRecordingCallId)) {
      return;
    }
    DialerCall call = CallList.getInstance().getCallById(armedRecordingCallId);
    if (call == null || call.getState() != DialerCallState.ACTIVE) {
      return;
    }
    startRecording(
        call.getNumber(), call.getCreationTimeMillis(), armedRecordingStartedAutomatically);
  }

  public boolean isRecording() {
    return activeRecording != null;
  }

  public CallRecording getActiveRecording() {
    return activeRecording;
  }

  public void finishRecording() {
    if (service != null) {
      try {
        final CallRecording recording = service.stopRecording();
        if (recording != null) {
          if (!TextUtils.isEmpty(recording.phoneNumber)) {
            String msg = context.getResources().getString(R.string.call_recording_file_location, recording.fileName);
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
          }
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
    service = null;
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
    if (activeRecording == null) {
      return;
    }
    activeRecording = null;
    shouldNotifyAutomaticRecordingStarted = false;
    for (RecordingProgressListener l : progressListeners) {
      l.onStopRecording();
    }
  }

  //
  // Call list listener methods.
  //
  @Override
  public void onIncomingCall(DialerCall call) { /* do nothing */ }

  @Override
  public void onCallListChange(final CallList callList) {
    if (context == null) {
      return;
    }
    if (!initialized && callList.getActiveCall() != null) {
      // we'll come here if this is the first active call
      initialize();
    } else {
      // we can come down this branch to resume a call that was on hold
      CallRecording active = getActiveRecording();
      if (active != null) {
        DialerCall call =
            callList.getCallWithStateAndNumber(DialerCallState.ONHOLD, active.phoneNumber);
        if (call != null) {
          // The call associated with the active recording has been placed
          // on hold, so stop the recording.
          finishRecording();
        }
      }
    }
    if (callRecordingCoordinator != null) {
      callRecordingCoordinator.onCallListChange(callList);
    }
    maybeStartArmedRecording();
  }

  @Override
  public void onDisconnect(final DialerCall call) {
    boolean hasActiveOrBackgroundCall = CallList.getInstance().getActiveOrBackgroundCall() != null;
    CallRecording active = getActiveRecording();
    if (active != null
        && (TextUtils.equals(call.getNumber(), active.phoneNumber) || !hasActiveOrBackgroundCall)) {
      // finish the current recording if the call gets disconnected
      finishRecording();
    }

    // tear down the service if there are no more active calls
    if (!hasActiveOrBackgroundCall) {
      uninitialize();
    }
    if (callRecordingCoordinator != null) {
      callRecordingCoordinator.onDisconnect(call);
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

  public void addRecordingProgressListener(RecordingProgressListener listener) {
    progressListeners.add(listener);
    notifyRecordingProgressListener(listener);
  }

  public void removeRecordingProgressListener(RecordingProgressListener listener) {
    progressListeners.remove(listener);
  }

  private void notifyRecordingProgressListener(RecordingProgressListener listener) {
    CallRecording active = getActiveRecording();
    if (active == null) {
      return;
    }

    boolean startedAutomatically = shouldNotifyAutomaticRecordingStarted;
    listener.onStartRecording(startedAutomatically);
    shouldNotifyAutomaticRecordingStarted = false;
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
}
