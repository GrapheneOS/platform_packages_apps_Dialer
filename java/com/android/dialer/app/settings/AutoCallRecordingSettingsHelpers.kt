// TODO: Migrate these helpers with the call recording settings screens off platform preferences.
@file:Suppress("DEPRECATION")

package com.android.dialer.app.settings

import android.Manifest.permission
import android.content.Context
import android.content.pm.PackageManager
import android.preference.PreferenceFragment
import com.android.dialer.callrecord.CallRecordingPermissionHelper
import com.android.dialer.callrecord.CallRecordingPreferencesStore
import com.android.dialer.callrecord.CallRecordingWarningHelper
import com.android.dialer.common.LogUtil
import com.android.dialer.util.PermissionsUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal val AUTO_RECORDING_REQUIRED_PERMISSIONS =
    arrayOf(permission.RECORD_AUDIO, permission.READ_CONTACTS)

internal fun hasAutomaticRecordingPermissions(context: Context): Boolean {
  return PermissionsUtil.hasMicrophonePermissions(context) &&
      PermissionsUtil.hasContactsReadPermissions(context)
}

internal fun CoroutineScope.launchCallRecordingPreferenceWrite(
    operation: String,
    onFailure: (Exception) -> Unit,
    onSuccess: () -> Unit = {},
    write: suspend () -> Unit
) {
  launch {
    try {
      write()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      LogUtil.e(operation, "failed to update call recording preferences", e)
      onFailure(e)
      return@launch
    }
    onSuccess()
  }
}

internal class AutoCallRecordingEnableFlow(
    private val fragment: PreferenceFragment,
    private val requestCode: Int,
    private val writeWarningPresented: CallRecordingWarningHelper.WarningAcknowledgementWriter,
    private val onPermissionDenied: () -> Unit,
    private val onPermissionPermanentlyDenied: () -> Unit = onPermissionDenied
) {
  private var pendingEnableAction: (() -> Unit)? = null

  fun requestEnable(enableAction: () -> Unit) {
    val activity = fragment.activity ?: return
    if (CallRecordingWarningHelper.requestAcknowledgementIfNeeded(
        activity,
        CallRecordingPreferencesStore.getSnapshot().recordingWarningPresented,
        writeWarningPresented,
        { continueEnable(enableAction) },
        { onPermissionDenied() })) {
      return
    }
    continueEnable(enableAction)
  }

  private fun continueEnable(enableAction: () -> Unit) {
    val activity = fragment.activity ?: return
    if (!hasAutomaticRecordingPermissions(activity)) {
      pendingEnableAction = enableAction
      fragment.requestPermissions(AUTO_RECORDING_REQUIRED_PERMISSIONS, requestCode)
      return
    }
    enableAction()
  }

  fun onRequestPermissionsResult(
      permissions: Array<out String>,
      grantResults: IntArray,
      onNoPendingAction: () -> Unit
  ) {
    CallRecordingPermissionHelper.markPermissionsRequested(fragment.activity, permissions)
    val allGranted =
        grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
    val pendingAction = pendingEnableAction
    pendingEnableAction = null
    if (allGranted && pendingAction != null) {
      pendingAction.invoke()
    } else if (allGranted) {
      onNoPendingAction()
    } else if (hasPermanentlyDeniedPermission(permissions)) {
      onPermissionPermanentlyDenied()
    } else {
      onPermissionDenied()
    }
  }

  fun requestMissingPermissions(onNoMissingPermissions: () -> Unit) {
    val activity = fragment.activity ?: return
    val missingPermissions =
        PermissionsUtil.getPermissionsCurrentlyDenied(
            activity, AUTO_RECORDING_REQUIRED_PERMISSIONS.asList())
    if (missingPermissions.isEmpty()) {
      onNoMissingPermissions()
      return
    }
    if (hasPermanentlyDeniedPermission(missingPermissions)) {
      onPermissionPermanentlyDenied()
      return
    }
    fragment.requestPermissions(missingPermissions, requestCode)
  }

  private fun hasPermanentlyDeniedPermission(permissions: Array<out String>): Boolean {
    return CallRecordingPermissionHelper.hasPermanentlyDeniedPermission(
        fragment.activity, permissions) { requestedPermission ->
          fragment.shouldShowRequestPermissionRationale(requestedPermission)
        }
  }
}
