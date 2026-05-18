package com.android.incallui.call;

import static com.android.incallui.call.CallRecordingTestSupport.call;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.android.dialer.callrecord.CallRecordingPreferences;
import com.android.incallui.call.CallRecordingTestSupport.FakeCurrentCalls;
import com.android.incallui.call.CallRecordingTestSupport.FakeRecorder;
import com.android.incallui.call.CallRecordingTestSupport.FakeSystem;
import com.android.incallui.call.AutoCallRecordingEligibility.AutoRecordDecision;
import com.android.incallui.incall.protocol.InCallButtonUi;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import kotlinx.coroutines.Dispatchers;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CallRecordingCoordinatorManualTest {

  @Test
  public void manualRecordingDoesNotStartAfterCurrentCallChanges() throws Exception {
    AtomicReference<DialerCall> currentCall = new AtomicReference<>(call("call-1"));
    BlockingTestPreferenceSource preferenceSource = new BlockingTestPreferenceSource();
    FakeRecorder recorder = new FakeRecorder();
    InCallButtonUi inCallButtonUi = mock(InCallButtonUi.class);
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            currentCall,
            preferenceSource,
            new FakeSystem(true /* hasPermissions */));

    startManualRecording(coordinator, currentCall, inCallButtonUi);
    assertThat(preferenceSource.awaitStarted()).isTrue();
    currentCall.set(call("call-2"));
    preferenceSource.complete(preferencesBuilder().setRecordingWarningPresented(true).build());

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
            readyPreferences(),
            new FakeSystem(false /* hasPermissions */));

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
            readyPreferences(),
            new FakeSystem(false /* hasPermissions */));

    startManualRecording(coordinator, currentCall, inCallButtonUi);
    verify(inCallButtonUi, timeout(5000)).requestCallRecordingPermissions(any(String[].class));
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> coordinator.onManualRecordingPermissionsResult(false /* allGranted */));

    waitUntil(() -> !isManualStartPending(coordinator));

    assertThat(recorder.started).isFalse();
  }

  @Test
  public void pressingRecordBeforeUnlockShowsMessageAndDoesNotStartRecording() {
    AtomicReference<DialerCall> currentCall = new AtomicReference<>(call("call-1"));
    CountingTestPreferenceSource preferenceSource =
        new CountingTestPreferenceSource(
            preferencesBuilder().setRecordingWarningPresented(true).build());
    AtomicBoolean lockedMessageShown = new AtomicBoolean();
    FakeRecorder recorder = new FakeRecorder();
    InCallButtonUi inCallButtonUi = mock(InCallButtonUi.class);
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            currentCall,
            preferenceSource,
            new FakeSystem(
                true /* hasPermissions */,
                false /* userUnlocked */,
                () -> lockedMessageShown.set(true)));

    startManualRecording(coordinator, currentCall, inCallButtonUi);

    assertThat(lockedMessageShown.get()).isTrue();
    assertThat(preferenceSource.wasLoaded()).isFalse();
    assertThat(recorder.started).isFalse();
    verify(inCallButtonUi, never()).requestCallRecordingPermissions(any(String[].class));
  }

  @Test
  public void pressingRecordAfterUnlockRequestsRecorderBinding() throws Exception {
    AtomicReference<DialerCall> currentCall = new AtomicReference<>(call("call-1"));
    FakeRecorder recorder = new FakeRecorder();
    InCallButtonUi inCallButtonUi = mock(InCallButtonUi.class);
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            currentCall,
            readyPreferences(),
            new FakeSystem(true /* hasPermissions */));

    startManualRecording(coordinator, currentCall, inCallButtonUi);

    assertThat(recorder.awaitStarted()).isTrue();
    assertThat(recorder.bindRequestCount).isEqualTo(1);
    assertThat(recorder.startedCallId).isEqualTo("call-1");
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
      PreferenceSource preferenceSource,
      CallRecordingSystem system) {
    return new CallRecordingCoordinator(
        InstrumentationRegistry.getInstrumentation().getTargetContext(),
        recorder,
        new CallRecordingDependencies(
            new FakeCurrentCalls(currentCall),
            new TestContactLookup(null),
            preferenceSource,
            new FakeSessionStore(),
            (call, preferences, requireContactsPermission) -> AutoRecordDecision.ELIGIBLE,
            system,
            Dispatchers.getUnconfined(),
            Dispatchers.getUnconfined()));
  }

  private static PreferenceSource readyPreferences() {
    return new TestPreferenceSource(
        preferencesBuilder().setRecordingWarningPresented(true).build());
  }

  private static CallRecordingPreferences.Builder preferencesBuilder() {
    return CallRecordingPreferences.newBuilder().setSharedPreferencesMigrated(true);
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

}
