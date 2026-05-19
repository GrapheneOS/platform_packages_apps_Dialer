package com.android.dialer.callrecord.impl;

import static com.google.common.truth.Truth.assertThat;

import android.content.Intent;
import android.media.MediaRecorder;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.dialer.callrecord.ICallRecorderService;
import com.android.dialer.callrecord.ICallRecorderServiceCallback;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CallRecorderServiceLifecycleTest {

  /** The legacy service can be destroyed without ever starting a recording. */
  @Test
  public void legacyOnDestroyWithoutRecorderLeavesRecorderNull() {
    CallRecorderService service = new CallRecorderService();

    service.onDestroy();

    assertThat(service.getMediaRecorderForTesting()).isNull();
  }

  /**
   * Keep the destruction path tolerant of partial recorder state even though start/stop are
   * synchronized and should not normally expose this interleaving.
   */
  @Test
  public void legacyOnDestroyStopsPartialRecorderWithoutRecordingMetadata() {
    CallRecorderService service = new CallRecorderService();
    MediaRecorder recorder = new MediaRecorder();
    service.setMediaRecorderForTesting(recorder);
    service.setCurrentRecordingForTesting(null);

    try {
      service.onDestroy();
      assertThat(service.getMediaRecorderForTesting()).isNull();
    } finally {
      MediaRecorder remainingRecorder = service.getMediaRecorderForTesting();
      if (remainingRecorder != null) {
        remainingRecorder.release();
      }
    }
  }

  @Test
  public void v2StopsFailedRecordingBackendWhenFailureIsObserved() throws Exception {
    CallRecorderServiceV2 service = new CallRecorderServiceV2();
    FailedRecordingBackend backend = new FailedRecordingBackend();
    service.setFailedRecordingCleanupExecutorForTesting(Runnable::run);
    service.setRecordingSessionForTesting(
        CallRecorderServiceV2.RecordingSession.partialForTesting(backend, null));

    assertThat(service.isRecordingForTesting()).isFalse();
    assertThat(service.getActiveRecordingForTesting()).isNull();
    assertThat(backend.stopCount()).isEqualTo(1);
    assertThat(backend.isClosed()).isTrue();
  }

  @Test
  public void v2DefersFailedRecordingBackendCleanup() throws Exception {
    CallRecorderServiceV2 service = new CallRecorderServiceV2();
    FailedRecordingBackend backend = new FailedRecordingBackend();
    AtomicReference<Runnable> cleanup = new AtomicReference<>();
    service.setFailedRecordingCleanupExecutorForTesting(cleanup::set);
    service.setRecordingSessionForTesting(
        CallRecorderServiceV2.RecordingSession.partialForTesting(backend, null));

    assertThat(service.isRecordingForTesting()).isFalse();
    assertThat(service.getActiveRecordingForTesting()).isNull();

    assertThat(cleanup.get()).isNotNull();
    assertThat(backend.stopCount()).isEqualTo(0);
    assertThat(backend.isClosed()).isFalse();

    cleanup.get().run();

    assertThat(backend.stopCount()).isEqualTo(1);
    assertThat(backend.isClosed()).isTrue();
  }

  @Test
  public void v2NotifiesCallbackWhenFailedRecordingBackendIsObserved() throws Exception {
    CallRecorderServiceV2 service = new CallRecorderServiceV2();
    FailedRecordingBackend backend = new FailedRecordingBackend();
    AtomicInteger errorCallbacks = new AtomicInteger();
    ICallRecorderService binder =
        ICallRecorderService.Stub.asInterface(service.onBind(new Intent()));
    binder.setCallback(
        new ICallRecorderServiceCallback.Stub() {
          @Override
          public void onRecordingError() {
            errorCallbacks.incrementAndGet();
          }
        });
    service.setFailedRecordingCleanupExecutorForTesting(Runnable::run);
    service.setRecordingSessionForTesting(
        CallRecorderServiceV2.RecordingSession.partialForTesting(backend, null));

    assertThat(service.isRecordingForTesting()).isFalse();

    assertThat(errorCallbacks.get()).isEqualTo(1);
  }

  private static final class FailedRecordingBackend implements RecordingBackend {
    private final Throwable failure = new IllegalStateException("async start failed");
    private int stopCount;
    private boolean closed;

    @Override
    public void startRecording() {
      throw new AssertionError("not used");
    }

    @Override
    public void stopRecordingBlocking() {
      stopCount++;
    }

    @Override
    public void setFailureListener(Runnable listener) {}

    @Override
    public boolean hasFailed() {
      return true;
    }

    @Override
    public Throwable getRecordingFailure() {
      return failure;
    }

    @Override
    public void close() {
      closed = true;
    }

    int stopCount() {
      return stopCount;
    }

    boolean isClosed() {
      return closed;
    }
  }
}
