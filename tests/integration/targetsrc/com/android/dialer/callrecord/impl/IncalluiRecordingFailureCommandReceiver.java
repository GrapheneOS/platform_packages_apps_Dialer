package com.android.dialer.callrecord.impl;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.Nullable;

/** Test-only command receiver compiled into DialerForTesting's incallui process. */
public final class IncalluiRecordingFailureCommandReceiver extends BroadcastReceiver {
  public static final String ACTION_PREPARE_NEXT_RECORDING_FAILURE =
      "com.android.dialer.callrecord.PREPARE_NEXT_RECORDING_FAILURE";
  public static final String ACTION_PREPARE_NEXT_RECORDING_FINISH_FAILURE =
      "com.android.dialer.callrecord.PREPARE_NEXT_RECORDING_FINISH_FAILURE";
  public static final String ACTION_REPORT_ACTIVE_RECORDING_FAILURE =
      "com.android.dialer.callrecord.REPORT_ACTIVE_RECORDING_FAILURE";
  public static final String ACTION_CLEAR_RECORDING_FAILURE =
      "com.android.dialer.callrecord.CLEAR_RECORDING_FAILURE";

  @Nullable private static FailureReportingBackend backend;

  @Override
  public void onReceive(Context context, Intent intent) {
    String action = intent.getAction();
    if (ACTION_PREPARE_NEXT_RECORDING_FAILURE.equals(action)) {
      backend = new FailureReportingBackend(false /* failOnStop */);
      CallRecorderServiceV2.setNextRecordingBackendForTesting(backend);
    } else if (ACTION_PREPARE_NEXT_RECORDING_FINISH_FAILURE.equals(action)) {
      backend = new FailureReportingBackend(true /* failOnStop */);
      CallRecorderServiceV2.setNextRecordingBackendForTesting(backend);
    } else if (ACTION_REPORT_ACTIVE_RECORDING_FAILURE.equals(action)) {
      FailureReportingBackend currentBackend = backend;
      if (currentBackend == null) {
        throw new IllegalStateException("No active recording to fail");
      }
      currentBackend.fail();
    } else if (ACTION_CLEAR_RECORDING_FAILURE.equals(action)) {
      backend = null;
      CallRecorderServiceV2.setNextRecordingBackendForTesting(null);
    } else {
      throw new IllegalArgumentException("Unknown incallui command: " + action);
    }
  }

  private static final class FailureReportingBackend implements RecordingBackend {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final boolean failOnStop;
    @Nullable private Runnable failureListener;
    @Nullable private Throwable failure;
    private boolean closed;

    FailureReportingBackend(boolean failOnStop) {
      this.failOnStop = failOnStop;
    }

    @Override
    public void startRecording() {}

    @Override
    public synchronized void stopRecordingBlocking() {
      if (failOnStop) {
        IllegalStateException stopFailure =
            new IllegalStateException("forced recording finish failure for testing");
        failure = stopFailure;
        throw stopFailure;
      }
      closed = true;
    }

    @Override
    public synchronized void setFailureListener(@Nullable Runnable listener) {
      failureListener = listener;
    }

    @Override
    public synchronized boolean hasFailed() {
      return failure != null;
    }

    @Override
    @Nullable
    public synchronized Throwable getRecordingFailure() {
      return failure;
    }

    @Override
    public synchronized void close() {
      closed = true;
    }

    void fail() {
      handler.post(
          () -> {
            final Runnable listener;
            synchronized (this) {
              if (closed || failure != null) {
                return;
              }
              failure = new IllegalStateException("forced recording failure for testing");
              listener = failureListener;
            }
            if (listener != null) {
              listener.run();
            }
          });
    }
  }
}
