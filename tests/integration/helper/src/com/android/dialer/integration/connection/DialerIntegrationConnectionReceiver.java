package com.android.dialer.integration.connection;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

/**
 * Shell-driven command receiver for the integration test connection service.
 *
 * <p>The receiver is guarded by android.permission.DUMP because the instrumentation sends commands
 * through adb shell. That keeps incoming call creation under the helper APK UID, matching Telecom's
 * ownership checks instead of bypassing them from the Dialer test process.
 */
public final class DialerIntegrationConnectionReceiver extends BroadcastReceiver {
  public static final String ACTION_REGISTER = "com.android.dialer.integration.connection.REGISTER";
  public static final String ACTION_ADD_INCOMING_CALL =
      "com.android.dialer.integration.connection.ADD_INCOMING_CALL";
  public static final String EXTRA_ACCOUNT_ID = "account_id";
  public static final String EXTRA_NUMBER = "number";
  public static final String EXTRA_PRESENTATION = "presentation";

  @Override
  public void onReceive(Context context, Intent intent) {
    String action = intent.getAction();
    if (ACTION_REGISTER.equals(action)) {
      registerPhoneAccount(context, intent.getStringExtra(EXTRA_ACCOUNT_ID));
    } else if (ACTION_ADD_INCOMING_CALL.equals(action)) {
      addIncomingCall(
          context,
          intent.getStringExtra(EXTRA_ACCOUNT_ID),
          intent.getStringExtra(EXTRA_NUMBER),
          intent.getIntExtra(EXTRA_PRESENTATION, TelecomManager.PRESENTATION_ALLOWED));
    }
  }

  private static void registerPhoneAccount(Context context, String accountId) {
    TelecomManager telecomManager = context.getSystemService(TelecomManager.class);
    if (telecomManager == null) {
      return;
    }
    PhoneAccountHandle handle = phoneAccountHandle(context, accountId);
    telecomManager.registerPhoneAccount(
        PhoneAccount.builder(handle, "Dialer integration")
            .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
            .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
            .build());
  }

  private static void addIncomingCall(
      Context context, String accountId, String number, int presentation) {
    TelecomManager telecomManager = context.getSystemService(TelecomManager.class);
    if (telecomManager == null) {
      return;
    }
    Bundle extras = new Bundle();
    if (number != null) {
      extras.putParcelable(
          TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
          Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null));
    }
    extras.putInt(MockDialerConnectionService.EXTRA_ADDRESS_PRESENTATION, presentation);
    telecomManager.addNewIncomingCall(phoneAccountHandle(context, accountId), extras);
  }

  private static PhoneAccountHandle phoneAccountHandle(Context context, String accountId) {
    return new PhoneAccountHandle(
        new ComponentName(context, MockDialerConnectionService.class), accountId);
  }
}
