package com.android.incallui.call;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.DeadObjectException;
import android.os.RemoteException;
import android.text.TextUtils;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.dialer.callrecord.CallRecording;
import com.android.dialer.callrecord.ICallRecorderService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CallRecorderLifecycleTest {

  @Before
  public void setUp() {
    CallRecorder.resetInstanceForTesting();
  }

  @After
  public void tearDown() {
    CallRecorder.resetInstanceForTesting();
  }

  /**
   * The user can tap the recording button off, or disconnect handling can stop an active recording
   * through finishRecording().
   */
  @Test
  public void finishRecordingStopsServiceAndNotifiesStopped() {
    CallRecorder recorder = new CallRecorder(false /* addCallListListener */);
    FakeRecorderService service =
        new FakeRecorderService(
            new CallRecording(
                "" /* phoneNumber */,
                1L /* creationTime */,
                "CallRecord_19700101-000001_unknown.m4a",
                2L /* startRecordingTime */,
                3L /* mediaId */));
    AtomicInteger stopCallbacks = new AtomicInteger();
    recorder.setContextForTesting(InstrumentationRegistry.getTargetContext());
    recorder.setServiceForTesting(service);
    recorder.setRecordingStartedForTesting(true);
    recorder.addRecordingProgressListener(
        new NoOpRecordingProgressListener() {
          @Override
          public void onStopRecording() {
            stopCallbacks.incrementAndGet();
          }
        });

    recorder.finishRecording();

    assertThat(service.stopCount).isEqualTo(1);
    assertThat(service.isRecordingForTesting()).isFalse();
    assertThat(stopCallbacks.get()).isEqualTo(1);
  }

  /**
   * A dead recorder service binder can fail while the UI still believes recording is active.
   */
  @Test
  public void getActiveRecordingRemoteExceptionUnbindsAndStopsRecordingState() {
    CallRecorder recorder = new CallRecorder(false /* addCallListListener */);
    TrackingContext context = new TrackingContext();
    AtomicInteger stopCallbacks = new AtomicInteger();
    recorder.setContextForTesting(context);
    recorder.setInitializedForTesting(true);
    recorder.setServiceForTesting(new ThrowingRecorderService());
    recorder.setRecordingStartedForTesting(true);
    recorder.addRecordingProgressListener(
        new NoOpRecordingProgressListener() {
          @Override
          public void onStopRecording() {
            stopCallbacks.incrementAndGet();
          }
        });

    assertThat(recorder.getActiveRecording()).isNull();

    assertThat(context.unbindCount).isEqualTo(1);
    assertThat(recorder.isInitializedForTesting()).isFalse();
    assertThat(recorder.getServiceForTesting()).isNull();
    assertThat(stopCallbacks.get()).isEqualTo(1);
  }

  private static class NoOpRecordingProgressListener
      implements CallRecorder.RecordingProgressListener {
    @Override
    public void onStartRecording(boolean startedAutomatically) {}

    @Override
    public void onStopRecording() {}

    @Override
    public void onRecordingTimeProgress(long elapsedTimeMs) {}
  }

  private static final class TrackingContext extends ContextWrapper {
    int unbindCount;

    TrackingContext() {
      super(InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    @Override
    public Context getApplicationContext() {
      return this;
    }

    @Override
    public void unbindService(ServiceConnection conn) {
      unbindCount++;
    }

    @Override
    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
      return true;
    }
  }

  private static final class FakeRecorderService extends ICallRecorderService.Stub {
    private CallRecording activeRecording;
    private int stopCount;

    FakeRecorderService(CallRecording activeRecording) {
      this.activeRecording = activeRecording;
    }

    @Override
    public boolean startRecording(String phoneNumber, long creationTime) {
      activeRecording =
          new CallRecording(
              phoneNumber,
              creationTime,
              TextUtils.isEmpty(phoneNumber) ? "unknown.m4a" : phoneNumber + ".m4a",
              System.currentTimeMillis(),
              1L /* mediaId */);
      return true;
    }

    @Override
    public CallRecording stopRecording() {
      stopCount++;
      CallRecording recording = activeRecording;
      activeRecording = null;
      return recording;
    }

    @Override
    public boolean isRecording() {
      return isRecordingForTesting();
    }

    @Override
    public CallRecording getActiveRecording() {
      return activeRecording;
    }

    boolean isRecordingForTesting() {
      return activeRecording != null;
    }
  }

  private static final class ThrowingRecorderService extends ICallRecorderService.Stub {
    @Override
    public boolean startRecording(String phoneNumber, long creationTime) throws RemoteException {
      throw new DeadObjectException();
    }

    @Override
    public CallRecording stopRecording() throws RemoteException {
      throw new DeadObjectException();
    }

    @Override
    public boolean isRecording() throws RemoteException {
      throw new DeadObjectException();
    }

    @Override
    public CallRecording getActiveRecording() throws RemoteException {
      throw new DeadObjectException();
    }
  }
}
