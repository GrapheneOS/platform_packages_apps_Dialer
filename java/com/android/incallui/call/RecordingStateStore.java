package com.android.incallui.call;

import android.support.annotation.Nullable;
import android.text.TextUtils;
import com.android.dialer.callrecord.CallRecording;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Stores recorder state that the UI needs to replay to late listeners.
 *
 * "Armed" means a recording should start once the matching call becomes recordable. "Active"
 * means the recorder service has already accepted a start request for a call.
 */
final class RecordingStateStore {
  // Replayable state listeners. Repeated registration must not replay again because some listeners
  // update UI that can re-enter registration.
  private final CopyOnWriteArraySet<CallRecorder.RecordingProgressListener> progressListeners =
      new CopyOnWriteArraySet<>();
  private final CopyOnWriteArraySet<CallRecorder.RecordingArmListener> recordingArmListeners =
      new CopyOnWriteArraySet<>();
  // Non-replayable event listeners. Automatic start drives a transient UI message, not durable
  // recording state.
  private final CopyOnWriteArraySet<CallRecorder.AutomaticRecordingStartListener>
      automaticStartListeners = new CopyOnWriteArraySet<>();

  @Nullable private ActiveRecording active;
  @Nullable private ArmedRecording armed;

  void arm(String callId, boolean startedAutomatically) {
    armed = new ArmedRecording(callId, startedAutomatically);
    for (CallRecorder.RecordingArmListener listener : recordingArmListeners) {
      listener.onRecordingArmed(callId, startedAutomatically);
    }
  }

  boolean disarm(String callId) {
    if (armed == null || !TextUtils.equals(armed.callId, callId)) {
      return false;
    }
    armed = null;
    for (CallRecorder.RecordingArmListener listener : recordingArmListeners) {
      listener.onRecordingDisarmed(callId);
    }
    return true;
  }

  boolean isArmed(String callId) {
    return armed != null && TextUtils.equals(armed.callId, callId);
  }

  void clearArmedRecording() {
    ArmedRecording currentArmedRecording = armed;
    if (currentArmedRecording != null) {
      disarm(currentArmedRecording.callId);
    }
  }

  void clearAutomaticArmedRecording() {
    ArmedRecording currentArmedRecording = armed;
    if (currentArmedRecording != null && currentArmedRecording.startedAutomatically) {
      disarm(currentArmedRecording.callId);
    }
  }

  @Nullable
  ArmedRecording getArmedRecording() {
    return armed;
  }

  void markStarted(
      @Nullable String callId, CallRecording recording, boolean startedAutomatically) {
    active = new ActiveRecording(callId, recording);
    armed = null;
    for (CallRecorder.RecordingProgressListener listener : progressListeners) {
      listener.onStartRecording();
    }
    if (startedAutomatically) {
      for (CallRecorder.AutomaticRecordingStartListener listener : automaticStartListeners) {
        listener.onAutomaticRecordingStarted();
      }
    }
  }

  boolean markStopped() {
    if (active == null) {
      return false;
    }
    active = null;
    for (CallRecorder.RecordingProgressListener listener : progressListeners) {
      listener.onStopRecording();
    }
    return true;
  }

  void addRecordingProgressListener(
      CallRecorder.RecordingProgressListener listener, @Nullable CallRecording activeRecording) {
    if (!progressListeners.add(listener) || activeRecording == null) {
      return;
    }
    listener.onStartRecording();
    listener.onRecordingTimeProgress(
        System.currentTimeMillis() - activeRecording.startRecordingTime);
  }

  void removeRecordingProgressListener(CallRecorder.RecordingProgressListener listener) {
    progressListeners.remove(listener);
  }

  void addRecordingArmListener(CallRecorder.RecordingArmListener listener) {
    if (!recordingArmListeners.add(listener) || armed == null) {
      return;
    }
    listener.onRecordingArmed(armed.callId, armed.startedAutomatically);
  }

  void removeRecordingArmListener(CallRecorder.RecordingArmListener listener) {
    recordingArmListeners.remove(listener);
  }

  void addAutomaticRecordingStartListener(CallRecorder.AutomaticRecordingStartListener listener) {
    automaticStartListeners.add(listener);
  }

  void removeAutomaticRecordingStartListener(
      CallRecorder.AutomaticRecordingStartListener listener) {
    automaticStartListeners.remove(listener);
  }

  void notifyRecordingTimeProgress(long elapsedTimeMs) {
    for (CallRecorder.RecordingProgressListener listener : progressListeners) {
      listener.onRecordingTimeProgress(elapsedTimeMs);
    }
  }

  @Nullable
  String getActiveCallId() {
    return active == null ? null : active.callId;
  }

  @Nullable
  CallRecording getActiveRecording() {
    return active == null ? null : active.recording;
  }

  private static final class ActiveRecording {
    // Private callers can have no number, so use the call id for call list transitions we observe.
    @Nullable final String callId;
    final CallRecording recording;

    ActiveRecording(@Nullable String callId, CallRecording recording) {
      this.callId = callId;
      this.recording = recording;
    }
  }

  static final class ArmedRecording {
    final String callId;
    final boolean startedAutomatically;

    ArmedRecording(String callId, boolean startedAutomatically) {
      this.callId = callId;
      this.startedAutomatically = startedAutomatically;
    }
  }
}
