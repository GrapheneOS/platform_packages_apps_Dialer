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

/**
 * Policy tests for automatic recording decisions.
 *
 * <p>Platform process death and Telecom call reconstruction are covered by out of process tests.
 * These tests use fakes only for coordinator and session store contracts that do not require a live
 * Telecom stack.
 */
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
  public void stoppedAutomaticRecordingDoesNotRestartAfterCallIdChanges()
      throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    FakeSessionStore sessionStore = new FakeSessionStore();
    // DialerCall ids are process local. After Dialer restarts, the same live Telecom call can
    // appear with a new id and must still remember that automatic recording was already handled.
    markAutomaticRecordingHandled(sessionStore, activeCall("call-before-restart", null));
    FakeCurrentCalls currentCalls = new FakeCurrentCalls(activeCall("call-after-restart", null));
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            currentCalls,
            noContact(),
            sessionStore,
            preferencesBuilder().setAutoRecordNonContacts(true).build());

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              coordinator.onCallListChange(
                  testCallList(call("call-after-restart", DialerCallState.ACTIVE, null)));
              coordinator.onRecorderServiceConnected();
            });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    assertThat(recorder.armCount).isEqualTo(0);
    assertThat(recorder.armedCallId).isNull();
  }

  @Test
  public void stoppedPrivateCallerRecordingStaysOffUntilLiveCallIdentityIsComplete()
      throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    FakeSessionStore sessionStore = new FakeSessionStore();
    markAutomaticRecordingHandled(sessionStore, activeCall("call-before-restart", 1234L, null));
    FakeCurrentCalls currentCalls =
        new FakeCurrentCalls(activeCall("call-after-restart", 1234L, null));
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            currentCalls,
            noContact(),
            sessionStore,
            preferencesBuilder().setAutoRecordNonContacts(true).build());

    // Private callers have no number, so the persisted session identity depends on Telecom's call
    // creation time. During process restart, CallList can briefly publish the live call before
    // that time is available. Keep the stopped recording latch until the call has a complete
    // session identity.
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                coordinator.onCallListChange(
                    testCallList(call("call-after-restart", DialerCallState.ACTIVE, null, 0L))));
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    assertThat(recorder.armCount).isEqualTo(0);
    assertThat(
            isAutomaticRecordingHandled(
                sessionStore, activeCall("call-after-restart", 1234L, null)))
        .isTrue();

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              coordinator.onCallListChange(
                  testCallList(call("call-after-restart", DialerCallState.ACTIVE, null, 1234L)));
              coordinator.onRecorderServiceConnected();
            });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    assertThat(recorder.armCount).isEqualTo(0);
    assertThat(recorder.armedCallId).isNull();
  }

  @Test
  public void stoppedPrivateCallerRecordingStaysOffWhenAnyLiveCallIdentityIsIncomplete()
      throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    FakeSessionStore sessionStore = new FakeSessionStore();
    markAutomaticRecordingHandled(sessionStore, activeCall("call-before-restart", 1234L, null));
    FakeCurrentCalls currentCalls =
        new FakeCurrentCalls(activeCall("call-after-restart", 1234L, null));
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            currentCalls,
            noContact(),
            sessionStore,
            preferencesBuilder().setAutoRecordNonContacts(true).build());

    // A visible second call already has a complete session identity, but that does not prove the
    // private live call is a different session. Keep the latch until every live call has a
    // complete session identity.
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                coordinator.onCallListChange(
                    testCallList(
                        call("call-after-restart", DialerCallState.ACTIVE, null, 0L),
                        call("second-call", DialerCallState.ONHOLD, "+15557654321", 5678L))));
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              coordinator.onCallListChange(
                  testCallList(call("call-after-restart", DialerCallState.ACTIVE, null, 1234L)));
              coordinator.onRecorderServiceConnected();
            });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    assertThat(recorder.armCount).isEqualTo(0);
    assertThat(recorder.armedCallId).isNull();
  }

  @Test
  public void stoppedAutomaticRecordingSurvivesInitialEmptyCallListAfterProcessRestart()
      throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    FakeSessionStore sessionStore = new FakeSessionStore();
    markAutomaticRecordingHandled(sessionStore, activeCall("call-before-restart", 1234L, null));
    FakeCurrentCalls currentCalls = new FakeCurrentCalls((CallSnapshot) null);
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            currentCalls,
            noContact(),
            sessionStore,
            preferencesBuilder().setAutoRecordNonContacts(true).build());

    // Process restart during a live call can deliver an empty CallList before Telecom redelivers
    // the call. The restored call may have a new call id but the same creation time.
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> coordinator.onCallListChange(testCallList()));
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    currentCalls.setActiveCall(activeCall("call-after-restart", 1234L, null));
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              coordinator.onCallListChange(
                  testCallList(call("call-after-restart", DialerCallState.ACTIVE, null, 1234L)));
              coordinator.onRecorderServiceConnected();
            });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    assertThat(recorder.armCount).isEqualTo(0);
    assertThat(recorder.armedCallId).isNull();
  }

  @Test
  public void automaticRecordingCanRestartAfterProcessRecreatesCallId() throws Exception {
    FakeRecorder firstRecorder = new FakeRecorder();
    FakeSessionStore sessionStore = new FakeSessionStore();
    CallRecordingCoordinator firstCoordinator =
        newCoordinator(
            firstRecorder,
            new FakeCurrentCalls(activeCall("call-before-restart", 1234L, null)),
            noContact(),
            sessionStore,
            preferencesBuilder().setAutoRecordNonContacts(true).build());

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(firstCoordinator::onRecorderServiceConnected);

    assertThat(firstRecorder.awaitArmed()).isTrue();
    assertThat(firstRecorder.armedCallId).isEqualTo("call-before-restart");
    assertThat(
            isAutomaticRecordingHandled(
                sessionStore, activeCall("call-before-restart", 1234L, null)))
        .isFalse();

    FakeRecorder recreatedRecorder = new FakeRecorder();
    CallRecordingCoordinator recreatedCoordinator =
        newCoordinator(
            recreatedRecorder,
            new FakeCurrentCalls(activeCall("call-after-restart", 1234L, null)),
            noContact(),
            sessionStore,
            preferencesBuilder().setAutoRecordNonContacts(true).build());

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(recreatedCoordinator::onRecorderServiceConnected);

    assertThat(recreatedRecorder.awaitArmed()).isTrue();
    assertThat(recreatedRecorder.armedCallId).isEqualTo("call-after-restart");
  }

  @Test
  public void pendingAutomaticRecordingDoesNotReevaluateBeforeRecorderStarts()
      throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            new FakeCurrentCalls(activeCall("call-1", 1234L, null)),
            noContact(),
            preferencesBuilder().setAutoRecordNonContacts(true).build());

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(coordinator::onRecorderServiceConnected);
    assertThat(recorder.awaitArmed()).isTrue();

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(coordinator::onRecorderServiceConnected);
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    assertThat(recorder.armCount).isEqualTo(1);
    assertThat(recorder.armedCallId).isEqualTo("call-1");
  }

  @Test
  public void activeAutomaticRecordingRestartsAfterRecorderServiceReconnect()
      throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            new FakeCurrentCalls(activeCall("call-1", 1234L, null)),
            noContact(),
            preferencesBuilder().setAutoRecordNonContacts(true).build());

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(coordinator::onRecorderServiceConnected);

    assertThat(recorder.awaitArmed()).isTrue();
    assertThat(recorder.armCount).isEqualTo(1);

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              recorder.clearArmedRecording();
              coordinator.onRecorderServiceConnected();
            });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    assertThat(recorder.armCount).isEqualTo(2);
    assertThat(recorder.armedCallId).isEqualTo("call-1");
    assertThat(recorder.armedAutomatically).isTrue();
  }

  @Test
  public void stoppedAutomaticRecordingDoesNotRestartAfterRecorderServiceReconnect()
      throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            new FakeCurrentCalls(activeCall("call-1", 1234L, null)),
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
              coordinator.onRecorderServiceConnected();
            });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    assertThat(recorder.armCount).isEqualTo(1);
    assertThat(recorder.armedCallId).isNull();
  }

  @Test
  public void stoppedAutomaticRecordingStateClearsWhenCallsEnd() throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    FakeSessionStore sessionStore = new FakeSessionStore();
    FakeCurrentCalls currentCalls = new FakeCurrentCalls(activeCall("call-1", null));
    markAutomaticRecordingHandled(sessionStore, activeCall("call-1", null));
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            currentCalls,
            noContact(),
            sessionStore,
            preferencesBuilder().setAutoRecordNonContacts(true).build());

    // Empty CallList snapshots can happen while Telecom is still redelivering calls. The
    // coordinator only treats an empty snapshot as call end after observing a live call.
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                coordinator.onCallListChange(
                    testCallList(call("call-1", DialerCallState.ACTIVE, null))));
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    currentCalls.setActiveCall(null);
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> coordinator.onCallListChange(testCallList()));

    assertThat(isAutomaticRecordingHandled(sessionStore, activeCall("call-1", null))).isFalse();
    assertThat(sessionStore.clearCount).isEqualTo(1);
  }

  @Test
  public void stoppedAutomaticRecordingStateDoesNotAffectFreshCall() throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    FakeSessionStore sessionStore = new FakeSessionStore();
    markAutomaticRecordingHandled(sessionStore, activeCall("old-call", 1234L, null));
    FakeCurrentCalls currentCalls = new FakeCurrentCalls(activeCall("new-call", 5678L, null));
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            currentCalls,
            noContact(),
            sessionStore,
            preferencesBuilder().setAutoRecordNonContacts(true).build());

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                coordinator.onCallListChange(
                    testCallList(call("new-call", DialerCallState.ACTIVE, null, 5678L))));

    assertThat(isAutomaticRecordingHandled(sessionStore, activeCall("old-call", 1234L, null)))
        .isFalse();
    assertThat(recorder.awaitArmed()).isTrue();
    assertThat(recorder.armedCallId).isEqualTo("new-call");
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
    return newCoordinator(
        recorder,
        currentCalls,
        contactLookup,
        preferenceSource,
        new FakeSessionStore(),
        system);
  }

  private static CallRecordingCoordinator newCoordinator(
      FakeRecorder recorder,
      CurrentCalls currentCalls,
      ContactLookup contactLookup,
      FakeSessionStore sessionStore,
      CallRecordingPreferences preferences) {
    return newCoordinator(
        recorder, currentCalls, contactLookup, new TestPreferenceSource(preferences), sessionStore);
  }

  private static CallRecordingCoordinator newCoordinator(
      FakeRecorder recorder,
      CurrentCalls currentCalls,
      ContactLookup contactLookup,
      PreferenceSource preferenceSource,
      FakeSessionStore sessionStore) {
    return newCoordinator(
        recorder, currentCalls, contactLookup, preferenceSource, sessionStore, new FakeSystem());
  }

  private static CallRecordingCoordinator newCoordinator(
      FakeRecorder recorder,
      CurrentCalls currentCalls,
      ContactLookup contactLookup,
      PreferenceSource preferenceSource,
      FakeSessionStore sessionStore,
      FakeSystem system) {
    return new CallRecordingCoordinator(
        InstrumentationRegistry.getInstrumentation().getTargetContext(),
        recorder,
        new CallRecordingDependencies(
            currentCalls,
            contactLookup,
            preferenceSource,
            sessionStore,
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

  private static CallSnapshot activeCall(String callId, long creationTime, String number) {
    return new CallSnapshot(
        callId,
        number,
        DialerCallState.ACTIVE,
        false /* isVideoCall */,
        false /* isConferenceCall */,
        null /* dialerCall */,
        creationTime);
  }

  private static CallSnapshot activeCall(String callId, boolean isConferenceCall, String number) {
    return new CallSnapshot(
        callId,
        number,
        DialerCallState.ACTIVE,
        false /* isVideoCall */,
        isConferenceCall,
        null /* dialerCall */,
        1234L);
  }

  private static CallSnapshot callSnapshot(String callId, int state, String number) {
    return new CallSnapshot(
        callId,
        number,
        state,
        false /* isVideoCall */,
        false /* isConferenceCall */,
        null /* dialerCall */,
        1234L);
  }

  private static void markAutomaticRecordingHandled(
      FakeSessionStore sessionStore, CallSnapshot call) throws Exception {
    sessionStore.markAutomaticRecordingHandledForTesting(call);
  }

  private static boolean isAutomaticRecordingHandled(
      FakeSessionStore sessionStore, CallSnapshot call) throws Exception {
    return sessionStore.isAutomaticRecordingHandledForTesting(call);
  }

}
