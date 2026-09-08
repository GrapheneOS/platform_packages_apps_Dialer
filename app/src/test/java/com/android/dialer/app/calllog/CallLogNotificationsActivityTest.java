/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
