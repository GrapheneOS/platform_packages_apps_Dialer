package com.android.dialer.callrecord.impl;

import android.support.annotation.Nullable;

/**
 * Narrow recorder contract kept separate so service failure handling can be tested without audio
 * I/O.
 */
interface RecordingBackend extends AutoCloseable {
  /** Returns only after the recorder is ready to produce audio. */
  void startRecording();

  void stopRecordingBlocking();

  void setFailureListener(@Nullable Runnable listener);

  boolean hasFailed();

  @Nullable
  Throwable getRecordingFailure();

  @Override
  void close();
}
