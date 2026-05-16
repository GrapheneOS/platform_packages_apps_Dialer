package com.android.incallui.call;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.Looper;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.dialer.callrecord.CallRecordingPreferences;
import com.android.incallui.call.AutoCallRecordingEligibility.AutoRecordDecision;
import com.android.incallui.call.state.DialerCallState;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
    DialerCall privateConferenceChild = conferenceChildDialerCall("call-1", null);
    DialerCall knownConferenceCall = conferenceDialerCall("call-2", "+15557654321");

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                coordinator.onCallListChange(
                    callList(privateConferenceChild, knownConferenceCall)));

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
    DialerCall activeDialerCall = dialerCall("call-1", DialerCallState.ACTIVE, null);
    DialerCall heldDialerCall = dialerCall("call-2", DialerCallState.ONHOLD, null);

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> coordinator.onCallListChange(callList(activeDialerCall, heldDialerCall)));

    assertThat(preferenceSource.awaitStarted()).isTrue();
    assertThat(recorder.armedCallId).isNull();
    preferenceSource.complete(preferencesBuilder().setAutoRecordNonContacts(true).build());
    assertThat(recorder.awaitArmed()).isTrue();
    assertThat(recorder.armedCallId).isEqualTo("call-1");
  }

  @Test
  public void activeCallWithHeldCallStartsAutomaticRecordingForSelectedContact() throws Exception {
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
    DialerCall heldDialerCall = dialerCall("call-1", DialerCallState.ONHOLD, null);
    DialerCall activeDialerCall =
        dialerCall("call-2", DialerCallState.ACTIVE, "+15557654321");

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> coordinator.onCallListChange(callList(heldDialerCall, activeDialerCall)));

    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    assertThat(recorder.awaitArmed()).isTrue();
    assertThat(recorder.armedCallId).isEqualTo("call-2");
  }

  @Test
  public void turningOffRecordingForBothCallsPreventsAutomaticRestartAfterSwappingTwice()
      throws Exception {
    FakeRecorder recorder = new FakeRecorder();
    FakeCurrentCalls currentCalls = new FakeCurrentCalls(activeCall("call-2", null));
    CallRecordingCoordinator coordinator =
        newCoordinator(
            recorder,
            currentCalls,
            noContact(),
            preferencesBuilder().setAutoRecordNonContacts(true).build());
    DialerCall heldEligibleCall = dialerCall("call-1", DialerCallState.ONHOLD, null);
    DialerCall activeEligibleCall = dialerCall("call-2", DialerCallState.ACTIVE, null);

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> coordinator.onCallListChange(callList(heldEligibleCall, activeEligibleCall)));
    assertThat(recorder.awaitArmed()).isTrue();
    assertThat(recorder.armedCallId).isEqualTo("call-2");

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              coordinator.stopRecordingFromUi(activeEligibleCall);
              coordinator.stopRecordingFromUi(heldEligibleCall);
              recorder.clearArmedRecording();
              currentCalls.setActiveCall(activeCall("call-1", null));
              coordinator.onCallListChange(
                  callList(
                      dialerCall("call-1", DialerCallState.ACTIVE, null),
                      dialerCall("call-2", DialerCallState.ONHOLD, null)));
            });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    assertThat(recorder.armCount).isEqualTo(1);
    assertThat(recorder.armedCallId).isNull();

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              currentCalls.setActiveCall(activeCall("call-2", null));
              coordinator.onCallListChange(
                  callList(
                      dialerCall("call-1", DialerCallState.ONHOLD, null),
                      dialerCall("call-2", DialerCallState.ACTIVE, null)));
            });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    assertThat(recorder.armCount).isEqualTo(1);
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
                    callList(
                        dialerCall("call-1", DialerCallState.ACTIVE, "+15551234567"),
                        dialerCall("call-2", DialerCallState.ONHOLD, "+15557654321"))));
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    assertThat(recorder.armCount).isEqualTo(0);

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              currentCalls.setActiveCall(activeCall("call-2", "+15557654321"));
              coordinator.onCallListChange(
                  callList(
                      dialerCall("call-1", DialerCallState.ONHOLD, "+15551234567"),
                      dialerCall("call-2", DialerCallState.ACTIVE, "+15557654321")));
            });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    assertThat(recorder.armCount).isEqualTo(0);

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              currentCalls.setActiveCall(activeCall("call-1", "+15551234567"));
              coordinator.onCallListChange(
                  callList(
                      dialerCall("call-1", DialerCallState.ACTIVE, "+15551234567"),
                      dialerCall("call-2", DialerCallState.ONHOLD, "+15557654321")));
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
              coordinator.onDisconnect(dialerCall("call-2", DialerCallState.DISCONNECTED, null));
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
    DialerCall conferenceDialerCall = conferenceDialerCall("call-1", "+15551234567");
    DialerCall activeDialerCall = dialerCall("call-1", DialerCallState.ACTIVE, "+15551234567");

    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> coordinator.onCallListChange(callList(conferenceDialerCall)));
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> coordinator.onCallListChange(callList(activeDialerCall)));
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
    return newCoordinator(
        recorder, currentCalls, contactLookup, new TestPreferenceSource(preferences));
  }

  private static CallRecordingCoordinator newCoordinator(
      FakeRecorder recorder,
      FakeCurrentCalls currentCalls,
      ContactLookup contactLookup,
      PreferenceSource preferenceSource) {
    return new CallRecordingCoordinator(
        InstrumentationRegistry.getInstrumentation().getTargetContext(),
        recorder,
        new CallRecordingDependencies(
            currentCalls,
            contactLookup,
            preferenceSource,
            (call, preferences, requireContactsPermission) -> AutoRecordDecision.ELIGIBLE,
            new FakeSystem(),
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

  private static DialerCall dialerCall(String callId, int state, String number) {
    DialerCall call = mock(DialerCall.class);
    when(call.getId()).thenReturn(callId);
    when(call.getState()).thenReturn(state);
    when(call.getNumber()).thenReturn(number);
    when(call.isVideoCall()).thenReturn(false);
    when(call.isConferenceCall()).thenReturn(false);
    when(call.getParentId()).thenReturn(null);
    return call;
  }

  private static DialerCall conferenceDialerCall(String callId, String number) {
    DialerCall call = dialerCall(callId, DialerCallState.ACTIVE, number);
    when(call.isConferenceCall()).thenReturn(true);
    return call;
  }

  private static DialerCall conferenceChildDialerCall(String callId, String number) {
    DialerCall call = dialerCall(callId, DialerCallState.CONFERENCED, number);
    when(call.getParentId()).thenReturn("conference-1");
    return call;
  }

  private static CallList callList(DialerCall... calls) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      return new TestCallList(calls);
    }
    AtomicReference<CallList> callList = new AtomicReference<>();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> callList.set(new TestCallList(calls)));
    return callList.get();
  }

  private static final class TestCallList extends CallList {
    private final Collection<DialerCall> calls;

    TestCallList(DialerCall... calls) {
      this.calls = Arrays.asList(calls);
    }

    @Override
    public Collection<DialerCall> getAllCalls() {
      return calls;
    }

    @Override
    public DialerCall getActiveCall() {
      return firstCallWithState(DialerCallState.ACTIVE);
    }

    @Override
    public DialerCall getOutgoingCall() {
      for (DialerCall call : getAllCalls()) {
        if (DialerCallState.isDialing(call.getState())) {
          return call;
        }
      }
      return null;
    }

    @Override
    public DialerCall getCallById(String callId) {
      for (DialerCall call : getAllCalls()) {
        if (Objects.equals(call.getId(), callId)) {
          return call;
        }
      }
      return null;
    }

    @Override
    public DialerCall getCallWithStateAndNumber(int state, String number) {
      for (DialerCall call : getAllCalls()) {
        if (call.getState() == state && Objects.equals(call.getNumber(), number)) {
          return call;
        }
      }
      return null;
    }

    @Override
    public boolean hasLiveCall() {
      for (DialerCall call : getAllCalls()) {
        if (DialerCallState.isConnectingOrConnected(call.getState())) {
          return true;
        }
      }
      return false;
    }

    private DialerCall firstCallWithState(int state) {
      for (DialerCall call : getAllCalls()) {
        if (call.getState() == state) {
          return call;
        }
      }
      return null;
    }
  }

  private static final class FakeRecorder extends CallRecorder {
    private final CountDownLatch armed = new CountDownLatch(1);
    String armedCallId;
    boolean armedAutomatically;
    int armCount;

    FakeRecorder() {
      super(
          false /* addCallListListener */,
          new Handler(Looper.getMainLooper()),
          new DefaultCallRecorderServiceBinding());
    }

    @Override
    public void armRecording(String callId, boolean startedAutomatically) {
      armCount++;
      armedCallId = callId;
      armedAutomatically = startedAutomatically;
      armed.countDown();
    }

    @Override
    void clearArmedRecording() {
      armedCallId = null;
    }

    @Override
    void clearAutomaticArmedRecording() {
      if (armedAutomatically) {
        armedCallId = null;
      }
    }

    boolean awaitArmed() throws InterruptedException {
      return armed.await(5, TimeUnit.SECONDS);
    }
  }

  private static final class FakeCurrentCalls implements CurrentCalls {
    private CallSnapshot activeCall;
    private boolean conferenceCallPresent;

    FakeCurrentCalls(CallSnapshot activeCall) {
      this(activeCall, false /* conferenceCallPresent */);
    }

    FakeCurrentCalls(CallSnapshot activeCall, boolean conferenceCallPresent) {
      this.activeCall = activeCall;
      this.conferenceCallPresent = conferenceCallPresent;
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
    public boolean requiresManualRecordingStart() {
      return conferenceCallPresent;
    }

    @Override
    public CallSnapshot getActiveCall() {
      return activeCall;
    }

    @Override
    public CallSnapshot getCallById(String callId) {
      return activeCall != null && activeCall.getId().equals(callId) ? activeCall : null;
    }

    void setActiveCall(CallSnapshot activeCall) {
      this.activeCall = activeCall;
    }

    void setConferenceCallPresent(boolean conferenceCallPresent) {
      this.conferenceCallPresent = conferenceCallPresent;
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

}
