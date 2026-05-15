package com.android.incallui.call

import com.android.incallui.call.state.DialerCallState

/**
 * Shared call list rules for recording.
 *
 * Separate active and held calls can record the active call, but conferences require a fresh
 * manual record press because the set of call participants changed.
 */
object RecordingRules {

  @JvmStatic
  fun requiresManualRecordingStart(callList: CallList): Boolean {
    // Separate active/held calls can record the active call; conferences add people to one call.
    return hasConferenceCall(callList)
  }

  @JvmStatic
  fun isConferenceCall(call: DialerCall?): Boolean {
    return call != null &&
        (call.isConferenceCall ||
            call.state == DialerCallState.CONFERENCED ||
            call.parentId != null)
  }

  private fun hasConferenceCall(callList: CallList): Boolean {
    return callList.allCalls.any { call ->
      isEstablishedOrSettingUpCall(call) && isConferenceCall(call)
    }
  }

  private fun isEstablishedOrSettingUpCall(call: DialerCall?): Boolean {
    return when (call?.state) {
      DialerCallState.ACTIVE,
      DialerCallState.ONHOLD,
      DialerCallState.DIALING,
      DialerCallState.REDIALING,
      DialerCallState.PULLING,
      DialerCallState.CONNECTING,
      DialerCallState.CONFERENCED,
      DialerCallState.SELECT_PHONE_ACCOUNT,
      DialerCallState.CALL_PENDING -> true
      else -> false
    }
  }
}
