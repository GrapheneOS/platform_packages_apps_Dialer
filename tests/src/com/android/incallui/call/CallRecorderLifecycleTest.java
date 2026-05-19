package com.android.incallui.call;

import static com.android.incallui.call.CallRecordingTestSupport.call;
import static com.android.incallui.call.CallRecordingTestSupport.conferenceCall;
import static com.android.incallui.call.CallRecordingTestSupport.conferenceChildCall;
import static com.android.incallui.call.CallRecordingTestSupport.testCallList;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserManager;
import android.text.TextUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.android.dialer.callrecord.CallRecording;
import com.android.dialer.callrecord.CallRecordingPreferences;
import com.android.dialer.callrecord.ICallRecorderService;
import com.android.dialer.callrecord.ICallRecorderServiceCallback;
import com.android.dialer.callrecord.impl.CallRecorderService;
import com.android.dialer.callrecord.impl.CallRecorderServiceV2;
import com.android.incallui.call.CallRecordingTestSupport.NoOpRecordingProgressListener;
import com.android.incallui.call.CallRecordingTestSupport.TestCallList;
import com.android.incallui.call.AutoCallRecordingEligibility.AutoRecordDecision;
import com.android.incallui.call.state.DialerCallState;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kotlinx.coroutines.Dispatchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CallRecorderLifecycleTest {

  @Before
  public void setUp() {
    resetController();
    resetCallList();
  }

  @After
  public void tearDown() {
    resetController();
    resetCallList();
  }

  /**
   * CallList.addListener immediately calls onCallListChange(), so constructing the controller
   * before setUp(Context) must not inspect calls or bind without a Context.
   */
  @Test
  public void getInstanceBeforeSetupDoesNotTouchCallList() {
    CallList.setCallListInstance(createCallListOnMain(true /* failIfCallStateInspected */));

    Throwable thrown = runOnMainAndCaptureThrowable(
        new Runnable() {
          @Override
          public void run() {
            CallRecordingController.getInstance();
          }
        });
    if (thrown != null) {
      throw new AssertionError(thrown);
    }
  }

  @Test
  public void attachingSameContextPreservesArmedRecording() {
    CallRecorder recorder = newCallRecorder();
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    recorder.attachContext(context);
    recorder.armRecording("call-1", true /* startedAutomatically */);
    recorder.attachContext(context);

    assertThat(recorder.isRecordingArmed("call-1")).isTrue();
  }

  @Test
  public void recorderServiceSelectionFollowsLoadedV2Preference() {
    CallRecordingPreferences v1Preferences =
        preferencesBuilder().setUseCallRecordingV2(false).build();
    CallRecordingPreferences v2Preferences =
        preferencesBuilder().setUseCallRecordingV2(true).build();

    // Binding waits for DataStore first. Service selection must use that loaded proto rather than
    // reading a global snapshot again, otherwise initialization can race preference loading.
    assertThat(CallRecorder.recorderServiceClass(v1Preferences))
        .isEqualTo(CallRecorderService.class);
    assertThat(CallRecorder.recorderServiceClass(v2Preferences))
        .isEqualTo(CallRecorderServiceV2.class);
  }

  @Test
  public void activeCallBeforeUnlockDoesNotBindRecorderService() {
    FakeServiceBinding serviceBinding = new FakeServiceBinding();
    CallRecorder recorder = newCallRecorder(serviceBinding);
    TestCallList callList =
        testCallList(call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L));
    CallList.setCallListInstance(callList);

    runOnMain(
        () -> {
          CallRecordingController controller =
              new CallRecordingController(recorder, testDependencies());
          controller.setUp(lockedUserContext());
          controller.onCallListChange(callList);
        });

    assertThat(serviceBinding.bindCount).isEqualTo(0);
  }

  /**
   * Listener dispatch must remain safe if UI lifecycle code unregisters a listener during a stop
   * callback.
   */
  @Test
  public void notifyRecordingStoppedAllowsListenerRemovalDuringCallback() {
    FakeRecorderService service = new FakeRecorderService(null /* activeRecording */);
    CallRecorder recorder = recorderWithService(service);
    recorder.attachContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
    recorder.startOrArmManualRecording(
        call("call-1", DialerCallState.ACTIVE, null, 1234L));

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
    recorder.attachContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
    recorder.startOrArmManualRecording(
        call("call-1", DialerCallState.ACTIVE, null, 1234L));
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
    recorder.attachContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
    assertThat(
            recorder.startOrArmManualRecording(
                call("call-1", DialerCallState.ACTIVE, "+15551234567", 1L /* creationTime */)))
        .isTrue();

    AtomicInteger startCallbacks = new AtomicInteger();
    AtomicInteger progressCallbacks = new AtomicInteger();
    AtomicReference<Long> elapsedTimeMs = new AtomicReference<>();
    recorder.addRecordingProgressListener(
        new NoOpRecordingProgressListener() {
          @Override
          public void onStartRecording() {
            startCallbacks.incrementAndGet();
          }

          @Override
          public void onRecordingTimeProgress(long elapsed) {
            progressCallbacks.incrementAndGet();
            elapsedTimeMs.set(elapsed);
          }
        });

    assertThat(startCallbacks.get()).isEqualTo(1);
    assertThat(progressCallbacks.get()).isEqualTo(1);
    assertThat(elapsedTimeMs.get()).isAtLeast(0L);
  }

  @Test
  public void registeringSameProgressListenerAgainDoesNotReplayCurrentRecording() {
    CallRecorder recorder =
        recorderWithService(new FakeRecorderService(null /* activeRecording */));
    recorder.attachContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
    assertThat(
            recorder.startOrArmManualRecording(
                call("call-1", DialerCallState.ACTIVE, "+15551234567", 1L /* creationTime */)))
        .isTrue();

    AtomicInteger startCallbacks = new AtomicInteger();
    AtomicInteger progressCallbacks = new AtomicInteger();
    CallRecorder.RecordingProgressListener listener =
        new NoOpRecordingProgressListener() {
          @Override
          public void onStartRecording() {
            startCallbacks.incrementAndGet();
          }

          @Override
          public void onRecordingTimeProgress(long elapsedTimeMs) {
            progressCallbacks.incrementAndGet();
          }
        };

    recorder.addRecordingProgressListener(listener);
    // Notification refreshes can register the same listener while recording is already active. The
    // second registration must not replay onStartRecording(), because that callback can refresh the
    // notification again and re-enter listener registration.
    recorder.addRecordingProgressListener(listener);

    assertThat(startCallbacks.get()).isEqualTo(1);
    assertThat(progressCallbacks.get()).isEqualTo(1);
  }

  @Test
  public void automaticRecordingStartNotifiesUiNoticeListenerOnce() {
    FakeRecorderService service = new FakeRecorderService(null /* activeRecording */);
    CallRecorder recorder = recorderWithService(service);
    recorder.attachContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
    recorder.addRecordingProgressListener(new NoOpRecordingProgressListener());

    AtomicInteger automaticStartCallbacks = new AtomicInteger();
    recorder.addAutomaticRecordingStartListener(automaticStartCallbacks::incrementAndGet);

    recorder.armRecording("call-1", true /* startedAutomatically */);
    recorder.maybeStartArmedRecording(
        call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L));

    AtomicInteger lateAutomaticStartCallbacks = new AtomicInteger();
    recorder.addAutomaticRecordingStartListener(lateAutomaticStartCallbacks::incrementAndGet);

    assertThat(automaticStartCallbacks.get()).isEqualTo(1);
    assertThat(lateAutomaticStartCallbacks.get()).isEqualTo(0);
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

  @Test
  public void registeringSameArmListenerAgainDoesNotReplayCurrentArmedRecording() {
    CallRecorder recorder = newCallRecorder();
    recorder.armRecording("call-1", true /* startedAutomatically */);
    AtomicInteger armCallbacks = new AtomicInteger();
    CallRecorder.RecordingArmListener listener =
        new CallRecorder.RecordingArmListener() {
          @Override
          public void onRecordingArmed(String callId, boolean startedAutomatically) {
            armCallbacks.incrementAndGet();
          }

          @Override
          public void onRecordingDisarmed(String callId) {}
        };

    recorder.addRecordingArmListener(listener);
    recorder.addRecordingArmListener(listener);

    assertThat(armCallbacks.get()).isEqualTo(1);
  }

  /**
   * The user can tap the recording button off, or disconnect handling can stop an active recording
   * through finishRecording().
   */
  @Test
  public void finishRecordingStopsServiceAndNotifiesStopped() {
    FakeRecorderService service = new FakeRecorderService(null /* activeRecording */);
    CallRecorder recorder = recorderWithService(service);
    AtomicInteger stopCallbacks = new AtomicInteger();
    recorder.attachContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
    recorder.startOrArmManualRecording(
        call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L));
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
  public void manualRecordDoesNotRestartUntilRecorderStopCompletes() {
    FakeRecorderService service = new FakeRecorderService(null /* activeRecording */);
    service.delayStopCallback();
    CallRecorder recorder = recorderWithService(service);
    DialerCall firstCall =
        call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L);
    DialerCall secondCall =
        call("call-2", DialerCallState.ACTIVE, "+15557654321", 2345L);
    recorder.attachContext(InstrumentationRegistry.getInstrumentation().getTargetContext());

    assertThat(recorder.startOrArmManualRecording(firstCall)).isTrue();
    recorder.finishRecording();
    boolean restartedWhileStopPending = recorder.startOrArmManualRecording(secondCall);

    assertThat(restartedWhileStopPending).isFalse();
    assertThat(recorder.isRecording()).isFalse();
    assertThat(recorder.isRecordingStopPending()).isTrue();
    assertThat(service.startCount).isEqualTo(1);

    service.completeStop();
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    boolean restartedAfterStopCallback = recorder.startOrArmManualRecording(secondCall);

    assertThat(restartedAfterStopCallback).isTrue();
    assertThat(recorder.isRecordingStopPending()).isFalse();
    assertThat(service.startCount).isEqualTo(2);
  }

  @Test
  public void newActiveCallStartsAutomaticRecordingAfterPreviousRecordingStopCompletes() {
    FakeRecorderService service = new FakeRecorderService(null /* activeRecording */);
    service.delayStopCallback();
    CallRecorder recorder = recorderWithService(service);
    DialerCall firstCall =
        call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L);
    DialerCall secondCall =
        call("call-2", DialerCallState.ACTIVE, "+15557654321", 2345L);
    TestCallList callList = testCallList(firstCall);
    CallList.setCallListInstance(callList);
    CallRecordingController controller = newController(recorder);

    startAutomaticRecording(recorder, firstCall);
    recorder.finishRecording();
    callList.setCall(secondCall);
    recorder.armRecording(secondCall.getId(), true /* startedAutomatically */);
    notifyCallListChanged(controller, callList);

    assertThat(recorder.isRecordingStopPending()).isTrue();
    assertThat(service.startCount).isEqualTo(1);

    service.completeStop();
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    assertThat(recorder.isRecordingStopPending()).isFalse();
    assertThat(service.startCount).isEqualTo(2);
    assertThat(service.isRecordingForTesting()).isTrue();
    assertThat(recorder.isRecordingArmed(secondCall.getId())).isFalse();
  }

  @Test
  public void manualRecordOnActiveCallStartsServiceImmediately() throws Exception {
    ICallRecorderService service = mock(ICallRecorderService.class);
    when(service.startRecording("+15551234567", 1234L)).thenReturn(true);
    CallRecorder recorder = recorderWithService(service);
    recorder.attachContext(InstrumentationRegistry.getInstrumentation().getTargetContext());

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
    CallRecordingController controller = newController(recorder);

    boolean started = recorder.startOrArmManualRecording(activeCall);
    notifyCallListChanged(controller, callList);

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
    CallRecordingController controller = newController(recorder);

    boolean started = recorder.startOrArmManualRecording(call);
    notifyCallListChanged(controller, callList);

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
    CallRecordingController controller = newController(recorder);

    boolean armed = recorder.startOrArmManualRecording(callList.getOnlyCall());

    assertThat(armed).isTrue();
    assertThat(recorder.isRecordingArmed("call-1")).isTrue();

    callList.setCall(call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L));
    notifyCallListChanged(controller, callList);

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
    CallRecordingController controller = newController(recorder);

    boolean armed = recorder.startOrArmManualRecording(callList.getOnlyCall());

    assertThat(armed).isTrue();
    assertThat(recorder.isRecordingArmed("call-1")).isTrue();
    assertThat(service.isRecordingForTesting()).isFalse();

    callList.setCall(call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L));
    notifyCallListChanged(controller, callList);

    assertThat(service.isRecordingForTesting()).isTrue();
    assertThat(recorder.isRecordingArmed("call-1")).isFalse();
  }

  @Test
  public void automaticRecordingIsDisarmedWhenCallJoinsConference() {
    CallRecorder recorder = newCallRecorder();
    TestCallList callList = testCallList(conferenceChildCall("call-1", "+15551234567"));
    CallList.setCallListInstance(callList);
    CallRecordingController controller = newController(recorder);
    recorder.armRecording("call-1", true /* startedAutomatically */);

    notifyCallListChanged(controller, callList);

    assertThat(recorder.isRecordingArmed("call-1")).isFalse();
  }

  @Test
  public void recordingStopsWhenRecordedCallMovesToHoldWithNewCallId() {
    FakeRecorderService service = new FakeRecorderService(null /* activeRecording */);
    CallRecorder recorder = recorderWithService(service);
    DialerCall recordedCall =
        call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L);
    TestCallList callList = testCallList(recordedCall);
    CallList.setCallListInstance(callList);
    CallRecordingController controller = newController(recorder);

    startAutomaticRecording(recorder, recordedCall);
    callList.setCalls(
        call("call-2", DialerCallState.ONHOLD, "+15551234567", 1234L),
        call("call-3", DialerCallState.ACTIVE, "+15557654321", 2345L));
    notifyCallListChanged(controller, callList);

    assertThat(service.stopCount).isEqualTo(1);
    assertThat(service.isRecordingForTesting()).isFalse();
  }

  @Test
  public void automaticRecordingStartsWhenDelayedContactLookupStillMatchesActiveCall()
      throws Exception {
    FakeRecorderService service = new FakeRecorderService(null /* activeRecording */);
    CallRecorder recorder = recorderWithService(service);
    BlockingTestContactLookup contactLookup = new BlockingTestContactLookup();
    TestCallList callList =
        testCallList(call("call-1", DialerCallState.ACTIVE, null, 1234L));
    CallRecordingPreferences preferences =
        preferencesBuilder().setAutoRecordNonContacts(true).build();
    CallList.setCallListInstance(callList);

    newController(
        recorder,
        testDependencies(
            contactLookup, preferences, AutoRecordDecision.ELIGIBLE));
    assertThat(service.isRecordingForTesting()).isFalse();

    assertThat(contactLookup.awaitStarted()).isTrue();
    contactLookup.complete(null);
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    assertThat(service.isRecordingForTesting()).isTrue();
    assertThat(recorder.isRecordingArmed("call-1")).isFalse();
  }

  @Test
  public void activeRecordingStopsWhenCallBecomesConference() {
    FakeRecorderService service = new FakeRecorderService(null /* activeRecording */);
    CallRecorder recorder = recorderWithService(service);
    DialerCall call = call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L);
    TestCallList callList = testCallList(call);
    CallList.setCallListInstance(callList);
    CallRecordingController controller = newController(recorder);
    startAutomaticRecording(recorder, call);

    callList.setCall(conferenceCall("call-1", "+15551234567"));
    notifyCallListChanged(controller, callList);

    assertThat(service.stopCount).isEqualTo(1);
    assertThat(service.isRecordingForTesting()).isFalse();
  }

  @Test
  public void activeRecordingStopsWhenCallUpgradesToVideo() {
    FakeRecorderService service = new FakeRecorderService(null /* activeRecording */);
    CallRecorder recorder = recorderWithService(service);
    DialerCall call = call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L);
    CallList.setCallListInstance(testCallList(call));
    CallRecordingController controller = newController(recorder);

    boolean started = recorder.startOrArmManualRecording(call);
    runOnMain(() -> controller.onUpgradeToVideo(call));

    assertThat(started).isTrue();
    assertThat(service.stopCount).isEqualTo(1);
    assertThat(service.isRecordingForTesting()).isFalse();
  }

  @Test
  public void manualRecordingOnConferenceContinuesAfterCallListRefresh() {
    FakeRecorderService service = new FakeRecorderService(null /* activeRecording */);
    CallRecorder recorder = recorderWithService(service);
    DialerCall call = conferenceCall("call-1", "+15551234567");
    TestCallList callList = testCallList(call);
    CallList.setCallListInstance(callList);
    CallRecordingController controller = newController(recorder);

    notifyCallListChanged(controller, callList);
    boolean started = recorder.startOrArmManualRecording(call);
    notifyCallListChanged(controller, callList);

    assertThat(started).isTrue();
    assertThat(service.stopCount).isEqualTo(0);
    assertThat(service.isRecordingForTesting()).isTrue();
  }

  @Test
  public void manualRecordingStopsWhenConferenceParticipantsChange() {
    FakeRecorderService service = new FakeRecorderService(null /* activeRecording */);
    CallRecorder recorder = recorderWithService(service);
    DialerCall initialConferenceCall = conferenceCall("call-1", "+15551234567");
    TestCallList callList = testCallList(initialConferenceCall);
    CallList.setCallListInstance(callList);
    CallRecordingController controller = newController(recorder);

    notifyCallListChanged(controller, callList);
    boolean started = recorder.startOrArmManualRecording(initialConferenceCall);
    callList.setCalls(
        conferenceChildCall("call-1", "+15551234567"),
        conferenceCall("call-2", "+15557654321"));
    notifyCallListChanged(controller, callList);

    assertThat(started).isTrue();
    assertThat(service.stopCount).isEqualTo(1);
    assertThat(service.isRecordingForTesting()).isFalse();
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
    CallRecordingController controller = newController(recorder);

    boolean armed = recorder.startOrArmManualRecording(callList.getOnlyCall());

    assertThat(armed).isTrue();
    assertThat(recorder.isRecordingArmed("call-1")).isTrue();

    runOnMain(() -> serviceBinding.connect(service));
    notifyCallListChanged(controller, callList);

    verify(service).startRecording("+15551234567", 1234L);
    assertThat(recorder.isRecordingArmed("call-1")).isFalse();
  }

  @Test
  public void startRecordingRemoteExceptionUnbindsAndStopsRecordingState() {
    FakeServiceBinding serviceBinding =
        new FakeServiceBinding(new FakeRecorderService(null /* activeRecording */));
    CallRecorder recorder = newCallRecorder(serviceBinding);
    AtomicInteger stopCallbacks = new AtomicInteger();
    recorder.attachContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
    recorder.startOrArmManualRecording(
        call("call-0", DialerCallState.ACTIVE, "+15550000000", 1233L));
    // Listener registration replays the local active recording; install the throwing service
    // afterward so only the next command fails.
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
    recorder.attachContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
    recorder.startOrArmManualRecording(
        call("call-0", DialerCallState.ACTIVE, "+15550000000", 1233L));
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

  @Test
  public void recordingErrorCallbackStopsLocalRecordingState() {
    FakeRecorderService service = new FakeRecorderService(null /* activeRecording */);
    CallRecorder recorder = recorderWithService(service);
    AtomicInteger stopCallbacks = new AtomicInteger();
    recorder.attachContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
    recorder.startOrArmManualRecording(
        call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L));
    recorder.addRecordingProgressListener(
        new NoOpRecordingProgressListener() {
          @Override
          public void onStopRecording() {
            stopCallbacks.incrementAndGet();
          }
        });

    service.notifyRecordingError();
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    assertThat(recorder.isRecording()).isFalse();
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

  private static void resetController() {
    runOnMain(CallRecordingController::resetInstanceForTesting);
  }

  private static CallRecordingController newController(CallRecorder recorder) {
    return newController(recorder, testDependencies());
  }

  private static CallRecordingController newController(
      CallRecorder recorder, CallRecordingDependencies dependencies) {
    AtomicReference<CallRecordingController> controller = new AtomicReference<>();
    runOnMain(
        () -> {
          CallRecordingController value = new CallRecordingController(recorder, dependencies);
          value.setUp(InstrumentationRegistry.getInstrumentation().getTargetContext());
          controller.set(value);
        });
    return controller.get();
  }

  private static void notifyCallListChanged(
      CallRecordingController controller, CallList callList) {
    runOnMain(() -> controller.onCallListChange(callList));
  }

  private static CallRecordingDependencies testDependencies() {
    return testDependencies(
        new TestContactLookup(null),
        preferencesBuilder().build(),
        AutoRecordDecision.NOT_CONFIGURED);
  }

  private static CallRecordingDependencies testDependencies(
      ContactLookup contactLookup,
      CallRecordingPreferences preferences,
      AutoRecordDecision decision) {
    return new CallRecordingDependencies(
        new CallListCurrentCalls(),
        contactLookup,
        new TestPreferenceSource(preferences),
        new FakeSessionStore(),
        (call, snapshot, requireContactsPermission) -> decision,
        new FakeSystem(),
        Dispatchers.getUnconfined(),
        Dispatchers.getUnconfined());
  }

  private static CallRecordingPreferences.Builder preferencesBuilder() {
    return CallRecordingPreferences.newBuilder()
        .setSharedPreferencesMigrated(true)
        .setRecordingWarningPresented(true);
  }

  private static Context lockedUserContext() {
    UserManager userManager = mock(UserManager.class);
    when(userManager.isUserUnlocked()).thenReturn(false);
    Context baseContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    return new ContextWrapper(baseContext) {
      @Override
      public Context getApplicationContext() {
        return this;
      }

      @Override
      public Object getSystemService(String name) {
        if (Context.USER_SERVICE.equals(name)) {
          return userManager;
        }
        return super.getSystemService(name);
      }
    };
  }

  private static void startAutomaticRecording(CallRecorder recorder, DialerCall call) {
    recorder.armRecording(call.getId(), true /* startedAutomatically */);
    recorder.maybeStartArmedRecording(call);
  }

  private static final class PreSetupCallList extends CallList {
    @Override
    public DialerCall getActiveCall() {
      throw new AssertionError(
          "CallRecordingController should wait for setUp(Context) before checking calls");
    }
  }

  private static CallRecorder newCallRecorder() {
    return newCallRecorder(new FakeServiceBinding());
  }

  private static CallRecorder newCallRecorder(CallRecorderServiceBinding serviceBinding) {
    return new CallRecorder(new Handler(Looper.getMainLooper()), serviceBinding);
  }

  private static final class CallListCurrentCalls implements CurrentCalls {
    @Override
    public boolean hasOngoingCall() {
      return RecordingRules.hasOngoingCall(CallList.getInstance());
    }

    @Override
    public boolean hasActiveOrBackgroundCall() {
      return CallList.getInstance().getActiveOrBackgroundCall() != null;
    }

    @Override
    public boolean requiresManualRecordingStart() {
      return RecordingRules.requiresManualRecordingStart(CallList.getInstance());
    }

    @Override
    public CallSnapshot getActiveCall() {
      return toCallSnapshot(CallList.getInstance().getActiveCall());
    }

    @Override
    public CallSnapshot getCallById(String callId) {
      return toCallSnapshot(CallList.getInstance().getCallById(callId));
    }

    private static CallSnapshot toCallSnapshot(DialerCall call) {
      if (call == null) {
        return null;
      }
      return new CallSnapshot(
          call.getId(),
          call.getNumber(),
          call.getState(),
          call.isVideoCall(),
          RecordingRules.isConferenceCall(call),
          call,
          call.getCreationTimeMillis());
    }
  }

  private static final class FakeSystem implements CallRecordingSystem {
    @Override
    public boolean hasAllPermissions(String[] permissions) {
      return true;
    }

    @Override
    public boolean isUserUnlocked() {
      return true;
    }

    @Override
    public void showLockedUserMessage() {}
  }

  private static CallRecorder recorderWithService(ICallRecorderService service) {
    return newCallRecorder(new FakeServiceBinding(service));
  }

  private static final class FakeServiceBinding implements CallRecorderServiceBinding {
    private ICallRecorderService service;
    private boolean bound;
    private int bindCount;
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
      bindCount++;
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
    private CallRecording pendingStoppedRecording;
    private ICallRecorderServiceCallback callback;
    private boolean completeStopsImmediately = true;
    private int startCount;
    private int stopCount;

    FakeRecorderService(CallRecording activeRecording) {
      this.activeRecording = activeRecording;
    }

    @Override
    public void setCallback(ICallRecorderServiceCallback callback) {
      this.callback = callback;
    }

    @Override
    public boolean startRecording(String phoneNumber, long creationTime) {
      startCount++;
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
    public void stopRecording() {
      stopCount++;
      CallRecording recording = activeRecording;
      activeRecording = null;
      if (completeStopsImmediately) {
        notifyRecordingStopped(recording);
      } else {
        pendingStoppedRecording = recording;
      }
    }

    boolean isRecordingForTesting() {
      return activeRecording != null;
    }

    void delayStopCallback() {
      completeStopsImmediately = false;
    }

    void completeStop() {
      CallRecording recording = pendingStoppedRecording;
      pendingStoppedRecording = null;
      notifyRecordingStopped(recording);
    }

    private void notifyRecordingStopped(CallRecording recording) {
      if (callback == null) {
        throw new AssertionError("recorder callback was not registered");
      }
      try {
        callback.onRecordingStopped(recording);
      } catch (RemoteException e) {
        throw new AssertionError(e);
      }
    }

    void notifyRecordingError() {
      activeRecording = null;
      if (callback == null) {
        throw new AssertionError("recorder callback was not registered");
      }
      try {
        callback.onRecordingError();
      } catch (RemoteException e) {
        throw new AssertionError(e);
      }
    }
  }

  private static final class ThrowingRecorderService extends ICallRecorderService.Stub {
    @Override
    public void setCallback(ICallRecorderServiceCallback callback) {}

    @Override
    public boolean startRecording(String phoneNumber, long creationTime) throws RemoteException {
      throw new DeadObjectException();
    }

    @Override
    public void stopRecording() throws RemoteException {
      throw new DeadObjectException();
    }
  }
}
