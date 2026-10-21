package com.android.dialer.callrecord;

import com.android.dialer.callrecord.ICallRecorderServiceCallback;

/**
 * Service for recording phone calls.  Only one recording may be active at a time
 * (i.e. every call to startRecording should be followed by a call to stopRecording).
 */
interface ICallRecorderService {
  /** Registers callbacks for asynchronous recorder events. */
  void setCallback(ICallRecorderServiceCallback callback);

  /** Starts a recording. Completion is reported with the same request ID. */
  oneway void startRecording(long requestId, String phoneNumber, long creationTime);

  /**
   * Requests that the current recording stop.
   *
   * Completion, including finalized call recording data, is reported through
   * ICallRecorderServiceCallback so recorder teardown cannot block the caller.
   */
  oneway void stopRecording(long requestId);

  /**
   * Stops and deletes the current recording without publishing it.
   */
  oneway void discardRecording(long requestId);
}
