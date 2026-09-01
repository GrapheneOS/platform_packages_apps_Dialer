package com.android.incallui.call;

import android.content.Context;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

/**
 * Java-facing entry point for call recording in incallui.
 *
 * This singleton can be requested before {@link #setUp(Context)}, so the context-dependent engine
 * exists only while the in-call service is bound.
 */
public final class CallRecordingController {

  private static CallRecordingController instance;

  private final CallRecorder recorder;
  @Nullable private final CallRecordingDependencies dependenciesForTesting;
  @Nullable private Context context;
  @Nullable private CallRecordingEngine engine;

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
    if (contextChanged && engine != null) {
      engine.destroy();
      engine = null;
    }
    this.context = appContext;
    if (engine == null) {
      engine =
          new CallRecordingEngine(
              appContext,
              recorder,
              dependenciesForTesting != null
                  ? dependenciesForTesting
                  : CallRecordingComponent.get(appContext).callRecordingDependencies());
    }
    engine.start();
  }

  public void tearDown() {
    if (engine != null) {
      engine.destroy();
      engine = null;
    } else {
      recorder.unbindAndReset();
      recorder.setRecorderServiceListener(null);
    }
    context = null;
  }

  public void setIncomingCallRecordingEnabled(String callId, boolean enabled) {
    if (engine != null) {
      engine.setIncomingCallRecordingEnabled(callId, enabled);
    }
  }

  public void startManualRecording(ManualRecordingRequest request) {
    if (engine != null) {
      engine.startManualRecording(request);
    }
  }

  public void cancelManualRecordingStart() {
    if (engine != null) {
      engine.cancelManualRecordingStart();
    }
  }

  public void onManualRecordingPermissionsResult(boolean allGranted) {
    if (engine != null) {
      engine.onManualRecordingPermissionsResult(allGranted);
    }
  }

  public void stopRecordingFromUi(@Nullable DialerCall call) {
    if (engine != null) {
      engine.stopRecordingFromUi(call);
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

  public void addRecordingErrorListener(CallRecorder.RecordingErrorListener listener) {
    recorder.addRecordingErrorListener(listener);
  }

  public void removeRecordingErrorListener(CallRecorder.RecordingErrorListener listener) {
    recorder.removeRecordingErrorListener(listener);
  }

  public void addRecordingArmListener(CallRecorder.RecordingArmListener listener) {
    recorder.addRecordingArmListener(listener);
  }

  public void removeRecordingArmListener(CallRecorder.RecordingArmListener listener) {
    recorder.removeRecordingArmListener(listener);
  }
}
