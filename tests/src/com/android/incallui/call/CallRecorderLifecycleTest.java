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
import java.util.Arrays;
import java.util.Collection;
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
    FakeRecorderService service = activeFakeRecorderService();
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
  public void manualRecordWithHeldCallStartsServiceImmediately() throws Exception {
    FakeRecorderService service = new FakeRecorderService(null /* activeRecording */);
    CallRecorder recorder = recorderWithService(service);
    DialerCall activeCall = call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L);
    TestCallList callList =
        testCallList(
            activeCall, call("call-2", DialerCallState.ONHOLD, "+15557654321", 2345L));
    CallList.setCallListInstance(callList);
    recorder.setUp(InstrumentationRegistry.getInstrumentation().getTargetContext());

    boolean started = recorder.startOrArmManualRecording(activeCall);
    notifyCallListChanged(recorder, callList);

    assertThat(started).isTrue();
    assertThat(service.stopCount).isEqualTo(0);
    assertThat(service.isRecordingForTesting()).isTrue();
    assertThat(recorder.isRecordingArmed("call-1")).isFalse();
  }

  @Test
  public void manualRecordOnConferenceStartsServiceImmediately() throws Exception {
    FakeRecorderService service = new FakeRecorderService(null /* activeRecording */);
    CallRecorder recorder = recorderWithService(service);
    DialerCall call = conferenceCall("call-1", "+15551234567");
    TestCallList callList = testCallList(call);
    CallList.setCallListInstance(callList);
    recorder.setUp(InstrumentationRegistry.getInstrumentation().getTargetContext());

    boolean started = recorder.startOrArmManualRecording(call);
    notifyCallListChanged(recorder, callList);

    assertThat(started).isTrue();
    assertThat(service.stopCount).isEqualTo(0);
    assertThat(service.isRecordingForTesting()).isTrue();
    assertThat(recorder.isRecordingArmed("call-1")).isFalse();
  }

  @Test
  public void manualRecordOnDialingCallStartsWhenCallBecomesActive() throws Exception {
    ICallRecorderService service = mock(ICallRecorderService.class);
    when(service.startRecording("+15551234567", 1234L)).thenReturn(true);
    CallRecorder recorder = recorderWithService(service);
    TestCallList callList =
        testCallList(call("call-1", DialerCallState.DIALING, "+15551234567", 1234L));
    CallList.setCallListInstance(callList);
    recorder.setUp(InstrumentationRegistry.getInstrumentation().getTargetContext());

    boolean armed = recorder.startOrArmManualRecording(callList.getOnlyCall());

    assertThat(armed).isTrue();
    assertThat(recorder.isRecordingArmed("call-1")).isTrue();

    callList.setCall(call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L));
    notifyCallListChanged(recorder, callList);

    verify(service).startRecording("+15551234567", 1234L);
    assertThat(recorder.isRecordingArmed("call-1")).isFalse();
  }

  @Test
  public void manualRecordOnConnectingCallStartsWhenCallBecomesActive() {
    FakeRecorderService service = new FakeRecorderService(null /* activeRecording */);
    CallRecorder recorder = recorderWithService(service);
    TestCallList callList =
        testCallList(call("call-1", DialerCallState.CONNECTING, "+15551234567", 1234L));
    CallList.setCallListInstance(callList);
    recorder.setUp(InstrumentationRegistry.getInstrumentation().getTargetContext());

    boolean armed = recorder.startOrArmManualRecording(callList.getOnlyCall());

    assertThat(armed).isTrue();
    assertThat(recorder.isRecordingArmed("call-1")).isTrue();
    assertThat(service.isRecordingForTesting()).isFalse();

    callList.setCall(call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L));
    notifyCallListChanged(recorder, callList);

    assertThat(service.isRecordingForTesting()).isTrue();
    assertThat(recorder.isRecordingArmed("call-1")).isFalse();
  }

  @Test
  public void automaticRecordingIsDisarmedWhenCallJoinsConference() {
    CallRecorder recorder = newCallRecorder();
    TestCallList callList = testCallList(conferenceChildCall("call-1", "+15551234567"));
    CallList.setCallListInstance(callList);
    recorder.setUp(InstrumentationRegistry.getInstrumentation().getTargetContext());
    recorder.armRecording("call-1", true /* startedAutomatically */);

    notifyCallListChanged(recorder, callList);

    assertThat(recorder.isRecordingArmed("call-1")).isFalse();
  }

  @Test
  public void activeRecordingStopsWhenCallBecomesConference() {
    FakeRecorderService service = activeFakeRecorderService();
    CallRecorder recorder = recorderWithService(service);
    TestCallList callList = testCallList(conferenceCall("call-1", "+15551234567"));
    CallList.setCallListInstance(callList);
    recorder.setUp(InstrumentationRegistry.getInstrumentation().getTargetContext());
    assertThat(recorder.startRecording("+15551234567", 1L /* creationTime */)).isTrue();

    notifyCallListChanged(recorder, callList);

    assertThat(service.stopCount).isEqualTo(1);
    assertThat(service.isRecordingForTesting()).isFalse();
  }

  @Test
  public void activeRecordingStopsWhenCallUpgradesToVideo() {
    FakeRecorderService service = new FakeRecorderService(null /* activeRecording */);
    CallRecorder recorder = recorderWithService(service);
    DialerCall call = call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L);
    recorder.setUp(InstrumentationRegistry.getInstrumentation().getTargetContext());

    boolean started = recorder.startOrArmManualRecording(call);
    recorder.onUpgradeToVideo(call);

    assertThat(started).isTrue();
    assertThat(service.stopCount).isEqualTo(1);
    assertThat(service.isRecordingForTesting()).isFalse();
  }

  @Test
  public void manualRecordingOnConferenceContinuesAfterCallListRefresh() {
    FakeRecorderService service = activeFakeRecorderService();
    CallRecorder recorder = recorderWithService(service);
    DialerCall call = conferenceCall("call-1", "+15551234567");
    TestCallList callList = testCallList(call);
    CallList.setCallListInstance(callList);
    recorder.setUp(InstrumentationRegistry.getInstrumentation().getTargetContext());
    assertThat(recorder.startRecording("+15551234567", 1L /* creationTime */)).isTrue();

    notifyCallListChanged(recorder, callList);
    boolean started = recorder.startOrArmManualRecording(call);
    notifyCallListChanged(recorder, callList);

    assertThat(started).isTrue();
    assertThat(service.stopCount).isEqualTo(1);
    assertThat(service.isRecordingForTesting()).isTrue();
  }

  @Test
  public void manualRecordOnActiveCallWaitsForServiceConnection() throws Exception {
    FakeServiceBinding serviceBinding = new FakeServiceBinding();
    CallRecorder recorder = newCallRecorder(serviceBinding);
    ICallRecorderService service = mock(ICallRecorderService.class);
    when(service.startRecording("+15551234567", 1234L)).thenReturn(true);
    TestCallList callList =
        testCallList(call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L));
    CallList.setCallListInstance(callList);
    recorder.setUp(InstrumentationRegistry.getInstrumentation().getTargetContext());

    boolean armed = recorder.startOrArmManualRecording(callList.getOnlyCall());

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

  private static void runOnMain(Runnable runnable) {
    Throwable thrown = runOnMainAndCaptureThrowable(runnable);
    if (thrown == null) {
      return;
    }
    if (thrown instanceof RuntimeException) {
      throw (RuntimeException) thrown;
    }
    throw new AssertionError(thrown);
  }

  private static void notifyCallListChanged(CallRecorder recorder, CallList callList) {
    runOnMain(() -> recorder.onCallListChange(callList));
  }

  private static DialerCall call(String callId, int state, String number, long creationTime) {
    DialerCall call = mock(DialerCall.class);
    when(call.getId()).thenReturn(callId);
    when(call.getState()).thenReturn(state);
    when(call.getNumber()).thenReturn(number);
    when(call.getCreationTimeMillis()).thenReturn(creationTime);
    when(call.isVideoCall()).thenReturn(false);
    when(call.isConferenceCall()).thenReturn(false);
    when(call.getParentId()).thenReturn(null);
    return call;
  }

  private static FakeRecorderService activeFakeRecorderService() {
    return new FakeRecorderService(
        new CallRecording(
            "" /* phoneNumber */,
            1L /* creationTime */,
            "CallRecord_19700101-000001_unknown.m4a",
            2L /* startRecordingTime */,
            3L /* mediaId */));
  }

  private static TestCallList testCallList(DialerCall... calls) {
    AtomicReference<TestCallList> callList = new AtomicReference<>();
    // CallList creates a Handler in its constructor, so build it on the main looper.
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> callList.set(new TestCallList(calls)));
    return callList.get();
  }

  private static DialerCall conferenceCall(String callId, String number) {
    DialerCall call = call(callId, DialerCallState.ACTIVE, number, 1234L);
    when(call.isConferenceCall()).thenReturn(true);
    return call;
  }

  private static DialerCall conferenceChildCall(String callId, String number) {
    DialerCall call = call(callId, DialerCallState.CONFERENCED, number, 1234L);
    when(call.getParentId()).thenReturn("conference-1");
    return call;
  }

  private static final class PreSetupCallList extends CallList {
    @Override
    public DialerCall getActiveCall() {
      throw new AssertionError("CallRecorder should wait for setUp(Context) before checking calls");
    }
  }

  private static final class TestCallList extends CallList {
    final AtomicReference<Collection<DialerCall>> calls;

    TestCallList(DialerCall... calls) {
      this.calls = new AtomicReference<>(Arrays.asList(calls));
    }

    DialerCall getOnlyCall() {
      return getAllCalls().iterator().next();
    }

    void setCall(DialerCall call) {
      calls.set(Arrays.asList(call));
    }

    void setCalls(DialerCall... calls) {
      this.calls.set(Arrays.asList(calls));
    }

    @Override
    public DialerCall getActiveCall() {
      for (DialerCall currentCall : getAllCalls()) {
        if (currentCall.getState() == DialerCallState.ACTIVE) {
          return currentCall;
        }
      }
      return null;
    }

    @Override
    public DialerCall getCallById(String callId) {
      for (DialerCall currentCall : getAllCalls()) {
        if (TextUtils.equals(currentCall.getId(), callId)) {
          return currentCall;
        }
      }
      return null;
    }

    @Override
    public Collection<DialerCall> getAllCalls() {
      return calls.get();
    }

    @Override
    public DialerCall getCallWithStateAndNumber(int state, String number) {
      for (DialerCall currentCall : getAllCalls()) {
        if (currentCall.getState() == state && TextUtils.equals(currentCall.getNumber(), number)) {
          return currentCall;
        }
      }
      return null;
    }

    @Override
    public boolean hasLiveCall() {
      for (DialerCall currentCall : getAllCalls()) {
        if (DialerCallState.isConnectingOrConnected(currentCall.getState())) {
          return true;
        }
      }
      return false;
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
