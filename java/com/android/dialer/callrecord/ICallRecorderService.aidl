package com.android.dialer.callrecord;

import com.android.dialer.callrecord.ICallRecorderServiceCallback;

/**
 * Service for recording phone calls.  Only one recording may be active at a time
 * (i.e. every call to startRecording should be followed by a call to stopRecording).
 */
interface ICallRecorderService {
  /**
   * Registers callbacks for recorder events that happen after a command returns.
   */
  void setCallback(ICallRecorderServiceCallback callback);

  /**
   * Start a recording.
   *
   * @return true if recording started successfully
   */
  boolean startRecording(String phoneNumber, long creationTime);

  /**
   * Requests that the current recording stop.
   *
   * Completion, including finalized call recording data, is reported through
   * ICallRecorderServiceCallback so same process service teardown cannot block the UI thread.
   */
  void stopRecording();
}
