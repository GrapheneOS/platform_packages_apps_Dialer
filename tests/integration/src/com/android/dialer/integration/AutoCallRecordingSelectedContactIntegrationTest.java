package com.android.dialer.integration;

import static org.junit.Assume.assumeTrue;

import android.Manifest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.dialer.R;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Selected contact integration coverage for automatic call recording.
 *
 * <p>These tests intentionally store contacts with display-formatted phone numbers while selected
 * number preferences use canonical numbers. That exercises the ContactsProvider lookup and Dialer
 * normalization path used by real calls.
 */
@RunWith(AndroidJUnit4.class)
public final class AutoCallRecordingSelectedContactIntegrationTest
    extends AutoCallRecordingIntegrationTestBase {

  @Test
  public void selectedContactStartsRecordingWhenContactNumberNeedsNormalization()
      throws Exception {
    assumeTrue(isUserUnlocked());
    grantTargetPermission(Manifest.permission.RECORD_AUDIO);
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    grantTargetPermission(Manifest.permission.WRITE_CONTACTS);
    insertLocalContact("Dialer Integration A", TEST_NUMBER_FORMATTED);
    waitForContactNumber(TEST_NUMBER);
    seedSelectedNumberRecordingPreferences(TEST_NUMBER);

    cleanupRecordingsCreatedByTest();
    addIncomingCall(TEST_NUMBER);
    answerIncomingCall();

    waitForRecordingToStart();
    assertRecordingStaysOn();
  }

  @Test
  public void unselectedContactDoesNotRecordWhenContactNumberNeedsNormalization()
      throws Exception {
    assumeTrue(isUserUnlocked());
    grantTargetPermission(Manifest.permission.RECORD_AUDIO);
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    grantTargetPermission(Manifest.permission.WRITE_CONTACTS);
    seedSelectedNumberRecordingPreferences(TEST_NUMBER);
    insertLocalContact("Dialer Integration B", SECOND_TEST_NUMBER_FORMATTED);

    addIncomingCall(SECOND_TEST_NUMBER);
    answerIncomingCall();

    assertRecordingStaysOff();
  }

  @Test
  public void selectedContactNotificationShowsAutomaticRecordingTextAfterNormalization()
      throws Exception {
    assumeTrue(isUserUnlocked());
    grantTargetPermission(Manifest.permission.RECORD_AUDIO);
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    grantTargetPermission(Manifest.permission.WRITE_CONTACTS);
    insertLocalContact("Dialer Integration A", TEST_NUMBER_FORMATTED);
    waitForContactNumber(TEST_NUMBER);
    seedSelectedNumberRecordingPreferences(TEST_NUMBER);

    addIncomingCall(TEST_NUMBER);

    waitForCallNotificationVerificationText(
        targetContext.getString(R.string.auto_call_recording_will_start_message));
  }
}
