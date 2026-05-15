package com.android.incallui.call

import android.content.Context
import com.android.dialer.callrecord.CallRecordingPreferences
import com.android.dialer.callrecord.CallRecordingPreferenceValues
import com.android.dialer.util.PermissionsUtil
import com.android.incallui.ContactInfoCache.ContactCacheEntry

/** Shared checks for automatic call recording UI and decisions. */
object AutoCallRecordingEligibility {

  enum class AutoRecordDecision {
    ELIGIBLE,
    NO_CALL,
    VIDEO_CALL,
    SNAPSHOT_NOT_READY,
    WARNING_NOT_PRESENTED,
    MISSING_MICROPHONE_AND_CONTACTS_PERMISSIONS,
    MISSING_MICROPHONE_PERMISSION,
    MISSING_CONTACTS_PERMISSION,
    NOT_CONFIGURED;

    fun canShowIncomingCallRecordingSwitch(): Boolean {
      return when (this) {
        ELIGIBLE,
        MISSING_MICROPHONE_AND_CONTACTS_PERMISSIONS,
        MISSING_MICROPHONE_PERMISSION,
        MISSING_CONTACTS_PERMISSION,
        NOT_CONFIGURED -> true
        else -> false
      }
    }

    fun canRecordIncomingCall(): Boolean {
      return canShowIncomingCallRecordingSwitch() &&
          this != MISSING_MICROPHONE_AND_CONTACTS_PERMISSIONS &&
          this != MISSING_MICROPHONE_PERMISSION
    }

    fun shouldCheckAutomaticRecording(): Boolean = this == ELIGIBLE

    fun shouldShowPermissionNotice(): Boolean {
      return this == MISSING_MICROPHONE_AND_CONTACTS_PERMISSIONS ||
          this == MISSING_MICROPHONE_PERMISSION ||
          this == MISSING_CONTACTS_PERMISSION
    }

    fun isMicrophonePermissionMissing(): Boolean {
      return this == MISSING_MICROPHONE_AND_CONTACTS_PERMISSIONS ||
          this == MISSING_MICROPHONE_PERMISSION
    }

    fun isContactsPermissionMissing(): Boolean {
      return this == MISSING_MICROPHONE_AND_CONTACTS_PERMISSIONS ||
          this == MISSING_CONTACTS_PERMISSION
    }
  }

  @JvmStatic
  fun shouldAutoRecordCall(
      context: Context,
      call: DialerCall?,
      entry: ContactCacheEntry?,
      preferences: CallRecordingPreferences
  ): Boolean {
    if (!getDecision(context, call, preferences, true /* requireContactsPermission */)
            .shouldCheckAutomaticRecording() ||
        entry == null ||
        entry.hasPendingContactLookup()) {
      return false
    }

    if (!entry.isLocalContact) {
      return preferences.autoRecordNonContacts
    }
    val normalizedNumber = entry.normalizedNumber
    return preferences.autoRecordSelectedNumbersEnabled &&
        !normalizedNumber.isNullOrEmpty() &&
        CallRecordingPreferenceValues.containsSelectedNumber(preferences, normalizedNumber)
  }

  @JvmStatic
  fun getDecision(
      context: Context,
      call: DialerCall?,
      preferences: CallRecordingPreferences,
      requireContactsPermission: Boolean
  ): AutoRecordDecision {
    val hasCall = call != null
    val isVideoCall = call?.isVideoCall == true
    return getDecision(
        hasCall,
        isVideoCall,
        true /* snapshotReady */,
        preferences,
        PermissionsUtil.hasMicrophonePermissions(context),
        PermissionsUtil.hasContactsReadPermissions(context),
        requireContactsPermission)
  }

  @JvmStatic
  fun getDecision(
      hasCall: Boolean,
      isVideoCall: Boolean,
      snapshotReady: Boolean,
      preferences: CallRecordingPreferences?,
      hasMicrophonePermission: Boolean,
      hasContactsPermission: Boolean,
      requireContactsPermission: Boolean
  ): AutoRecordDecision {
    if (!hasCall) {
      return AutoRecordDecision.NO_CALL
    }
    if (isVideoCall) {
      return AutoRecordDecision.VIDEO_CALL
    }
    // Notification and incallui updates must not block on DataStore I/O; default snapshots keep
    // automatic recording disabled until preference loading has completed.
    if (!snapshotReady || preferences == null) {
      return AutoRecordDecision.SNAPSHOT_NOT_READY
    }
    if (!preferences.recordingWarningPresented) {
      return AutoRecordDecision.WARNING_NOT_PRESENTED
    }
    if (!CallRecordingPreferenceValues.isAnyAutoRecordingSettingEnabled(preferences)) {
      return AutoRecordDecision.NOT_CONFIGURED
    }
    return getPermissionDecision(
        hasMicrophonePermission, hasContactsPermission, requireContactsPermission)
  }

  @JvmStatic
  fun getPermissionDecision(
      context: Context,
      requireContactsPermission: Boolean
  ): AutoRecordDecision {
    return getPermissionDecision(
        PermissionsUtil.hasMicrophonePermissions(context),
        PermissionsUtil.hasContactsReadPermissions(context),
        requireContactsPermission)
  }

  @JvmStatic
  fun getPermissionDecision(
      hasMicrophonePermission: Boolean,
      hasContactsPermission: Boolean,
      requireContactsPermission: Boolean
  ): AutoRecordDecision {
    val microphoneMissing = !hasMicrophonePermission
    val contactsMissing = requireContactsPermission && !hasContactsPermission
    if (microphoneMissing && contactsMissing) {
      return AutoRecordDecision.MISSING_MICROPHONE_AND_CONTACTS_PERMISSIONS
    }
    if (microphoneMissing) {
      return AutoRecordDecision.MISSING_MICROPHONE_PERMISSION
    }
    if (contactsMissing) {
      return AutoRecordDecision.MISSING_CONTACTS_PERMISSION
    }
    return AutoRecordDecision.ELIGIBLE
  }
}
