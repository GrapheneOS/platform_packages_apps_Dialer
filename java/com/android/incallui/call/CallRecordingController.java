package com.android.incallui.call;

import android.content.Context;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.text.TextUtils;
import com.android.incallui.call.state.DialerCallState;

/** Owns call list events and call recording policy for incallui. */
public final class CallRecordingController
    implements CallList.Listener, CallRecorder.ServiceConnectionListener {

  private static CallRecordingController instance;

  private final CallRecorder recorder;
  @Nullable private final CallRecordingDependencies dependenciesForTesting;
  @Nullable private Context context;
  @Nullable private CallRecordingCoordinator coordinator;
  private boolean callListListenerRegistered;

  public static CallRecordingController getInstance() {
    if (instance == null) {
      instance = new CallRecordingController();
    }
    return instance;
  }

  private CallRecordingController() {
    this(new CallRecorder(), null);
  }

  @VisibleForTesting
  CallRecordingController(
      CallRecorder recorder, @Nullable CallRecordingDependencies dependenciesForTesting) {
    this.recorder = recorder;
    this.dependenciesForTesting = dependenciesForTesting;
  }

  @VisibleForTesting
  static void resetInstanceForTesting() {
    if (instance != null) {
      instance.tearDown();
      instance = null;
    }
  }

  public void setUp(Context context) {
    Context appContext =
        context.getApplicationContext() != null ? context.getApplicationContext() : context;
    boolean contextChanged = this.context != null && this.context != appContext;
    if (contextChanged && coordinator != null) {
      coordinator.destroy();
      coordinator = null;
    }
    this.context = appContext;
    recorder.setServiceConnectionListener(this);
    recorder.attachContext(appContext);
    if (coordinator == null) {
      coordinator =
          new CallRecordingCoordinator(
              appContext,
              recorder,
              dependenciesForTesting != null
                  ? dependenciesForTesting
                  : CallRecordingComponent.get(appContext).callRecordingDependencies());
    }
    CallList callList = CallList.getInstance();
    if (!callListListenerRegistered) {
      callList.addListener(this);
      callListListenerRegistered = true;
    } else {
      onCallListChange(callList);
    }
  }

  public void tearDown() {
    if (callListListenerRegistered) {
      CallList.getInstance().removeListener(this);
      callListListenerRegistered = false;
    }
    if (coordinator != null) {
      coordinator.destroy();
      coordinator = null;
    }
    recorder.setServiceConnectionListener(null);
    context = null;
  }

  public void setIncomingCallRecordingEnabled(String callId, boolean enabled) {
    if (coordinator != null) {
      coordinator.setIncomingCallRecordingEnabled(callId, enabled);
    }
  }

  public void startManualRecording(ManualRecordingRequest request) {
    if (coordinator != null) {
      coordinator.startManualRecording(request);
    }
  }

  public void cancelManualRecordingStart() {
    if (coordinator != null) {
      coordinator.cancelManualRecordingStart();
    }
  }

  public void onManualRecordingPermissionsResult(boolean allGranted) {
    if (coordinator != null) {
      coordinator.onManualRecordingPermissionsResult(allGranted);
    }
  }

  public void stopRecordingFromUi(@Nullable DialerCall call) {
    if (coordinator != null) {
      coordinator.stopRecordingFromUi(call);
      return;
    }
    if (call != null) {
      recorder.disarmRecording(call.getId());
    }
    if (recorder.isRecording()) {
      recorder.finishRecording();
    }
  }

  public void disarmRecording(String callId) {
    recorder.disarmRecording(callId);
  }

  public boolean isRecording() {
    return recorder.isRecording();
  }

  public boolean isRecordingArmed(String callId) {
    return recorder.isRecordingArmed(callId);
  }

  public void addRecordingProgressListener(CallRecorder.RecordingProgressListener listener) {
    recorder.addRecordingProgressListener(listener);
  }

  public void removeRecordingProgressListener(CallRecorder.RecordingProgressListener listener) {
    recorder.removeRecordingProgressListener(listener);
  }

  public void addAutomaticRecordingStartListener(
      CallRecorder.AutomaticRecordingStartListener listener) {
    recorder.addAutomaticRecordingStartListener(listener);
  }

  public void removeAutomaticRecordingStartListener(
      CallRecorder.AutomaticRecordingStartListener listener) {
    recorder.removeAutomaticRecordingStartListener(listener);
  }

  public void addRecordingArmListener(CallRecorder.RecordingArmListener listener) {
    recorder.addRecordingArmListener(listener);
  }

  public void removeRecordingArmListener(CallRecorder.RecordingArmListener listener) {
    recorder.removeRecordingArmListener(listener);
  }

  @Override
  public void onIncomingCall(DialerCall call) {
    onCallListChange(CallList.getInstance());
  }

  @Override
  public void onUpgradeToVideo(DialerCall call) {
    recorder.disarmRecording(call.getId());
    String activeCallId = recorder.getActiveRecordingCallId();
    if (TextUtils.equals(activeCallId, call.getId())
        || (activeCallId == null
            && call.getState() == DialerCallState.ACTIVE
            && recorder.isRecording())) {
      recorder.finishRecording();
    }
  }

  @Override
  public void onSessionModificationStateChange(DialerCall call) {}

  @Override
  public void onCallListChange(CallList callList) {
    if (context == null) {
      return;
    }
    if (coordinator != null) {
      coordinator.onCallListChange(callList);
    }
    if (!RecordingRules.hasOngoingCall(callList)) {
      recorder.unbindAndReset();
      return;
    }
    if (callList.getActiveCall() != null) {
      recorder.bindIfNeeded();
    }
    maybeStartArmedRecording(callList);
  }

  @Override
  public void onDisconnect(DialerCall call) {
    if (coordinator != null) {
      coordinator.onDisconnect(call);
    }
    if (CallList.getInstance().getActiveOrBackgroundCall() == null) {
      recorder.unbindAndReset();
    }
  }

  @Override
  public void onWiFiToLteHandover(DialerCall call) {}

  @Override
  public void onHandoverToWifiFailed(DialerCall call) {}

  @Override
  public void onInternationalCallOnWifi(DialerCall call) {}

  @Override
  public void onRecorderServiceConnected() {
    if (coordinator != null) {
      coordinator.onRecorderServiceConnected();
    }
    maybeStartArmedRecording(CallList.getInstance());
  }

  @Override
  public void onRecorderServiceRemoteException() {
    bindRecorderIfActiveCallExists();
  }

  private void bindRecorderIfActiveCallExists() {
    if (context != null && CallList.getInstance().getActiveCall() != null) {
      recorder.bindIfNeeded();
    }
  }

  private void maybeStartArmedRecording(CallList callList) {
    recorder.maybeStartArmedRecording(callList.getActiveCall());
  }
}
