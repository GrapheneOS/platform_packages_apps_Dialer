package com.android.dialer.callrecord;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import com.android.dialer.util.PermissionsUtil;

/** Shared helpers for call recording permission request flows. */
public final class CallRecordingPermissionHelper {

  /** Allows Java fragments and Kotlin fragments to supply their platform rationale callback. */
  public interface RationaleChecker {
    boolean shouldShowRequestPermissionRationale(@NonNull String permission);
  }

  public static void markPermissionsRequested(
      @Nullable Context context, @NonNull String[] permissions) {
    if (context == null) {
      return;
    }
    for (String requestedPermission : permissions) {
      PermissionsUtil.permissionRequested(context, requestedPermission);
    }
  }

  public static boolean hasPermanentlyDeniedPermission(
      @Nullable Context context,
      @NonNull String[] permissions,
      @NonNull RationaleChecker rationaleChecker) {
    if (context == null) {
      return false;
    }
    for (String requestedPermission : permissions) {
      if (ContextCompat.checkSelfPermission(context, requestedPermission)
          == PackageManager.PERMISSION_GRANTED) {
        continue;
      }
      if (!PermissionsUtil.isFirstRequest(context, requestedPermission)
          && !rationaleChecker.shouldShowRequestPermissionRationale(requestedPermission)) {
        return true;
      }
    }
    return false;
  }

  public static void openAppSettings(@NonNull Context context) {
    Intent intent =
        new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:" + context.getPackageName()));
    if (!(context instanceof Activity)) {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
    context.startActivity(intent);
  }

  private CallRecordingPermissionHelper() {}
}
