package com.android.incallui.call;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.dialer.callrecord.CallRecordingPreferences;
import com.android.incallui.call.AutoCallRecordingEligibility.AutoRecordDecision;
import com.android.incallui.call.state.DialerCallState;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kotlinx.coroutines.Dispatchers;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CallRecordingCoordinatorTest {

  @Test
  public void turningOffIncomingSwitchPreventsAutomaticRecording() throws Exception {
    FakeRecorder recorder = new FakeRecorder();

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              CallRecordingCoordinator coordinator =
                  newCoordinator(
                      recorder,
                      new FakeCurrentCalls(activeCall()),
                      noContact(),
                      preferencesBuilder().setAutoRecordNonContacts(true).build());

              coordinator.setIncomingCallRecordingEnabled("call-1", false /* enabled */);
              coordinator.onRecorderServiceConnected();
            });

    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    assertThat(recorder.armedCallId).isNull();
  }

  @Test
  public void turningRecordingBackOnAllowsAutomaticRecording() throws Exception {
    FakeRecorder recorder = new FakeRecorder();

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              CallRecordingCoordinator coordinator =
                  newCoordinator(
                      recorder,
                      new FakeCurrentCalls(activeCall()),
                      noContact(),
                      preferencesBuilder().setAutoRecordNonContacts(true).build());

              coordinator.setCallRecordingDisabledByUser("call-1", true /* disabled */);
              coordinator.setCallRecordingDisabledByUser("call-1", false /* disabled */);
              coordinator.onRecorderServiceConnected();
            });

    assertThat(recorder.awaitArmed()).isTrue();
    assertThat(recorder.armedCallId).isEqualTo("call-1");
    assertThat(recorder.armedAutomatically).isTrue();
  }

  @Test
  public void unknownCallerPolicyStartsAutomaticRecording() throws Exception {
    FakeRecorder recorder = new FakeRecorder();

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                newCoordinator(
                        recorder,
                        new FakeCurrentCalls(activeCall()),
                        noContact(),
                        preferencesBuilder().setAutoRecordNonContacts(true).build())
                    .onRecorderServiceConnected());

    assertThat(recorder.awaitArmed()).isTrue();
    assertThat(recorder.armedCallId).isEqualTo("call-1");
    assertThat(recorder.armedAutomatically).isTrue();
  }

  @Test
  public void contactLookupFailureDoesNotStartAutomaticRecording() throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    ContactLookup failingLookup =
        new FailingTestContactLookup(new RuntimeException("lookup failed"));

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                newCoordinator(
                        recorder,
                        new FakeCurrentCalls(activeCall()),
                        failingLookup,
                        preferencesBuilder().setAutoRecordNonContacts(true).build())
                    .onRecorderServiceConnected());
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    assertThat(recorder.armedCallId).isNull();
  }

  @Test
  public void selectedContactPolicyStartsAutomaticRecordingWhenNumberMatches() throws Exception {
    FakeRecorder recorder = new FakeRecorder();

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                newCoordinator(
                        recorder,
                        new FakeCurrentCalls(activeCall()),
                        contactLookup(new ContactInfo(true /* isLocalContact */, "+15551234567")),
                        preferencesBuilder()
                            .setAutoRecordSelectedNumbersEnabled(true)
                            .addAutoRecordSelectedNumbers("+15551234567")
                            .build())
                    .onRecorderServiceConnected());

    assertThat(recorder.awaitArmed()).isTrue();
    assertThat(recorder.armedCallId).isEqualTo("call-1");
    assertThat(recorder.armedAutomatically).isTrue();
  }

  @Test
  public void incomingSwitchStartsRecordingWithoutWaitingForContactLookup() throws Exception {
    FakeRecorder recorder = new FakeRecorder();

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              CallRecordingCoordinator coordinator =
                  newCoordinator(
                      recorder,
                      new FakeCurrentCalls(activeCall()),
                      new BlockingTestContactLookup(),
                      preferencesBuilder().build());

              coordinator.setIncomingCallRecordingEnabled("call-1", true /* enabled */);
            });

    assertThat(recorder.awaitArmed()).isTrue();
    assertThat(recorder.armedCallId).isEqualTo("call-1");
    assertThat(recorder.armedAutomatically).isTrue();
  }

  private static ContactLookup noContact() {
    return contactLookup(null);
  }

  private static ContactLookup contactLookup(ContactInfo contactInfo) {
    return new TestContactLookup(contactInfo);
  }

  private static CallRecordingCoordinator newCoordinator(
      FakeRecorder recorder,
      FakeCurrentCalls currentCalls,
      ContactLookup contactLookup,
      CallRecordingPreferences preferences) {
    return new CallRecordingCoordinator(
        InstrumentationRegistry.getInstrumentation().getTargetContext(),
        recorder,
        new CallRecordingDependencies(
            currentCalls,
            contactLookup,
            new TestPreferenceSource(preferences),
            (call, preferencesSnapshot, requireContactsPermission) -> AutoRecordDecision.ELIGIBLE,
            Dispatchers.getUnconfined(),
            Dispatchers.getUnconfined()));
  }

  private static CallRecordingPreferences.Builder preferencesBuilder() {
    return CallRecordingPreferences.newBuilder()
        .setSharedPreferencesMigrated(true)
        .setRecordingWarningPresented(true);
  }

  private static CallSnapshot activeCall() {
    return new CallSnapshot(
        "call-1",
        "+15551234567",
        DialerCallState.ACTIVE,
        false /* isVideoCall */,
        false /* isConferenceCall */,
        null /* dialerCall */);
  }

  private static final class FakeRecorder extends CallRecorder {
    private final CountDownLatch armed = new CountDownLatch(1);
    String armedCallId;
    boolean armedAutomatically;

    FakeRecorder() {
      super(false /* addCallListListener */);
    }

    @Override
    public void armRecording(String callId, boolean startedAutomatically) {
      armedCallId = callId;
      armedAutomatically = startedAutomatically;
      armed.countDown();
    }

    boolean awaitArmed() throws InterruptedException {
      return armed.await(5, TimeUnit.SECONDS);
    }
  }

  private static final class FakeCurrentCalls implements CurrentCalls {
    private final CallSnapshot activeCall;

    FakeCurrentCalls(CallSnapshot activeCall) {
      this.activeCall = activeCall;
    }

    @Override
    public boolean hasLiveCall() {
      return activeCall != null;
    }

    @Override
    public boolean hasActiveOrBackgroundCall() {
      return activeCall != null;
    }

    @Override
    public CallSnapshot getActiveCall() {
      return activeCall;
    }

    @Override
    public CallSnapshot getCallById(String callId) {
      return activeCall != null && activeCall.getId().equals(callId) ? activeCall : null;
    }
  }

}
