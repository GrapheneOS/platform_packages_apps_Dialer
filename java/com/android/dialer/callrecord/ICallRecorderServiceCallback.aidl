package com.android.dialer.callrecord;

import com.android.dialer.callrecord.CallRecording;

/** Callback for recorder service events that happen after a command returns. */
oneway interface ICallRecorderServiceCallback {
  /** The active recording stopped and file finalization has completed. */
  void onRecordingStopped(in CallRecording recording);

  /** The active recording failed asynchronously and is no longer running. */
  void onRecordingError();
}
