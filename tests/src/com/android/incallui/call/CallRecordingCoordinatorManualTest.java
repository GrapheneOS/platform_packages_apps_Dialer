package com.android.incallui.call;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.dialer.callrecord.CallRecordingPreferences;
import com.android.incallui.call.AutoCallRecordingEligibility.AutoRecordDecision;
import com.android.incallui.call.state.DialerCallState;
import com.android.incallui.incall.protocol.InCallButtonUi;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import kotlinx.coroutines.Dispatchers;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CallRecordingCoordinatorManualTest {

  @Test
  public void manualRecordingDoesNotStartAfterCurrentCallChanges() throws Exception {
    AtomicReference<DialerCall> currentCall = new AtomicReference<>(call("call-1"));
    SettableFuture<CallRecordingPreferences> preferencesFuture = SettableFuture.create();
    CountDownLatch preferencesLoadStarted = new CountDownLatch(1);
    FakeRecorder recorder = new FakeRecorder();
    InCallButtonUi inCallButtonUi = mock(InCallButtonUi.class);
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            currentCall,
            inCallButtonUi,
            new FakePreferenceSource(
                () -> {
                  preferencesLoadStarted.countDown();
                  return preferencesFuture;
                }),
            permissions -> true);

    startManualRecording(coordinator, currentCall, inCallButtonUi);
    assertThat(preferencesLoadStarted.await(5, TimeUnit.SECONDS)).isTrue();
    currentCall.set(call("call-2"));
    preferencesFuture.set(preferencesBuilder().setRecordingWarningPresented(true).build());

    waitUntil(() -> !isManualStartPending(coordinator));

    assertThat(recorder.started).isFalse();
    verify(inCallButtonUi, never()).requestCallRecordingPermissions(any(String[].class));
  }

  @Test
  public void grantingRecordAudioPermissionStartsRecordingForCurrentCall() throws Exception {
    AtomicReference<DialerCall> currentCall = new AtomicReference<>(call("call-1"));
    FakeRecorder recorder = new FakeRecorder();
    InCallButtonUi inCallButtonUi = mock(InCallButtonUi.class);
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            currentCall,
            inCallButtonUi,
            readyPreferences(),
            permissions -> false);

    startManualRecording(coordinator, currentCall, inCallButtonUi);
    verify(inCallButtonUi, timeout(5000)).requestCallRecordingPermissions(any(String[].class));

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> coordinator.onManualRecordingPermissionsResult(true /* allGranted */));

    assertThat(recorder.awaitStarted()).isTrue();
    assertThat(recorder.startedCallId).isEqualTo("call-1");
  }

  @Test
  public void denyingRecordAudioPermissionDoesNotStartRecording() throws Exception {
    AtomicReference<DialerCall> currentCall = new AtomicReference<>(call("call-1"));
    FakeRecorder recorder = new FakeRecorder();
    InCallButtonUi inCallButtonUi = mock(InCallButtonUi.class);
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            currentCall,
            inCallButtonUi,
            readyPreferences(),
            permissions -> false);

    startManualRecording(coordinator, currentCall, inCallButtonUi);
    verify(inCallButtonUi, timeout(5000)).requestCallRecordingPermissions(any(String[].class));
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> coordinator.onManualRecordingPermissionsResult(false /* allGranted */));

    waitUntil(() -> !isManualStartPending(coordinator));

    assertThat(recorder.started).isFalse();
  }

  private static void startManualRecording(
      CallRecordingCoordinator coordinator,
      AtomicReference<DialerCall> currentCall,
      InCallButtonUi inCallButtonUi) {
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                coordinator.startManualRecording(
                    new ManualRecordingRequest(
                        currentCall::get, () -> null, () -> inCallButtonUi)));
  }

  private static CallRecordingCoordinator newCoordinator(
      FakeRecorder recorder,
      AtomicReference<DialerCall> currentCall,
      InCallButtonUi inCallButtonUi,
      PreferenceSource preferenceSource,
      PermissionChecker permissionChecker) {
    return new CallRecordingCoordinator(
        InstrumentationRegistry.getInstrumentation().getTargetContext(),
        recorder,
        new CallRecordingDependencies(
            new FakeCurrentCalls(currentCall),
            (call, callback) -> callback.onContactInfoComplete(null),
            preferenceSource,
            (call, preferences, requireContactsPermission) -> AutoRecordDecision.ELIGIBLE,
            permissionChecker,
            Dispatchers.getUnconfined(),
            Dispatchers.getUnconfined()));
  }

  private static PreferenceSource readyPreferences() {
    return new FakePreferenceSource(
        () ->
            Futures.immediateFuture(
                preferencesBuilder().setRecordingWarningPresented(true).build()));
  }

  private static CallRecordingPreferences.Builder preferencesBuilder() {
    return CallRecordingPreferences.newBuilder().setSharedPreferencesMigrated(true);
  }

  private static DialerCall call(String callId) {
    DialerCall call = mock(DialerCall.class);
    when(call.getId()).thenReturn(callId);
    when(call.getState()).thenReturn(DialerCallState.ACTIVE);
    when(call.isVideoCall()).thenReturn(false);
    return call;
  }

  private static boolean isManualStartPending(CallRecordingCoordinator coordinator) {
    AtomicBoolean pending = new AtomicBoolean();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> pending.set(coordinator.isManualStartPending()));
    return pending.get();
  }

  private static void waitUntil(BooleanSupplier condition) throws Exception {
    for (int i = 0; i < 100; i++) {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("condition was not met");
  }

  private static final class FakeCurrentCalls implements CurrentCalls {
    private final AtomicReference<DialerCall> currentCall;

    FakeCurrentCalls(AtomicReference<DialerCall> currentCall) {
      this.currentCall = currentCall;
    }

    @Override
    public boolean hasLiveCall() {
      return currentCall.get() != null;
    }

    @Override
    public boolean hasActiveOrBackgroundCall() {
      return currentCall.get() != null;
    }

    @Override
    public CallSnapshot getActiveCall() {
      return null;
    }

    @Override
    public CallSnapshot getCallById(String callId) {
      return null;
    }
  }

  private static final class FakePreferenceSource implements PreferenceSource {
    private final Supplier<ListenableFuture<CallRecordingPreferences>> loader;
    private final CallRecordingPreferences snapshot =
        preferencesBuilder().setRecordingWarningPresented(true).build();

    FakePreferenceSource(Supplier<ListenableFuture<CallRecordingPreferences>> loader) {
      this.loader = loader;
    }

    @Override
    public boolean isSnapshotReady() {
      return false;
    }

    @Override
    public ListenableFuture<CallRecordingPreferences> loadAsync() {
      return loader.get();
    }

    @Override
    public CallRecordingPreferences getSnapshot() {
      return snapshot;
    }
  }

  private static final class FakeRecorder extends CallRecorder {
    private final CountDownLatch startedLatch = new CountDownLatch(1);
    boolean started;
    String startedCallId;

    FakeRecorder() {
      super(false /* addCallListListener */);
    }

    @Override
    public boolean startOrArmManualRecording(DialerCall call) {
      started = true;
      startedCallId = call.getId();
      startedLatch.countDown();
      return true;
    }

    boolean awaitStarted() throws InterruptedException {
      return startedLatch.await(5, TimeUnit.SECONDS);
    }
  }
}
