package com.android.dialer.integration;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.KeyguardManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import com.android.dialer.R;
import com.android.incallui.call.DialerCall;
import com.android.incallui.call.state.DialerCallState;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * UI integration coverage for automatic call recording controls.
 *
 * <p>The Telecom integration tests assert recording behavior through Dialer methods. This class
 * clicks visible incallui controls so the in-call record button and the incoming call recording
 * switch are covered against real Telecom call waiting, hold, and swap transitions.
 *
 * <p>Answering uses the answer screen accessibility action instead of a swipe gesture. That still
 * exercises the real answer view callback while avoiding a test dependency on gesture thresholds
 * and animation timing.
 *
 * <p>When the device is already unlocked, Telecom may expose an incoming call as a notification
 * instead of launching the full answer UI. The test asks Telecom to show the in-call screen before
 * interacting with answer controls so it does not depend on that notification policy.
 */
@RunWith(AndroidJUnit4.class)
public final class AutoCallRecordingUiIntegrationTest
    extends AutoCallRecordingIntegrationTestBase {

  private UiDevice device;

  @Before
  public void setUpUiDevice() throws Exception {
    assumeTrue(isUserUnlocked());
    device = UiDevice.getInstance(instrumentation);
    device.wakeUp();
    KeyguardManager keyguardManager = targetContext.getSystemService(KeyguardManager.class);
    assumeTrue(keyguardManager == null || !keyguardManager.isKeyguardLocked());
  }

  @Test
  public void stoppedNonContactCallsStayOffAfterUiChoicesAndCallSwap()
      throws Exception {
    grantTargetPermission(Manifest.permission.RECORD_AUDIO);
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    assumeTrue(numberIsNotInContacts(TEST_NUMBER));
    assumeTrue(numberIsNotInContacts(SECOND_TEST_NUMBER));
    seedAutomaticRecordingPreferences();

    cleanupRecordingsCreatedByTest();
    addIncomingCall(TEST_NUMBER);
    answerIncomingCallFromUi(TEST_NUMBER);
    waitForRecordingToStart();

    clickStopRecordingButton();
    waitForRecordingToStop();
    assertRecordingStaysOff();

    addIncomingCall(SECOND_TEST_NUMBER);
    DialerCall secondIncomingCall = waitForIncomingCall(SECOND_TEST_NUMBER);
    assertThat(secondIncomingCall.getState()).isEqualTo(DialerCallState.INCOMING);
    assertThat(callWithNumberAndState(TEST_NUMBER, DialerCallState.ACTIVE)).isNotNull();

    turnOffIncomingRecordingSwitch();
    answerIncomingCallFromUi(SECOND_TEST_NUMBER);
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

  @Test
  public void incomingRecordingSwitchIsHiddenUntilWarningIsAcknowledged() throws Exception {
    grantTargetPermission(Manifest.permission.RECORD_AUDIO);
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    assumeTrue(numberIsNotInContacts(TEST_NUMBER));
    seedAutomaticRecordingPreferencesWithoutWarning();

    addIncomingCall(TEST_NUMBER);
    showAnswerScreenForIncomingCall(TEST_NUMBER);

    // The answer screen is not a good place to introduce the legal warning flow:
    // users are deciding whether to answer an active incoming call, and a switch
    // that cannot start recording until another dialog has run would be confusing.
    assertThat(device.findObject(incomingRecordingSwitchSelector())).isNull();
  }

  @Test
  public void incomingRecordingSwitchCanBeShownUnchecked() throws Exception {
    grantTargetPermission(Manifest.permission.RECORD_AUDIO);
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    seedRecordingSwitchPreferencesWithoutAutomaticRules();

    addIncomingCall(TEST_NUMBER);
    showAnswerScreenForIncomingCall(TEST_NUMBER);

    UiObject2 recordingSwitch = waitForIncomingRecordingSwitch(false /* checked */);
    assertThat(recordingSwitch.isEnabled()).isTrue();
  }

  @Test
  public void incomingRecordingSwitchOffPreventsRecordingAfterAnswer() throws Exception {
    grantTargetPermission(Manifest.permission.RECORD_AUDIO);
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    assumeTrue(numberIsNotInContacts(TEST_NUMBER));
    seedAutomaticRecordingPreferences();

    addIncomingCall(TEST_NUMBER);
    waitForCallNotificationVerificationText(
        targetContext.getString(R.string.auto_call_recording_will_start_message));
    showAnswerScreenForIncomingCall(TEST_NUMBER);

    turnOffIncomingRecordingSwitch();
    answerIncomingCallFromUi(TEST_NUMBER);

    assertRecordingStaysOff();
  }

  @Test
  public void activeRecordingCanBeStoppedFromUi() throws Exception {
    grantTargetPermission(Manifest.permission.RECORD_AUDIO);
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    assumeTrue(numberIsNotInContacts(TEST_NUMBER));
    seedAutomaticRecordingPreferences();

    cleanupRecordingsCreatedByTest();
    addIncomingCall(TEST_NUMBER);
    answerIncomingCallFromUi(TEST_NUMBER);
    waitForRecordingToStart();

    clickStopRecordingButton();

    waitForRecordingToStop();
    assertRecordingStaysOff();
  }

  @Test
  public void conferenceCallStopsRecordingAndShowsManualRecordButton() throws Exception {
    grantTargetPermission(Manifest.permission.RECORD_AUDIO);
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    assumeTrue(numberIsNotInContacts(TEST_NUMBER));
    assumeTrue(numberIsNotInContacts(SECOND_TEST_NUMBER));
    seedAutomaticRecordingPreferences();

    addIncomingCall(TEST_NUMBER);
    answerIncomingCallFromUi(TEST_NUMBER);
    waitForRecordingToStart();

    addIncomingCall(SECOND_TEST_NUMBER);
    turnOffIncomingRecordingSwitch();
    answerIncomingCallFromUi(SECOND_TEST_NUMBER);
    waitForCallWithNumberAndState(TEST_NUMBER, DialerCallState.ONHOLD);
    waitForCallWithNumberAndState(SECOND_TEST_NUMBER, DialerCallState.ACTIVE);
    waitForRecordingToStop();

    mergeActiveCallWithHeldCall();
    waitForConferenceCall();
    showInCallScreen();

    waitForRecordButton(R.string.onscreenCallRecordText);
  }

  @Test
  public void outgoingCallShowsRecordButtonBeforeItConnects() throws Exception {
    seedRecordingSwitchPreferencesWithoutAutomaticRules();
    placeOutgoingCall(TEST_NUMBER);

    waitForCallWithNumberAndState(TEST_NUMBER, DialerCallState.DIALING);
    showInCallScreen();

    waitForRecordButton(R.string.onscreenCallRecordText);
  }

  private void clickStopRecordingButton() throws Exception {
    waitForRecordButton(R.string.onscreenStopCallRecordText).click();
    device.waitForIdle();
  }

  private void turnOffIncomingRecordingSwitch() throws Exception {
    UiObject2 recordingSwitch = waitForIncomingRecordingSwitch(true /* checked */);
    recordingSwitch.click();
    device.waitForIdle();
    waitForIncomingRecordingSwitch(false /* checked */);
  }

  private void showAnswerScreenForIncomingCall(String number) throws Exception {
    waitForIncomingCall(number);
    showInCallScreen();
    waitForAnswerContainer();
  }

  private void answerIncomingCallFromUi(String number) throws Exception {
    DialerCall incomingCall = waitForIncomingCall(number);
    showInCallScreen();
    UiObject2 answerContainer = waitForAnswerContainer();
    int answerActionId =
        targetContext
            .getResources()
            .getIdentifier("accessibility_action_answer", "id", targetContext.getPackageName());
    assertThat(answerActionId).isNotEqualTo(0);
    assertThat(answerContainer.getAccessibilityNodeInfo().performAction(answerActionId))
        .isTrue();
    device.waitForIdle();
    waitUntil(
        "answer screen to report the call as active",
        () -> {
          DialerCall call = callById(incomingCall.getId());
          return call != null && call.getState() == DialerCallState.ACTIVE;
        });
  }

  private UiObject2 waitForAnswerContainer() {
    return waitForUiObject(
        By.pkg(targetContext.getPackageName())
            .res(targetContext.getPackageName(), "incoming_swipe_to_answer_container"));
  }

  private UiObject2 waitForRecordButton(int descriptionResId) {
    return waitForUiObject(
        By.pkg(targetContext.getPackageName()).desc(targetContext.getString(descriptionResId)));
  }

  private BySelector incomingRecordingSwitchSelector() {
    return By.pkg(targetContext.getPackageName())
        .res(targetContext.getPackageName(), "incoming_call_recording_switch");
  }

  private UiObject2 waitForIncomingRecordingSwitch(boolean checked) throws Exception {
    waitUntil(
        "incoming recording switch checked state to become " + checked,
        () -> {
          UiObject2 recordingSwitch = device.findObject(incomingRecordingSwitchSelector());
          return recordingSwitch != null
              && recordingSwitch.isEnabled()
              && recordingSwitch.isChecked() == checked;
        });
    return device.findObject(incomingRecordingSwitchSelector());
  }

  private UiObject2 waitForUiObject(BySelector selector) {
    UiObject2 object = device.wait(Until.findObject(selector), TIMEOUT_MILLIS);
    assertThat(object).isNotNull();
    return object;
  }
}
