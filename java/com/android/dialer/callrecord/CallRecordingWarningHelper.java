package com.android.dialer.callrecord;

import android.app.AlertDialog;
import android.content.Context;
import android.support.annotation.NonNull;
import com.android.incallui.R;

/** Shared acknowledgement dialog for manual and automatic call recording enable flows. */
public final class CallRecordingWarningHelper {

  public interface WarningAcknowledgementWriter {
    void write(@NonNull Runnable onSuccess, @NonNull FailureCallback onFailure);
  }

  public interface FailureCallback {
    void onFailure(@NonNull Throwable throwable);
  }

  private CallRecordingWarningHelper() {}

  public static boolean requestAcknowledgementIfNeeded(
      @NonNull Context context,
      boolean warningPresented,
      @NonNull WarningAcknowledgementWriter writer,
      @NonNull Runnable onAcknowledged,
      @NonNull FailureCallback onFailure) {
    return requestAcknowledgementIfNeeded(
        context, warningPresented, writer, onAcknowledged, onFailure, () -> {});
  }

  public static boolean requestAcknowledgementIfNeeded(
      @NonNull Context context,
      boolean warningPresented,
      @NonNull WarningAcknowledgementWriter writer,
      @NonNull Runnable onAcknowledged,
      @NonNull FailureCallback onFailure,
      @NonNull Runnable onCancelled) {
    if (warningPresented) {
      return false;
    }
    new AlertDialog.Builder(context)
        .setTitle(R.string.recording_warning_title)
        .setMessage(R.string.recording_warning_text)
        .setPositiveButton(
            R.string.onscreenCallRecordText,
            (dialog, which) -> writer.write(onAcknowledged, onFailure))
        .setNegativeButton(android.R.string.cancel, (dialog, which) -> onCancelled.run())
        .setOnCancelListener(dialog -> onCancelled.run())
        .show();
    return true;
  }
}
