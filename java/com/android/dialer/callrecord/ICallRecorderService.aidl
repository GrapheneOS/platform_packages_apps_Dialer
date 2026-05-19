package com.android.dialer.callrecord;

import com.android.dialer.callrecord.CallRecording;
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
   * stops the current recording
   *
   * @return call recording data including the output filename
   */
  CallRecording stopRecording();
}
