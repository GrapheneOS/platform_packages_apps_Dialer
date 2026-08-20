package com.android.dialer.app.calllog;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CallLogNotificationsActivityTest {

  @Test
  public void callbackDoesNotRequireCallLogPermission() {
    assertFalse(
        CallLogNotificationsActivity.requiresReadCallLogPermission(
            CallLogNotificationsActivity.ACTION_CALL_BACK_FROM_MISSED_CALL_NOTIFICATION));
  }

  @Test
  public void smsKeepsLegacyCallLogPermissionCheck() {
    assertTrue(
        CallLogNotificationsActivity.requiresReadCallLogPermission(
            CallLogNotificationsActivity.ACTION_SEND_SMS_FROM_MISSED_CALL_NOTIFICATION));
  }
}
