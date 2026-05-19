package com.android.incallui.call

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import com.android.dialer.R
import com.android.dialer.notification.DialerNotificationManager
import com.android.dialer.notification.NotificationChannelId
import com.android.incallui.InCallActivity
import java.util.concurrent.atomic.AtomicInteger

/** Posts user-visible call recording failure notifications. */
object CallRecordingErrorNotifier {
  private const val TAG = "CallRecordingErrorNotifier"
  private const val NOTIFICATION_TAG = "call_recording_error"
  private const val NOTIFICATION_REQUEST_CODE = 8000
  private val nextNotificationId = AtomicInteger(8000)

  @JvmStatic
  fun show(context: Context) {
    try {
      DialerNotificationManager.notify(
          context,
          NOTIFICATION_TAG,
          nextNotificationId.getAndIncrement(),
          buildNotification(context))
    } catch (e: RuntimeException) {
      // Recorder state has already been cleared, so notification failure should not trigger
      // recording cleanup again.
      Log.w(TAG, "Failed to post call recording error notification", e)
    }
  }

  private fun buildNotification(context: Context): Notification {
    val message = context.getString(R.string.call_recording_error_message)
    return Notification.Builder(context, NotificationChannelId.CALL_RECORDING_ERROR)
        .setSmallIcon(R.drawable.quantum_ic_call_white_24)
        .setContentTitle(context.getString(R.string.call_recording_error_title))
        .setContentText(message)
        .setStyle(Notification.BigTextStyle().bigText(message))
        .setContentIntent(createContentIntent(context))
        .setAutoCancel(true)
        .setCategory(Notification.CATEGORY_ERROR)
        .setOnlyAlertOnce(false)
        .setShowWhen(true)
        .setWhen(System.currentTimeMillis())
        .build()
  }

  private fun createContentIntent(context: Context): PendingIntent {
    val intent = InCallActivity.getIntent(context, false, false, false)
    // Use a stable request code, but a fresh notification id, so repeated failures alert again.
    return PendingIntent.getActivity(
        context, NOTIFICATION_REQUEST_CODE, intent, PendingIntent.FLAG_IMMUTABLE)
  }
}
