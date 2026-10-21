package com.android.dialer.integration;

import static org.junit.Assume.assumeTrue;

import android.Manifest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.dialer.R;
import com.android.incallui.call.state.DialerCallState;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Notification integration coverage for automatic call recording state. */
@RunWith(AndroidJUnit4.class)
public final class AutoCallRecordingNotificationIntegrationTest
    extends AutoCallRecordingIntegrationTestBase {

  @Test
  public void incomingCallNotificationShowsAutomaticRecordingText() throws Exception {
    assumeTrue(isUserUnlocked());
    grantTargetPermission(Manifest.permission.RECORD_AUDIO);
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    assumeTrue(numberIsNotInContacts(TEST_NUMBER));
    seedAutomaticRecordingPreferences();

    addIncomingCall(TEST_NUMBER);

    waitForCallNotificationVerificationText(
        targetContext.getString(R.string.auto_call_recording_will_start_message));
  }

  @Test
  public void incomingCallWithoutAutomaticRuleShowsNoAutomaticRecordingText() throws Exception {
    assumeTrue(isUserUnlocked());
    grantTargetPermission(Manifest.permission.RECORD_AUDIO);
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    seedRecordingSwitchPreferencesWithoutAutomaticRules();

    addIncomingCall(TEST_NUMBER);

    waitForCallNotification();
    assertRecentCallNotificationsDoNotShowText(
        targetContext.getString(R.string.auto_call_recording_will_start_message));
  }

  @Test
  public void notificationAnswerActionAnswersIncomingCall() throws Exception {
    assumeTrue(isUserUnlocked());

    addIncomingCall(TEST_NUMBER);
    waitForIncomingCall(TEST_NUMBER);
    waitForCallNotification();

    sendIncomingCallAnswerNotificationAction();

    waitForCallWithNumberAndState(TEST_NUMBER, DialerCallState.ACTIVE);
  }
}
