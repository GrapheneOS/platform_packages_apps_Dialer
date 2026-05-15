package com.android.incallui.call

import android.support.annotation.VisibleForTesting
import com.android.dialer.callrecord.CallRecordingPreferences
import com.android.dialer.common.LogUtil
import com.android.incallui.call.state.DialerCallState
import kotlinx.coroutines.CancellationException

// These seams keep Android singletons out of policy tests. Production still uses the default
// adapters in CallRecordingDefaultDependencies; tests replace only the parts that would otherwise
// require CallList or ContactInfoCache process state or real permissions.
// TODO: Replace this bundle with proper dependency injection if InCall gets a standard DI boundary.
@VisibleForTesting
data class CallRecordingDependencies(
    val currentCalls: CurrentCalls,
    val contactLookup: ContactLookup,
    val preferenceSource: PreferenceSource,
    val eligibilityChecker: EligibilityChecker,
)

@VisibleForTesting
data class CallSnapshot(
    val id: String,
    val number: String?,
    val state: Int,
    val isVideoCall: Boolean,
    val isConferenceCall: Boolean,
    val dialerCall: DialerCall?,
)

@VisibleForTesting
data class ContactInfo(
    val isLocalContact: Boolean,
    val normalizedNumber: String?,
)

@VisibleForTesting
fun interface ContactLookup {
  suspend fun findInfo(call: CallSnapshot): ContactInfo?
}

@VisibleForTesting
interface CurrentCalls {
  fun hasLiveCall(): Boolean

  fun hasActiveOrBackgroundCall(): Boolean

  fun getActiveCall(): CallSnapshot?

  fun getCallById(callId: String): CallSnapshot?
}

@VisibleForTesting
fun interface PreferenceSource {
  suspend fun load(): CallRecordingPreferences
}

@VisibleForTesting
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
