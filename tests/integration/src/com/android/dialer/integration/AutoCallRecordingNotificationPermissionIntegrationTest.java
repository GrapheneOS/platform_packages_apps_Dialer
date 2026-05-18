package com.android.dialer.integration;

import static org.junit.Assume.assumeTrue;

import android.Manifest;
import com.android.dialer.R;
import java.util.Arrays;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Notification integration coverage for call recording permission messages. */
@RunWith(Parameterized.class)
public final class AutoCallRecordingNotificationPermissionIntegrationTest
    extends AutoCallRecordingIntegrationTestBase {

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> cases() {
    return Arrays.asList(
        new Object[][] {
          {
            "microphone permission missing",
            false,
            true,
            R.string.auto_call_recording_mic_permission_message
          },
          {
            "contacts permission missing",
            true,
            false,
            R.string.auto_call_recording_contacts_permission_message
          },
          {
            "microphone and contacts permissions missing",
            false,
            false,
            R.string.auto_call_recording_permissions_message
          }
        });
  }

  private final boolean hasMicrophonePermission;
  private final boolean hasContactsPermission;
  private final int expectedMessageResId;

  public AutoCallRecordingNotificationPermissionIntegrationTest(
      String name,
      boolean hasMicrophonePermission,
      boolean hasContactsPermission,
      int expectedMessageResId) {
    this.hasMicrophonePermission = hasMicrophonePermission;
    this.hasContactsPermission = hasContactsPermission;
    this.expectedMessageResId = expectedMessageResId;
  }

  @Test
  public void incomingCallNotificationShowsMissingPermissionMessage() throws Exception {
    assumeTrue(isUserUnlocked());
    setTargetPermission(Manifest.permission.RECORD_AUDIO, hasMicrophonePermission);
    setTargetPermission(Manifest.permission.READ_CONTACTS, hasContactsPermission);
    seedAutomaticRecordingPreferences();

    addIncomingCall(TEST_NUMBER);

    waitForCallNotificationVerificationText(targetContext.getString(expectedMessageResId));
  }

  private void setTargetPermission(String permission, boolean granted) {
    if (granted) {
      grantTargetPermission(permission);
    } else {
      assumeTrue("Target permission can be revoked", revokeTargetPermission(permission));
    }
  }
}
