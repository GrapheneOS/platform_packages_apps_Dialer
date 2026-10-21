package com.android.dialer.callrecord.impl;

import static com.google.common.truth.Truth.assertThat;

import android.content.Intent;
import android.media.MediaRecorder;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.dialer.callrecord.CallRecording;
import com.android.dialer.callrecord.ICallRecorderService;
import com.android.dialer.callrecord.ICallRecorderServiceCallback;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CallRecorderServiceLifecycleTest {
  private static final long REQUEST_ID = 17L;

  @Test
  public void startThenDiscardCompletesInRequestOrder() throws Exception {
    TestRecorderService service = new TestRecorderService();
    ArrayDeque<Runnable> commands = new ArrayDeque<>();
    AtomicInteger startedCallbacks = new AtomicInteger();
    AtomicInteger stoppedCallbacks = new AtomicInteger();
    service.setRecorderCommandExecutorForTesting(commands::add);
    ICallRecorderService binder =
        ICallRecorderService.Stub.asInterface(service.onBind(new Intent()));
    binder.setCallback(
        new NoOpRecorderServiceCallback() {
          @Override
          public void onRecordingStarted(long requestId) {
            assertThat(requestId).isEqualTo(REQUEST_ID);
            startedCallbacks.incrementAndGet();
          }

          @Override
          public void onRecordingStopped(long requestId, CallRecording recording) {
            assertThat(requestId).isEqualTo(REQUEST_ID);
            stoppedCallbacks.incrementAndGet();
          }
        });

    binder.startRecording(REQUEST_ID, "+15551234567", 1234L);
    binder.discardRecording(REQUEST_ID);

    assertThat(service.startCount).isEqualTo(0);
    assertThat(service.stopCount).isEqualTo(0);
    assertThat(commands).hasSize(2);

    commands.remove().run();

    assertThat(service.startCount).isEqualTo(1);
    assertThat(service.stopCount).isEqualTo(0);
    assertThat(startedCallbacks.get()).isEqualTo(1);

    commands.remove().run();

    assertThat(service.stopCount).isEqualTo(1);
    assertThat(stoppedCallbacks.get()).isEqualTo(1);
  }

  /** The legacy service can be destroyed without ever starting a recording. */
  @Test
  public void legacyOnDestroyWithoutRecorderLeavesRecorderNull() {
    CallRecorderService service = new CallRecorderService();
    service.setRecorderCommandExecutorForTesting(Runnable::run);

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
    service.setRecorderCommandExecutorForTesting(Runnable::run);
    service.setRecorderCleanupExecutorForTesting(Runnable::run);
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
  public void v2RecordingErrorCleansUpRecorder() throws Exception {
    CallRecorderServiceV2 service = new CallRecorderServiceV2();
    FailedRecordingBackend backend = new FailedRecordingBackend();
    service.setRecorderCleanupExecutorForTesting(Runnable::run);
    service.setRecordingSessionForTesting(
        CallRecorderServiceV2.RecordingSession.partialForTesting(REQUEST_ID, backend, null));

    assertThat(service.isRecordingForTesting()).isFalse();
    assertThat(service.getActiveRecordingForTesting()).isNull();
    assertThat(backend.stopCount()).isEqualTo(1);
    assertThat(backend.isClosed()).isTrue();
  }

  @Test
  public void v2RecordingErrorDefersRecorderCleanup() throws Exception {
    CallRecorderServiceV2 service = new CallRecorderServiceV2();
    FailedRecordingBackend backend = new FailedRecordingBackend();
    AtomicReference<Runnable> cleanup = new AtomicReference<>();
    service.setRecorderCleanupExecutorForTesting(cleanup::set);
    service.setRecordingSessionForTesting(
        CallRecorderServiceV2.RecordingSession.partialForTesting(REQUEST_ID, backend, null));

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
  public void v2RecordingErrorNotifiesCallback() throws Exception {
    CallRecorderServiceV2 service = new CallRecorderServiceV2();
    FailedRecordingBackend backend = new FailedRecordingBackend();
    AtomicInteger errorCallbacks = new AtomicInteger();
    ICallRecorderService binder =
        ICallRecorderService.Stub.asInterface(service.onBind(new Intent()));
    binder.setCallback(
        new NoOpRecorderServiceCallback() {
          @Override
          public void onRecordingError(long requestId) {
            assertThat(requestId).isEqualTo(REQUEST_ID);
            errorCallbacks.incrementAndGet();
          }
        });
    service.setRecorderCleanupExecutorForTesting(Runnable::run);
    service.setRecordingSessionForTesting(
        CallRecorderServiceV2.RecordingSession.partialForTesting(REQUEST_ID, backend, null));

    assertThat(service.isRecordingForTesting()).isFalse();

    assertThat(errorCallbacks.get()).isEqualTo(1);
  }

  @Test
  public void v2RecorderFailureNotifiesErrorAndCleansUp() throws Exception {
    CallRecorderServiceV2 service = new CallRecorderServiceV2();
    FailedRecordingBackend backend = new FailedRecordingBackend(false /* failed */);
    AtomicInteger errorCallbacks = new AtomicInteger();
    ICallRecorderService binder =
        ICallRecorderService.Stub.asInterface(service.onBind(new Intent()));
    binder.setCallback(
        new NoOpRecorderServiceCallback() {
          @Override
          public void onRecordingError(long requestId) {
            assertThat(requestId).isEqualTo(REQUEST_ID);
            errorCallbacks.incrementAndGet();
          }
        });
    service.setRecorderCleanupExecutorForTesting(Runnable::run);
    service.setRecordingSessionForTesting(
        CallRecorderServiceV2.RecordingSession.partialForTesting(REQUEST_ID, backend, null));

    backend.reportFailure();

    assertThat(service.isRecordingForTesting()).isFalse();
    assertThat(errorCallbacks.get()).isEqualTo(1);
    assertThat(backend.stopCount()).isEqualTo(1);
    assertThat(backend.isClosed()).isTrue();
  }

  @Test
  public void v2StopRecordingReportsErrorWhenRecorderFailsToFinishRecording() throws Exception {
    CallRecorderServiceV2 service = new CallRecorderServiceV2();
    FailedRecordingBackend backend =
        new FailedRecordingBackend(false /* failed */, true /* failOnStop */);
    AtomicInteger errorCallbacks = new AtomicInteger();
    AtomicInteger stoppedCallbacks = new AtomicInteger();
    ICallRecorderService binder =
        ICallRecorderService.Stub.asInterface(service.onBind(new Intent()));
    binder.setCallback(
        new NoOpRecorderServiceCallback() {
          @Override
          public void onRecordingStopped(long requestId, CallRecording recording) {
            stoppedCallbacks.incrementAndGet();
          }

          @Override
          public void onRecordingError(long requestId) {
            assertThat(requestId).isEqualTo(REQUEST_ID);
            errorCallbacks.incrementAndGet();
          }
        });
    service.setRecorderCommandExecutorForTesting(Runnable::run);
    service.setRecorderCleanupExecutorForTesting(Runnable::run);
    service.setRecordingSessionForTesting(
        CallRecorderServiceV2.RecordingSession.partialForTesting(REQUEST_ID, backend, null));

    binder.stopRecording(REQUEST_ID);

    assertThat(service.isRecordingForTesting()).isFalse();
    assertThat(errorCallbacks.get()).isEqualTo(1);
    assertThat(stoppedCallbacks.get()).isEqualTo(0);
    assertThat(backend.stopCount()).isEqualTo(1);
    assertThat(backend.isClosed()).isTrue();
  }

  @Test
  public void v2StopRecordingReportsStoppedAfterDeferredCleanup() throws Exception {
    CallRecorderServiceV2 service = new CallRecorderServiceV2();
    FailedRecordingBackend backend = new FailedRecordingBackend(false /* failed */);
    AtomicReference<Runnable> cleanup = new AtomicReference<>();
    AtomicInteger stoppedCallbacks = new AtomicInteger();
    ICallRecorderService binder =
        ICallRecorderService.Stub.asInterface(service.onBind(new Intent()));
    binder.setCallback(
        new NoOpRecorderServiceCallback() {
          @Override
          public void onRecordingStopped(long requestId, CallRecording recording) {
            assertThat(requestId).isEqualTo(REQUEST_ID);
            stoppedCallbacks.incrementAndGet();
          }
        });
    service.setRecorderCommandExecutorForTesting(Runnable::run);
    service.setRecorderCleanupExecutorForTesting(cleanup::set);
    service.setRecordingSessionForTesting(
        CallRecorderServiceV2.RecordingSession.partialForTesting(REQUEST_ID, backend, null));

    binder.stopRecording(REQUEST_ID);

    assertThat(service.isRecordingForTesting()).isTrue();
    assertThat(cleanup.get()).isNotNull();
    assertThat(backend.stopCount()).isEqualTo(0);
    assertThat(stoppedCallbacks.get()).isEqualTo(0);

    cleanup.get().run();

    assertThat(service.isRecordingForTesting()).isFalse();
    assertThat(backend.stopCount()).isEqualTo(1);
    assertThat(backend.isClosed()).isTrue();
    assertThat(stoppedCallbacks.get()).isEqualTo(1);
  }

  private abstract static class NoOpRecorderServiceCallback
      extends ICallRecorderServiceCallback.Stub {
    @Override
    public void onRecordingStarted(long requestId) {}

    @Override
    public void onRecordingStartFailed(long requestId) {}

    @Override
    public void onRecordingStopped(long requestId, CallRecording recording) {}

    @Override
    public void onRecordingError(long requestId) {}
  }

  private static final class TestRecorderService extends AbstractCallRecorderService {
    private int startCount;
    private int stopCount;

    @Override
    public android.os.IBinder onBind(Intent intent) {
      return getRecorderServiceBinder();
    }

    @Override
    protected String getLogTag() {
      return "TestRecorderService";
    }

    @Override
    protected boolean startRecordingInternal(
        long requestId, String phoneNumber, long creationTime) {
      startCount++;
      return true;
    }

    @Override
    protected void stopRecordingAsync(long requestId, boolean completeRecording) {
      stopCount++;
      notifyRecordingStopped(requestId, null);
    }
  }

  private static final class FailedRecordingBackend implements RecordingBackend {
    private final Throwable failure = new IllegalStateException("async start failed");
    private final boolean failOnStop;
    private boolean failed;
    private Runnable failureListener;
    private int stopCount;
    private boolean closed;

    FailedRecordingBackend() {
      this(true /* failed */);
    }

    FailedRecordingBackend(boolean failed) {
      this(failed, false /* failOnStop */);
    }

    FailedRecordingBackend(boolean failed, boolean failOnStop) {
      this.failed = failed;
      this.failOnStop = failOnStop;
    }

    @Override
    public void startRecording() {
      throw new AssertionError("not used");
    }

    @Override
    public void stopRecordingBlocking() {
      stopCount++;
      if (failOnStop) {
        failed = true;
        throw new IllegalStateException("finish failed");
      }
    }

    @Override
    public void setFailureListener(Runnable listener) {
      failureListener = listener;
    }

    @Override
    public boolean hasFailed() {
      return failed;
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

    void reportFailure() {
      failed = true;
      if (failureListener == null) {
        throw new AssertionError("failure listener was not registered");
      }
      failureListener.run();
    }
  }
}
