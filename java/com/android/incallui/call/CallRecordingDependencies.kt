package com.android.incallui.call

import com.android.dialer.callrecord.CallRecordingPreferences
import com.android.dialer.common.LogUtil
import com.android.incallui.InCallActivity
import com.android.incallui.call.state.DialerCallState
import com.android.incallui.incall.protocol.InCallButtonUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher

// Production dependencies are provided by CallRecordingComponent. Tests build this value directly
// so policy checks can avoid CallList, ContactInfoCache process state, and real permissions.
data class CallRecordingDependencies(
    val currentCalls: CurrentCalls,
    val contactLookup: ContactLookup,
    val preferenceSource: PreferenceSource,
    val eligibilityChecker: EligibilityChecker,
    val permissionChecker: PermissionChecker,
    val uiDispatcher: CoroutineDispatcher,
    val backgroundDispatcher: CoroutineDispatcher,
)

data class CallSnapshot(
    val id: String,
    val number: String?,
    val state: Int,
    val isVideoCall: Boolean,
    val isConferenceCall: Boolean,
    val dialerCall: DialerCall?,
)

data class ContactInfo(
    val isLocalContact: Boolean,
    val normalizedNumber: String?,
)

fun interface ContactLookup {
  suspend fun findInfo(call: CallSnapshot): ContactInfo?
}

interface CurrentCalls {
  fun hasLiveCall(): Boolean

  fun hasActiveOrBackgroundCall(): Boolean

  fun requiresManualRecordingStart(): Boolean

  fun getActiveCall(): CallSnapshot?

  fun getCallById(callId: String): CallSnapshot?
}

fun interface PreferenceSource {
  suspend fun load(): CallRecordingPreferences
}

fun interface EligibilityChecker {
  fun getDecision(
      call: CallSnapshot,
      preferences: CallRecordingPreferences,
      requireContactsPermission: Boolean
  ): AutoCallRecordingEligibility.AutoRecordDecision
}

fun interface PermissionChecker {
  fun hasAll(permissions: Array<String>): Boolean
}

fun interface CallProvider {
  fun get(): DialerCall?
}

fun interface ActivityProvider {
  fun get(): InCallActivity?
}

fun interface ButtonUiProvider {
  fun get(): InCallButtonUi?
}

// Manual recording can cross warning and permission UI. Keep these as providers so the flow can
// check the current call and UI again after each async boundary.
data class ManualRecordingRequest(
    val callProvider: CallProvider,
    val activityProvider: ActivityProvider,
    val buttonUiProvider: ButtonUiProvider,
)

internal enum class RecordingChoice {
  ENABLED,
  DISABLED,
}

internal fun isRecordableCall(call: CallSnapshot?): Boolean {
  return call != null &&
      !call.isConferenceCall &&
      !call.isVideoCall &&
      (call.state == DialerCallState.ACTIVE ||
          call.state == DialerCallState.CONNECTING ||
          DialerCallState.isDialing(call.state))
}

internal fun DialerCall?.toCallSnapshot(): CallSnapshot? {
  return this?.let {
    CallSnapshot(
        it.id,
        it.number,
        it.state,
        it.isVideoCall,
        RecordingRules.isConferenceCall(it),
        it)
  }
}

internal suspend fun PreferenceSource.loadPreferencesOrNull(
    callId: String,
    operation: String,
    logTag: String
): CallRecordingPreferences? {
  return try {
    load()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    LogUtil.e(logTag, "Failed to load $operation, callId=$callId", e)
    null
  }
}
