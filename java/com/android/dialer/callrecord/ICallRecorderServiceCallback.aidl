package com.android.dialer.callrecord;

import com.android.dialer.callrecord.CallRecording;

/** Callback for asynchronous recorder service events. */
oneway interface ICallRecorderServiceCallback {
  /** The requested recording started. */
  void onRecordingStarted(long requestId);

  /** The requested recording could not be started. */
  void onRecordingStartFailed(long requestId);

  /** The active recording stopped and file finalization has completed. */
  void onRecordingStopped(long requestId, in CallRecording recording);

  /** The active recording failed asynchronously and is no longer running. */
  void onRecordingError(long requestId);
}
