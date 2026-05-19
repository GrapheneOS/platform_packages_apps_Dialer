package com.android.dialer.callrecord;

/** Callback for recorder service events that happen after a start command returns. */
oneway interface ICallRecorderServiceCallback {
  /** The active recording failed asynchronously and is no longer running. */
  void onRecordingError();
}
