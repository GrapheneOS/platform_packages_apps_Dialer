package com.android.incallui.call;

import static com.android.incallui.call.CallRecordingTestSupport.call;
import static com.android.incallui.call.CallRecordingTestSupport.conferenceCall;
import static com.android.incallui.call.CallRecordingTestSupport.conferenceChildCall;
import static com.android.incallui.call.CallRecordingTestSupport.testCallList;
import static com.google.common.truth.Truth.assertThat;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.dialer.callrecord.CallRecordingPreferences;
import com.android.incallui.call.AutoCallRecordingEligibility.AutoRecordDecision;
import com.android.incallui.call.CallRecordingTestSupport.FakeCurrentCalls;
import com.android.incallui.call.CallRecordingTestSupport.FakeRecorder;
import com.android.incallui.call.CallRecordingTestSupport.FakeSystem;
import com.android.incallui.call.state.DialerCallState;
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
  public void conferenceCallDoesNotStartAutomaticRecordingForSelectedContact() throws Exception {
    FakeRecorder recorder = new FakeRecorder();

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                newCoordinator(
                        recorder,
                        new FakeCurrentCalls(activeCall(true /* isConferenceCall */)),
                        contactLookup(new ContactInfo(true /* isLocalContact */, "+15551234567")),
                        preferencesBuilder()
                            .setAutoRecordSelectedNumbersEnabled(true)
                            .addAutoRecordSelectedNumbers("+15551234567")
                            .build())
                    .onRecorderServiceConnected());

    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    assertThat(recorder.armedCallId).isNull();
  }

  @Test
  public void conferenceWithPrivateAndKnownCallersDoesNotStartAutomaticRecording()
      throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            new FakeCurrentCalls(activeCall("call-2", "+15557654321")),
            contactLookup(new ContactInfo(true /* isLocalContact */, "+15557654321")),
            preferencesBuilder()
                .setAutoRecordNonContacts(true)
                .setAutoRecordSelectedNumbersEnabled(true)
                .addAutoRecordSelectedNumbers("+15557654321")
                .build());
    DialerCall privateConferenceChild = conferenceChildCall("call-1", null);
    DialerCall knownConferenceCall = conferenceCall("call-2", "+15557654321");

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                coordinator.onCallListChange(
                    testCallList(privateConferenceChild, knownConferenceCall)));

    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    assertThat(recorder.armedCallId).isNull();
  }

  @Test
  public void addingEligibleCallerToNonEligibleCallAsConferenceDoesNotStartAutomaticRecording()
      throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    FakeCurrentCalls currentCalls = new FakeCurrentCalls(activeCall("call-1", "+15550000000"));
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            currentCalls,
            new MatchingNumberTestContactLookup(),
            preferencesBuilder()
                .setAutoRecordSelectedNumbersEnabled(true)
                .addAutoRecordSelectedNumbers("+15557654321")
                .build());
    DialerCall nonEligibleCall = call("call-1", DialerCallState.ACTIVE, "+15550000000");
    DialerCall nonEligibleConferenceChild = conferenceChildCall("call-1", "+15550000000");
    DialerCall eligibleConferenceCall = conferenceCall("call-2", "+15557654321");

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> coordinator.onCallListChange(testCallList(nonEligibleCall)));
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    assertThat(recorder.armedCallId).isNull();

    // The joined caller would normally match automatic rules, but a conference needs a fresh
    // manual record press because the participant set changed.
    currentCalls.setActiveCall(activeCall("call-2", true /* isConferenceCall */, "+15557654321"));
    currentCalls.setConferenceCallPresent(true);
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                coordinator.onCallListChange(
                    testCallList(nonEligibleConferenceChild, eligibleConferenceCall)));
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    assertThat(recorder.armCount).isEqualTo(0);
    assertThat(recorder.armedCallId).isNull();
  }

  @Test
  public void activeCallWithHeldCallStartsAutomaticRecordingAfterPreferencesLoadCompletes()
      throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    BlockingTestPreferenceSource preferenceSource = new BlockingTestPreferenceSource();
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            new FakeCurrentCalls(activeCall(false /* isConferenceCall */, null)),
            noContact(),
            preferenceSource);
    DialerCall activeDialerCall = call("call-1", DialerCallState.ACTIVE, null);
    DialerCall heldDialerCall = call("call-2", DialerCallState.ONHOLD, null);

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                coordinator.onCallListChange(testCallList(activeDialerCall, heldDialerCall)));

    assertThat(preferenceSource.awaitStarted()).isTrue();
    assertThat(recorder.armedCallId).isNull();
    preferenceSource.complete(preferencesBuilder().setAutoRecordNonContacts(true).build());
    assertThat(recorder.awaitArmed()).isTrue();
    assertThat(recorder.armedCallId).isEqualTo("call-1");
  }

  @Test
  public void connectingOutgoingCallCanStartAutomaticRecording() throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            new FakeCurrentCalls(callSnapshot("call-1", DialerCallState.CONNECTING, null)),
            noContact(),
            preferencesBuilder().setAutoRecordNonContacts(true).build());

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                coordinator.onCallListChange(
                    testCallList(call("call-1", DialerCallState.CONNECTING, null))));

    assertThat(recorder.awaitArmed()).isTrue();
    assertThat(recorder.armedCallId).isEqualTo("call-1");
  }

  @Test
  public void delayedAutomaticDecisionCanFinishWhileOutgoingCallIsConnecting()
      throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    BlockingTestContactLookup delayedContactLookup = new BlockingTestContactLookup();
    FakeCurrentCalls currentCalls =
        new FakeCurrentCalls(callSnapshot("call-1", DialerCallState.DIALING, null));
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            currentCalls,
            delayedContactLookup,
            preferencesBuilder().setAutoRecordNonContacts(true).build());

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                coordinator.onCallListChange(
                    testCallList(call("call-1", DialerCallState.DIALING, null))));
    assertThat(delayedContactLookup.awaitStarted()).isTrue();
    currentCalls.setActiveCall(callSnapshot("call-1", DialerCallState.CONNECTING, null));
    delayedContactLookup.complete(null);

    assertThat(recorder.awaitArmed()).isTrue();
    assertThat(recorder.armedCallId).isEqualTo("call-1");
  }

  @Test
  public void activeCallWithHeldCallStartsAutomaticRecordingForSelectedContact()
      throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            new FakeCurrentCalls(activeCall("call-2", "+15557654321")),
            contactLookup(new ContactInfo(true /* isLocalContact */, "+15557654321")),
            preferencesBuilder()
                .setAutoRecordSelectedNumbersEnabled(true)
                .addAutoRecordSelectedNumbers("+15557654321")
                .build());
    DialerCall heldDialerCall = call("call-1", DialerCallState.ONHOLD, null);
    DialerCall activeDialerCall =
        call("call-2", DialerCallState.ACTIVE, "+15557654321");

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                coordinator.onCallListChange(testCallList(heldDialerCall, activeDialerCall)));

    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    assertThat(recorder.awaitArmed()).isTrue();
    assertThat(recorder.armedCallId).isEqualTo("call-2");
  }

  @Test
  public void delayedAutomaticDecisionDoesNotRecordAfterUserStopsRecording()
      throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    BlockingTestContactLookup delayedContactLookup = new BlockingTestContactLookup();
    FakeCurrentCalls currentCalls = new FakeCurrentCalls(activeCall("call-1", null));
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            currentCalls,
            delayedContactLookup,
            preferencesBuilder().setAutoRecordNonContacts(true).build());

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                coordinator.onCallListChange(
                    testCallList(call("call-1", DialerCallState.ACTIVE, null))));
    assertThat(delayedContactLookup.awaitStarted()).isTrue();
    currentCalls.setActiveCall(activeCall("call-2", "+15557654321"));
    DialerCall activeSecondCall =
        call("call-2", DialerCallState.ACTIVE, "+15557654321");
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> coordinator.stopRecordingFromUi(activeSecondCall));
    currentCalls.setActiveCall(activeCall("call-1", null));
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                coordinator.onCallListChange(
                    testCallList(call("call-1", DialerCallState.ACTIVE, null))));

    delayedContactLookup.complete(null);
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    assertThat(recorder.armCount).isEqualTo(0);
    assertThat(recorder.armedCallId).isNull();
  }

  @Test
  public void nonEligibleCallDoesNotStartAutomaticRecordingAfterSwapBack() throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    FakeCurrentCalls currentCalls = new FakeCurrentCalls(activeCall("call-1", "+15551234567"));
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            currentCalls,
            new MatchingNumberTestContactLookup(),
            preferencesBuilder().setAutoRecordNonContacts(true).build());

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                coordinator.onCallListChange(
                    testCallList(
                        call("call-1", DialerCallState.ACTIVE, "+15551234567"),
                        call("call-2", DialerCallState.ONHOLD, "+15557654321"))));
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    assertThat(recorder.armCount).isEqualTo(0);

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              currentCalls.setActiveCall(activeCall("call-2", "+15557654321"));
              coordinator.onCallListChange(
                  testCallList(
                      call("call-1", DialerCallState.ONHOLD, "+15551234567"),
                      call("call-2", DialerCallState.ACTIVE, "+15557654321")));
            });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    assertThat(recorder.armCount).isEqualTo(0);

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              currentCalls.setActiveCall(activeCall("call-1", "+15551234567"));
              coordinator.onCallListChange(
                  testCallList(
                      call("call-1", DialerCallState.ACTIVE, "+15551234567"),
                      call("call-2", DialerCallState.ONHOLD, "+15557654321")));
            });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    assertThat(recorder.armCount).isEqualTo(0);
    assertThat(recorder.armedCallId).isNull();
  }

  @Test
  public void automaticRecordingDoesNotRestartAfterConferenceEnds() throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    FakeCurrentCalls currentCalls = new FakeCurrentCalls(activeCall());
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            currentCalls,
            noContact(),
            preferencesBuilder().setAutoRecordNonContacts(true).build());

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(coordinator::onRecorderServiceConnected);
    assertThat(recorder.awaitArmed()).isTrue();
    currentCalls.setConferenceCallPresent(true);
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(coordinator::onRecorderServiceConnected);
    currentCalls.setConferenceCallPresent(false);
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(coordinator::onRecorderServiceConnected);
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    assertThat(recorder.armCount).isEqualTo(1);
    assertThat(recorder.armedCallId).isNull();
  }

  @Test
  public void userStoppedAutomaticRecordingDoesNotRestartAfterConferenceEnds() throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    FakeCurrentCalls currentCalls = new FakeCurrentCalls(activeCall());
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            currentCalls,
            noContact(),
            preferencesBuilder().setAutoRecordNonContacts(true).build());

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(coordinator::onRecorderServiceConnected);
    assertThat(recorder.awaitArmed()).isTrue();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              coordinator.setCallRecordingDisabledByUser("call-1", true /* disabled */);
              recorder.clearArmedRecording();
              currentCalls.setConferenceCallPresent(true);
              coordinator.onRecorderServiceConnected();
              currentCalls.setConferenceCallPresent(false);
              coordinator.onDisconnect(call("call-2", DialerCallState.DISCONNECTED, null));
              coordinator.onRecorderServiceConnected();
            });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    assertThat(recorder.armCount).isEqualTo(1);
    assertThat(recorder.armedCallId).isNull();
  }

  @Test
  public void conferenceCallDoesNotStartAutomaticRecordingAfterBecomingSingleCall()
      throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    FakeCurrentCalls currentCalls = new FakeCurrentCalls(activeCall());
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            currentCalls,
            noContact(),
            preferencesBuilder().setAutoRecordNonContacts(true).build());
    DialerCall conferenceDialerCall =
        conferenceCall("call-1", "+15551234567");
    DialerCall activeDialerCall =
        call("call-1", DialerCallState.ACTIVE, "+15551234567");

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> coordinator.onCallListChange(testCallList(conferenceDialerCall)));
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> coordinator.onCallListChange(testCallList(activeDialerCall)));
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

  @Test
  public void automaticRecordingWaitsUntilUserUnlocks() throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    FakeSystem system = new FakeSystem();
    system.setUserUnlocked(false);
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            new FakeCurrentCalls(activeCall()),
            noContact(),
            new TestPreferenceSource(
                preferencesBuilder().setAutoRecordNonContacts(true).build()),
            system);

    InstrumentationRegistry.getInstrumentation().runOnMainSync(coordinator::onRecorderServiceConnected);
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    assertThat(recorder.armCount).isEqualTo(0);

    system.setUserUnlocked(true);
    InstrumentationRegistry.getInstrumentation().runOnMainSync(coordinator::onRecorderServiceConnected);

    assertThat(recorder.awaitArmed()).isTrue();
    assertThat(recorder.armedCallId).isEqualTo("call-1");
  }

  private static ContactLookup noContact() {
    return contactLookup(null);
  }

  private static ContactLookup contactLookup(ContactInfo contactInfo) {
    return new TestContactLookup(contactInfo);
  }

  private static CallRecordingCoordinator newCoordinator(
      FakeRecorder recorder,
      CurrentCalls currentCalls,
      ContactLookup contactLookup,
      CallRecordingPreferences preferences) {
    return newCoordinator(
        recorder, currentCalls, contactLookup, new TestPreferenceSource(preferences));
  }

  private static CallRecordingCoordinator newCoordinator(
      FakeRecorder recorder,
      CurrentCalls currentCalls,
      ContactLookup contactLookup,
      PreferenceSource preferenceSource) {
    return newCoordinator(
        recorder,
        currentCalls,
        contactLookup,
        preferenceSource,
        new FakeSystem());
  }

  private static CallRecordingCoordinator newCoordinator(
      FakeRecorder recorder,
      CurrentCalls currentCalls,
      ContactLookup contactLookup,
      PreferenceSource preferenceSource,
      FakeSystem system) {
    return new CallRecordingCoordinator(
        InstrumentationRegistry.getInstrumentation().getTargetContext(),
        recorder,
        new CallRecordingDependencies(
            currentCalls,
            contactLookup,
            preferenceSource,
            (call, preferences, requireContactsPermission) -> AutoRecordDecision.ELIGIBLE,
            system,
            Dispatchers.getUnconfined(),
            Dispatchers.getUnconfined()));
  }

  private static CallRecordingPreferences.Builder preferencesBuilder() {
    return CallRecordingPreferences.newBuilder()
        .setSharedPreferencesMigrated(true)
        .setRecordingWarningPresented(true);
  }

  private static CallSnapshot activeCall() {
    return activeCall(false /* isConferenceCall */);
  }

  private static CallSnapshot activeCall(boolean isConferenceCall) {
    return activeCall(isConferenceCall, "+15551234567");
  }

  private static CallSnapshot activeCall(boolean isConferenceCall, String number) {
    return activeCall("call-1", isConferenceCall, number);
  }

  private static CallSnapshot activeCall(String callId, String number) {
    return activeCall(callId, false /* isConferenceCall */, number);
  }

  private static CallSnapshot activeCall(String callId, boolean isConferenceCall, String number) {
    return new CallSnapshot(
        callId,
        number,
        DialerCallState.ACTIVE,
        false /* isVideoCall */,
        isConferenceCall,
        null /* dialerCall */);
  }

  private static CallSnapshot callSnapshot(String callId, int state, String number) {
    return new CallSnapshot(
        callId,
        number,
        state,
        false /* isVideoCall */,
        false /* isConferenceCall */,
        null /* dialerCall */);
  }

}
