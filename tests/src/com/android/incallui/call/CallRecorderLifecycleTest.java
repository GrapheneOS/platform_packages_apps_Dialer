package com.android.incallui.call;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.text.TextUtils;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.dialer.callrecord.CallRecording;
import com.android.dialer.callrecord.ICallRecorderService;
import com.android.incallui.call.state.DialerCallState;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CallRecorderLifecycleTest {

  @Before
  public void setUp() {
    CallRecorder.resetInstanceForTesting();
    resetCallList();
  }

  @After
  public void tearDown() {
    CallRecorder.resetInstanceForTesting();
    resetCallList();
  }

  /**
   * CallList.addListener immediately calls onCallListChange(), so constructing CallRecorder before
   * setUp(Context) must not inspect calls or bind without a Context.
   */
  @Test
  public void getInstanceBeforeSetupDoesNotTouchCallList() {
    CallList.setCallListInstance(createCallListOnMain(true /* failIfCallStateInspected */));

    Throwable thrown = runOnMainAndCaptureThrowable(
        new Runnable() {
          @Override
          public void run() {
            CallRecorder.getInstance();
          }
        });
    if (thrown != null) {
      throw new AssertionError(thrown);
    }
  }

  /**
   * InCallServiceImpl can bind more than once while the process survives; same context setup must
   * preserve recording state.
   */
  @Test
  public void setupWithSameContextPreservesArmedRecording() {
    CallRecorder recorder = newCallRecorder();
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    recorder.setUp(context);
    recorder.armRecording("call-1", true /* startedAutomatically */);
    recorder.setUp(context);

    assertThat(recorder.isRecordingArmed("call-1")).isTrue();
  }

  /**
   * Listener dispatch must remain safe if UI lifecycle code unregisters a listener during a stop
   * callback.
   */
  @Test
  public void notifyRecordingStoppedAllowsListenerRemovalDuringCallback() {
    FakeRecorderService service = new FakeRecorderService(null /* activeRecording */);
    CallRecorder recorder = recorderWithService(service);
    recorder.setUp(InstrumentationRegistry.getInstrumentation().getTargetContext());
    assertThat(recorder.startRecording("+15551234567", 1L /* creationTime */)).isTrue();

    AtomicInteger stopCallbacks = new AtomicInteger();
    CallRecorder.RecordingProgressListener secondListener = new NoOpRecordingProgressListener() {
      @Override
      public void onStopRecording() {
        stopCallbacks.incrementAndGet();
      }
    };
    CallRecorder.RecordingProgressListener removingListener = new NoOpRecordingProgressListener() {
      @Override
      public void onStopRecording() {
        stopCallbacks.incrementAndGet();
        recorder.removeRecordingProgressListener(secondListener);
      }
    };
    CallRecorder.RecordingProgressListener thirdListener = new NoOpRecordingProgressListener() {
      @Override
      public void onStopRecording() {
        stopCallbacks.incrementAndGet();
      }
    };

    recorder.addRecordingProgressListener(removingListener);
    recorder.addRecordingProgressListener(secondListener);
    recorder.addRecordingProgressListener(thirdListener);

    recorder.finishRecording();

    assertThat(stopCallbacks.get()).isAtLeast(2);
  }

  /**
   * Hangup, service disconnect, and error cleanup can all converge on stop notification; users
   * should not see duplicate stop transitions for one recording.
   */
  @Test
  public void notifyRecordingStoppedOnlyNotifiesOncePerStartedRecording() {
    FakeRecorderService service = new FakeRecorderService(null /* activeRecording */);
    CallRecorder recorder = recorderWithService(service);
    AtomicInteger stopCallbacks = new AtomicInteger();
    recorder.setUp(InstrumentationRegistry.getInstrumentation().getTargetContext());
    assertThat(recorder.startRecording("+15551234567", 1L /* creationTime */)).isTrue();
    recorder.addRecordingProgressListener(
        new NoOpRecordingProgressListener() {
          @Override
          public void onStopRecording() {
            stopCallbacks.incrementAndGet();
          }
        });

    recorder.finishRecording();
    recorder.finishRecording();

    assertThat(stopCallbacks.get()).isEqualTo(1);
  }

  /** Late listeners replay active recording state owned by CallRecorder. */
  @Test
  public void newProgressListenerReceivesCurrentRecording() {
    CallRecorder recorder =
        recorderWithService(new FakeRecorderService(null /* activeRecording */));
    assertThat(recorder.startRecording("+15551234567", 1L /* creationTime */)).isTrue();

    AtomicInteger startCallbacks = new AtomicInteger();
    AtomicInteger progressCallbacks = new AtomicInteger();
    AtomicReference<Boolean> startedAutomatically = new AtomicReference<>();
    AtomicReference<Long> elapsedTimeMs = new AtomicReference<>();
    recorder.addRecordingProgressListener(
        new NoOpRecordingProgressListener() {
          @Override
          public void onStartRecording(boolean automatic) {
            startCallbacks.incrementAndGet();
            startedAutomatically.set(automatic);
          }

          @Override
          public void onRecordingTimeProgress(long elapsed) {
            progressCallbacks.incrementAndGet();
            elapsedTimeMs.set(elapsed);
          }
        });

    assertThat(startCallbacks.get()).isEqualTo(1);
    assertThat(startedAutomatically.get()).isFalse();
    assertThat(progressCallbacks.get()).isEqualTo(1);
    assertThat(elapsedTimeMs.get()).isAtLeast(0L);
  }

  @Test
  public void newArmListenerReceivesCurrentArmedRecordingAndDisarm() {
    CallRecorder recorder = newCallRecorder();
    AtomicReference<String> armedCallId = new AtomicReference<>();
    AtomicReference<Boolean> armedAutomatically = new AtomicReference<>();
    AtomicReference<String> disarmedCallId = new AtomicReference<>();
    recorder.armRecording("call-1", true /* startedAutomatically */);

    recorder.addRecordingArmListener(
        new CallRecorder.RecordingArmListener() {
          @Override
          public void onRecordingArmed(String callId, boolean startedAutomatically) {
            armedCallId.set(callId);
            armedAutomatically.set(startedAutomatically);
          }

          @Override
          public void onRecordingDisarmed(String callId) {
            disarmedCallId.set(callId);
          }
        });
    recorder.disarmRecording("call-1");

    assertThat(armedCallId.get()).isEqualTo("call-1");
    assertThat(armedAutomatically.get()).isTrue();
    assertThat(disarmedCallId.get()).isEqualTo("call-1");
  }

  /**
   * The user can tap the recording button off, or disconnect handling can stop an active recording
   * through finishRecording().
   */
  @Test
  public void finishRecordingStopsServiceAndNotifiesStopped() {
    FakeRecorderService service =
        new FakeRecorderService(
            new CallRecording(
                "" /* phoneNumber */,
                1L /* creationTime */,
                "CallRecord_19700101-000001_unknown.m4a",
                2L /* startRecordingTime */,
                3L /* mediaId */));
    CallRecorder recorder = recorderWithService(service);
    AtomicInteger stopCallbacks = new AtomicInteger();
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

  @Test
  public void manualRecordOnActiveCallStartsServiceImmediately() throws Exception {
    ICallRecorderService service = mock(ICallRecorderService.class);
    when(service.startRecording("+15551234567", 1234L)).thenReturn(true);
    CallRecorder recorder = recorderWithService(service);
    recorder.setUp(InstrumentationRegistry.getInstrumentation().getTargetContext());

    boolean started = recorder.startOrArmManualRecording(
        call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L));

    assertThat(started).isTrue();
    verify(service).startRecording("+15551234567", 1234L);
    assertThat(recorder.isRecordingArmed("call-1")).isFalse();
  }

  @Test
  public void manualRecordOnDialingCallStartsWhenCallBecomesActive() throws Exception {
    ICallRecorderService service = mock(ICallRecorderService.class);
    when(service.startRecording("+15551234567", 1234L)).thenReturn(true);
    CallRecorder recorder = recorderWithService(service);
    TestCallList callList =
        new TestCallList(call("call-1", DialerCallState.DIALING, "+15551234567", 1234L));
    CallList.setCallListInstance(callList);
    recorder.setUp(InstrumentationRegistry.getInstrumentation().getTargetContext());

    boolean armed = recorder.startOrArmManualRecording(callList.call.get());

    assertThat(armed).isTrue();
    assertThat(recorder.isRecordingArmed("call-1")).isTrue();

    callList.call.set(call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L));
    recorder.onCallListChange(callList);

    verify(service).startRecording("+15551234567", 1234L);
    assertThat(recorder.isRecordingArmed("call-1")).isFalse();
  }

  @Test
  public void manualRecordOnConnectingCallStartsWhenCallBecomesActive() {
    FakeRecorderService service = new FakeRecorderService(null /* activeRecording */);
    CallRecorder recorder = recorderWithService(service);
    TestCallList callList =
        new TestCallList(call("call-1", DialerCallState.CONNECTING, "+15551234567", 1234L));
    CallList.setCallListInstance(callList);
    recorder.setUp(InstrumentationRegistry.getInstrumentation().getTargetContext());

    boolean armed = recorder.startOrArmManualRecording(callList.call.get());

    assertThat(armed).isTrue();
    assertThat(recorder.isRecordingArmed("call-1")).isTrue();
    assertThat(service.isRecordingForTesting()).isFalse();

    callList.call.set(call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L));
    recorder.onCallListChange(callList);

    assertThat(service.isRecordingForTesting()).isTrue();
    assertThat(recorder.isRecordingArmed("call-1")).isFalse();
  }

  @Test
  public void manualRecordOnActiveCallWaitsForServiceConnection() throws Exception {
    FakeServiceBinding serviceBinding = new FakeServiceBinding();
    CallRecorder recorder = newCallRecorder(serviceBinding);
    ICallRecorderService service = mock(ICallRecorderService.class);
    when(service.startRecording("+15551234567", 1234L)).thenReturn(true);
    TestCallList callList =
        new TestCallList(call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L));
    CallList.setCallListInstance(callList);
    recorder.setUp(InstrumentationRegistry.getInstrumentation().getTargetContext());

    boolean armed = recorder.startOrArmManualRecording(callList.call.get());

    assertThat(armed).isTrue();
    assertThat(recorder.isRecordingArmed("call-1")).isTrue();

    serviceBinding.connect(service);
    recorder.onCallListChange(callList);

    verify(service).startRecording("+15551234567", 1234L);
    assertThat(recorder.isRecordingArmed("call-1")).isFalse();
  }

  @Test
  public void startRecordingRemoteExceptionUnbindsAndStopsRecordingState() {
    FakeServiceBinding serviceBinding =
        new FakeServiceBinding(new FakeRecorderService(null /* activeRecording */));
    CallRecorder recorder = newCallRecorder(serviceBinding);
    AtomicInteger stopCallbacks = new AtomicInteger();
    recorder.setUp(InstrumentationRegistry.getInstrumentation().getTargetContext());
    assertThat(
            recorder.startOrArmManualRecording(
                call("call-0", DialerCallState.ACTIVE, "+15550000000", 1233L)))
        .isTrue();
    // Listener registration replays getActiveRecording(); install the throwing service afterward.
    recorder.addRecordingProgressListener(
        new NoOpRecordingProgressListener() {
          @Override
          public void onStopRecording() {
            stopCallbacks.incrementAndGet();
          }
        });
    serviceBinding.connect(new ThrowingRecorderService());

    boolean started = recorder.startOrArmManualRecording(
        call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L));

    assertThat(started).isFalse();
    assertThat(serviceBinding.unbindCount).isEqualTo(1);
    assertThat(serviceBinding.isBound()).isFalse();
    assertThat(serviceBinding.getService()).isNull();
    assertThat(stopCallbacks.get()).isEqualTo(1);
  }

  /**
   * A dead recorder service binder can fail while the UI still believes recording is active.
   */
  @Test
  public void finishRecordingRemoteExceptionUnbindsAndStopsRecordingState() {
    FakeServiceBinding serviceBinding =
        new FakeServiceBinding(new FakeRecorderService(null /* activeRecording */));
    CallRecorder recorder = newCallRecorder(serviceBinding);
    AtomicInteger stopCallbacks = new AtomicInteger();
    recorder.setUp(InstrumentationRegistry.getInstrumentation().getTargetContext());
    assertThat(recorder.startRecording("+15551234567", 1L /* creationTime */)).isTrue();
    recorder.addRecordingProgressListener(
        new NoOpRecordingProgressListener() {
          @Override
          public void onStopRecording() {
            stopCallbacks.incrementAndGet();
          }
        });
    serviceBinding.connect(new ThrowingRecorderService());

    recorder.finishRecording();

    assertThat(serviceBinding.unbindCount).isEqualTo(1);
    assertThat(serviceBinding.isBound()).isFalse();
    assertThat(serviceBinding.getService()).isNull();
    assertThat(stopCallbacks.get()).isEqualTo(1);
  }

  private static void resetCallList() {
    CallList.setCallListInstance(createCallListOnMain(false /* failIfCallStateInspected */));
  }

  private static CallList createCallListOnMain(final boolean failIfCallStateInspected) {
    final AtomicReference<CallList> callList = new AtomicReference<>();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(
        new Runnable() {
          @Override
          public void run() {
            callList.set(failIfCallStateInspected ? new PreSetupCallList() : new CallList());
          }
        });
    return callList.get();
  }

  private static Throwable runOnMainAndCaptureThrowable(Runnable runnable) {
    final AtomicReference<Throwable> thrown = new AtomicReference<>();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(
        new Runnable() {
          @Override
          public void run() {
            try {
              runnable.run();
            } catch (Throwable t) {
              thrown.set(t);
            }
          }
        });
    return thrown.get();
  }

  private static DialerCall call(String callId, int state, String number, long creationTime) {
    DialerCall call = mock(DialerCall.class);
    when(call.getId()).thenReturn(callId);
    when(call.getState()).thenReturn(state);
    when(call.getNumber()).thenReturn(number);
    when(call.getCreationTimeMillis()).thenReturn(creationTime);
    when(call.isVideoCall()).thenReturn(false);
    return call;
  }

  private static final class PreSetupCallList extends CallList {
    @Override
    public DialerCall getActiveCall() {
      throw new AssertionError("CallRecorder should wait for setUp(Context) before checking calls");
    }
  }

  private static final class TestCallList extends CallList {
    final AtomicReference<DialerCall> call;

    TestCallList(DialerCall call) {
      this.call = new AtomicReference<>(call);
    }

    @Override
    public DialerCall getActiveCall() {
      DialerCall currentCall = call.get();
      return currentCall != null && currentCall.getState() == DialerCallState.ACTIVE
          ? currentCall
          : null;
    }

    @Override
    public DialerCall getCallById(String callId) {
      DialerCall currentCall = call.get();
      return currentCall != null && TextUtils.equals(currentCall.getId(), callId)
          ? currentCall
          : null;
    }
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

  private static CallRecorder newCallRecorder() {
    return newCallRecorder(new FakeServiceBinding());
  }

  private static CallRecorder newCallRecorder(CallRecorderServiceBinding serviceBinding) {
    return new CallRecorder(
        false /* addCallListListener */,
        new Handler(Looper.getMainLooper()),
        serviceBinding);
  }

  private static CallRecorder recorderWithService(ICallRecorderService service) {
    return newCallRecorder(new FakeServiceBinding(service));
  }

  private static final class FakeServiceBinding implements CallRecorderServiceBinding {
    private ICallRecorderService service;
    private boolean bound;
    private int unbindCount;
    private Listener listener;

    FakeServiceBinding() {}

    FakeServiceBinding(ICallRecorderService service) {
      connect(service);
    }

    @Override
    public boolean isBound() {
      return bound;
    }

    @Override
    public ICallRecorderService getService() {
      return service;
    }

    @Override
    public boolean bind(Context context, Intent serviceIntent, Listener listener) {
      this.listener = listener;
      bound = true;
      return true;
    }

    @Override
    public void unbind(Context context) {
      if (bound) {
        unbindCount++;
      }
      bound = false;
      service = null;
    }

    void connect(ICallRecorderService service) {
      this.service = service;
      bound = true;
      if (listener != null) {
        listener.onServiceConnected();
      }
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

  }
}
