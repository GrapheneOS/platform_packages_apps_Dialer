package com.android.dialer.integration;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.net.Uri;
import android.telecom.PhoneAccount;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.incallui.call.DialerCall;
import com.android.incallui.call.state.DialerCallState;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Automatic call recording integration tests for real Telecom call transitions.
 *
 * <p>These tests assert through production incallui state. UI control wiring belongs in separate
 * UiAutomator tests so this class can stay focused on real Telecom call transitions.
 */
@RunWith(AndroidJUnit4.class)
public final class AutoCallRecordingTelecomIntegrationTest
    extends AutoCallRecordingIntegrationTestBase {

  @Test
  public void incomingTelecomCallAppearsInDialerCallList() throws Exception {
    addIncomingCall(TEST_NUMBER);

    DialerCall incomingCall = waitForIncomingCall();
    assertThat(incomingCall.getState()).isEqualTo(DialerCallState.INCOMING);
    assertThat(incomingCall.getHandle())
        .isEqualTo(Uri.fromParts(PhoneAccount.SCHEME_TEL, TEST_NUMBER, null));
  }

  @Test
  public void incomingNonContactCallStartsRealAutomaticRecording() throws Exception {
    assumeTrue(isUserUnlocked());
    grantTargetPermission(Manifest.permission.RECORD_AUDIO);
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    assumeTrue(numberIsNotInContacts(TEST_NUMBER));
    seedAutomaticRecordingPreferences();

    cleanupRecordingsCreatedByTest();
    int recordingCountBeforeTest = generatedRecordingCount(TEST_NUMBER);
    addIncomingCall(TEST_NUMBER);
    answerIncomingCall();

    waitForRecordingToStart();

    // Keep the real recorder alive briefly so asynchronous AudioRecord setup failures surface
    // before the test accepts the recording as started.
    assertRecordingStaysOn();

    stopRecordingIfNeeded();
    waitForRecordingToStop();
    waitUntil(
        "recording file for " + TEST_NUMBER + " to be created",
        () -> generatedRecordingCount(TEST_NUMBER) > recordingCountBeforeTest);
  }

  @Test
  public void outgoingNonContactStartsAutomaticRecordingWhenCallConnects() throws Exception {
    assumeTrue(isUserUnlocked());
    grantTargetPermission(Manifest.permission.RECORD_AUDIO);
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    assumeTrue(numberIsNotInContacts(TEST_NUMBER));
    seedAutomaticRecordingPreferences();

    cleanupRecordingsCreatedByTest();
    placeOutgoingCall(TEST_NUMBER);
    waitForCallWithNumberAndState(TEST_NUMBER, DialerCallState.ACTIVE);
    waitForRecordingToStart();

    assertRecordingStaysOn();
  }

  @Test
  public void recordingContinuesWhileSecondCallRingsAndAfterItIsRejected() throws Exception {
    assumeTrue(isUserUnlocked());
    grantTargetPermission(Manifest.permission.RECORD_AUDIO);
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    assumeTrue(numberIsNotInContacts(TEST_NUMBER));
    assumeTrue(numberIsNotInContacts(SECOND_TEST_NUMBER));
    seedAutomaticRecordingPreferences();

    addIncomingCall(TEST_NUMBER);
    answerIncomingCall();
    waitForRecordingToStart();

    addIncomingCall(SECOND_TEST_NUMBER);
    assertThat(waitForIncomingCall(SECOND_TEST_NUMBER).getState())
        .isEqualTo(DialerCallState.INCOMING);
    assertThat(callWithNumberAndState(TEST_NUMBER, DialerCallState.ACTIVE)).isNotNull();
    assertRecordingStaysOn();

    rejectIncomingCall(SECOND_TEST_NUMBER);
    waitForCallToDisappear(SECOND_TEST_NUMBER);
    assertThat(callWithNumberAndState(TEST_NUMBER, DialerCallState.ACTIVE)).isNotNull();
    assertRecordingStaysOn();
  }

  @Test
  public void recordingStopsWhenRecordedCallIsPutOnHoldByAnsweringSecondCall() throws Exception {
    assumeTrue(isUserUnlocked());
    grantTargetPermission(Manifest.permission.RECORD_AUDIO);
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    assumeTrue(numberIsNotInContacts(TEST_NUMBER));
    assumeTrue(numberIsNotInContacts(SECOND_TEST_NUMBER));
    seedAutomaticRecordingPreferences();

    addIncomingCall(TEST_NUMBER);
    answerIncomingCall();
    waitForRecordingToStart();

    addIncomingCall(SECOND_TEST_NUMBER);
    DialerCall secondIncomingCall = waitForIncomingCall(SECOND_TEST_NUMBER);
    setIncomingRecordingChoice(secondIncomingCall.getId(), false /* enabled */);
    answerIncomingCall();
    waitForCallWithNumberAndState(TEST_NUMBER, DialerCallState.ONHOLD);
    waitForCallWithNumberAndState(SECOND_TEST_NUMBER, DialerCallState.ACTIVE);

    waitForRecordingToStop();
    assertRecordingStaysOff();
  }

  @Test
  public void newActiveNonContactStartsRecordingAfterPreviousCallMovesToHold() throws Exception {
    assumeTrue(isUserUnlocked());
    grantTargetPermission(Manifest.permission.RECORD_AUDIO);
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    assumeTrue(numberIsNotInContacts(TEST_NUMBER));
    assumeTrue(numberIsNotInContacts(SECOND_TEST_NUMBER));
    seedAutomaticRecordingPreferences();

    addIncomingCall(SECOND_TEST_NUMBER);
    answerIncomingCall();
    waitForRecordingToStart();

    addIncomingCall(TEST_NUMBER);
    answerIncomingCall();
    waitForCallWithNumberAndState(TEST_NUMBER, DialerCallState.ACTIVE);
    waitForCallWithNumberAndState(SECOND_TEST_NUMBER, DialerCallState.ONHOLD);

    waitForRecordingToStart();
    assertRecordingStaysOn();
  }

  @Test
  public void recordingStopsWhenRecordedCallDisconnects() throws Exception {
    assumeTrue(isUserUnlocked());
    grantTargetPermission(Manifest.permission.RECORD_AUDIO);
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    assumeTrue(numberIsNotInContacts(TEST_NUMBER));
    seedAutomaticRecordingPreferences();

    addIncomingCall(TEST_NUMBER);
    answerIncomingCall();
    waitForRecordingToStart();

    disconnectCall(TEST_NUMBER);
    waitForCallToDisappear(TEST_NUMBER);
    waitForRecordingToStop();
  }

  @Test
  public void conferenceCallDoesNotStartAutomaticRecordingAfterMerge() throws Exception {
    assumeTrue(isUserUnlocked());
    grantTargetPermission(Manifest.permission.RECORD_AUDIO);
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    assumeTrue(numberIsNotInContacts(TEST_NUMBER));
    assumeTrue(numberIsNotInContacts(SECOND_TEST_NUMBER));
    seedAutomaticRecordingPreferences();

    addIncomingCall(TEST_NUMBER);
    answerIncomingCall();
    waitForRecordingToStart();
    addIncomingCall(SECOND_TEST_NUMBER);
    DialerCall secondIncomingCall = waitForIncomingCall(SECOND_TEST_NUMBER);
    setIncomingRecordingChoice(secondIncomingCall.getId(), false /* enabled */);
    answerIncomingCall();
    waitForCallWithNumberAndState(TEST_NUMBER, DialerCallState.ONHOLD);
    waitForCallWithNumberAndState(SECOND_TEST_NUMBER, DialerCallState.ACTIVE);
    waitForRecordingToStop();

    mergeActiveCallWithHeldCall();
    waitForConferenceCall();
    assertRecordingStaysOff();
  }

  @Test
  public void stoppedNonContactCallsStayOffAfterCallWaitingSwapAndDisconnect() throws Exception {
    // Real call waiting, hold, and swap transitions must preserve explicit user choices to keep
    // automatic recording off for both calls in this two-call session.
    assumeTrue(isUserUnlocked());
    grantTargetPermission(Manifest.permission.RECORD_AUDIO);
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    assumeTrue(numberIsNotInContacts(TEST_NUMBER));
    assumeTrue(numberIsNotInContacts(SECOND_TEST_NUMBER));
    seedAutomaticRecordingPreferences();

    cleanupRecordingsCreatedByTest();
    addIncomingCall(TEST_NUMBER);
    answerIncomingCall();
    waitForRecordingToStart();

    stopRecordingIfNeeded();
    waitForRecordingToStop();
    assertRecordingStaysOff();

    addIncomingCall(SECOND_TEST_NUMBER);
    DialerCall secondIncomingCall = waitForIncomingCall(SECOND_TEST_NUMBER);
    // Dialer maps Telecom's ringing state to INCOMING even when another call is active. The
    // important platform behavior for this test is that the first call stays active until the
    // second call is answered, then Telecom moves it to hold.
    assertThat(secondIncomingCall.getState()).isEqualTo(DialerCallState.INCOMING);
    assertThat(callWithNumberAndState(TEST_NUMBER, DialerCallState.ACTIVE)).isNotNull();
    setIncomingRecordingChoice(secondIncomingCall.getId(), false /* enabled */);
    answerIncomingCall();
    waitForCallWithNumberAndState(SECOND_TEST_NUMBER, DialerCallState.ACTIVE);
    waitForCallWithNumberAndState(TEST_NUMBER, DialerCallState.ONHOLD);
    assertRecordingStaysOff();

    switchToHeldCall(TEST_NUMBER);
    waitForCallWithNumberAndState(TEST_NUMBER, DialerCallState.ACTIVE);
    waitForCallWithNumberAndState(SECOND_TEST_NUMBER, DialerCallState.ONHOLD);
    assertRecordingStaysOff();

    disconnectCall(TEST_NUMBER);
    waitForCallWithNumberAndState(SECOND_TEST_NUMBER, DialerCallState.ACTIVE);
    assertRecordingStaysOff();
  }
}
