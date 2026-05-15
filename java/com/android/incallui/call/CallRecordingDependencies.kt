package com.android.incallui.call

import com.android.dialer.callrecord.CallRecordingPreferences
import com.android.dialer.common.LogUtil
import com.android.incallui.call.state.DialerCallState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher

// Production dependencies are provided by CallRecordingComponent. Tests build this value directly
// so policy checks can avoid CallList, ContactInfoCache process state, and real permissions.
data class CallRecordingDependencies(
    val currentCalls: CurrentCalls,
    val contactLookup: ContactLookup,
    val preferenceSource: PreferenceSource,
    val eligibilityChecker: EligibilityChecker,
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

internal enum class RecordingChoice {
  ENABLED,
  DISABLED,
}

internal fun isRecordableCall(call: CallSnapshot?): Boolean {
  return call != null &&
      !call.isConferenceCall &&
      !call.isVideoCall &&
      (call.state == DialerCallState.ACTIVE || DialerCallState.isDialing(call.state))
}

internal fun DialerCall?.toCallSnapshot(): CallSnapshot? {
  return this?.let {
    CallSnapshot(
        it.id,
        it.number,
        it.state,
        it.isVideoCall,
        it.isConferenceCall,
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
