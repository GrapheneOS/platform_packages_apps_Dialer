package com.android.incallui.telecomeventui;

import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import com.android.dialer.common.LogUtil;

/**
 * Offers to message emergency services over satellite after an emergency call could not connect.
 *
 * <p>Telephony sends {@link android.telephony.TelephonyManager#EVENT_DISPLAY_EMERGENCY_MESSAGE}
 * with a {@link PendingIntent} that opens the default SMS app on a conversation with emergency
 * services, using the subscription that is connected to the satellite network. This activity is
 * separate from the in-call screen so it stays up after the emergency call ends.
 */
public class SatelliteEmergencyMessageDialogActivity extends AppCompatActivity {

  private static final String EXTRA_LAUNCH_INTENT = "extra_launch_intent";

  private PendingIntent launchIntent;
  private AlertDialog dialog;

  public static Intent newIntent(@NonNull Context context, @NonNull PendingIntent launchIntent) {
    Intent intent = new Intent(context, SatelliteEmergencyMessageDialogActivity.class);
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    intent.putExtra(EXTRA_LAUNCH_INTENT, launchIntent);
    return intent;
  }

  @Override
  protected void onCreate(@Nullable Bundle bundle) {
    super.onCreate(bundle);

    launchIntent = getIntent().getParcelableExtra(EXTRA_LAUNCH_INTENT, PendingIntent.class);
    if (launchIntent == null) {
      finish();
      return;
    }

    dialog =
        new AlertDialog.Builder(this)
            .setTitle(R.string.satellite_emergency_message_title)
            .setMessage(R.string.satellite_emergency_message_details)
            .setPositiveButton(
                R.string.satellite_emergency_message_action, (d, which) -> onMessageClick())
            .setNegativeButton(android.R.string.cancel, (d, which) -> finish())
            .setOnCancelListener(d -> finish())
            .create();
    dialog.setCanceledOnTouchOutside(false);
    dialog.show();
  }

  @Override
  protected void onDestroy() {
    if (dialog != null) {
      dialog.dismiss();
    }
    super.onDestroy();
  }

  private void onMessageClick() {
    KeyguardManager keyguardManager = getSystemService(KeyguardManager.class);
    if (keyguardManager == null || !keyguardManager.isKeyguardLocked()) {
      sendLaunchIntent();
      return;
    }

    // The SMS app can't show over the lock screen, so ask the user to unlock first.
    keyguardManager.requestDismissKeyguard(
        this,
        new KeyguardManager.KeyguardDismissCallback() {
          @Override
          public void onDismissSucceeded() {
            sendLaunchIntent();
          }

          @Override
          public void onDismissCancelled() {
            LogUtil.i(
                "SatelliteEmergencyMessageDialogActivity.onDismissCancelled",
                "keyguard not dismissed");
            showDialogAgain();
          }

          @Override
          public void onDismissError() {
            LogUtil.e(
                "SatelliteEmergencyMessageDialogActivity.onDismissError",
                "failed to dismiss keyguard");
            showDialogAgain();
          }
        });
  }

  /** The buttons dismiss the dialog, so bring it back if the user is still here. */
  private void showDialogAgain() {
    if (!isFinishing() && !isDestroyed()) {
      dialog.show();
    }
  }

  private void sendLaunchIntent() {
    ActivityOptions options =
        ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE);
    try {
      launchIntent.send(options.toBundle());
    } catch (PendingIntent.CanceledException e) {
      LogUtil.e("SatelliteEmergencyMessageDialogActivity.sendLaunchIntent", "canceled", e);
    }
    finish();
  }
}
