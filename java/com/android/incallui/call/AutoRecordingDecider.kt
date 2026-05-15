package com.android.incallui.call

import android.content.Context
import com.android.dialer.callrecord.CallRecordingPreferences
import com.android.dialer.callrecord.CallRecordingPreferenceValues
import com.android.dialer.common.LogUtil
import com.android.dialer.phonenumberutil.PhoneNumberCanonicalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Decides whether an eligible call should be armed for automatic recording. */
internal class AutoRecordingDecider(
    context: Context,
    private val currentCalls: CurrentCalls,
    private val contactLookup: ContactLookup,
    private val eligibilityChecker: EligibilityChecker,
    private val backgroundDispatcher: CoroutineDispatcher,
) {
  private val context = context.applicationContext ?: context

  fun currentRecordableCall(callId: String): CallSnapshot? {
    return currentCalls.getCallById(callId)?.takeIf(::isRecordableCall)
  }

  suspend fun shouldRecord(
      callId: String,
      call: CallSnapshot,
      preferences: CallRecordingPreferences,
      userChoice: RecordingChoice?
  ): Boolean {
    if (userChoice == RecordingChoice.ENABLED) {
      return eligibilityChecker
          .getDecision(call, preferences, false /* requireContactsPermission */)
          .canRecordIncomingCall()
    }

    if (!shouldCheckAutomaticRecording(call, preferences)) {
      return false
    }
    val entry =
        try {
          contactLookup.findInfo(call)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          LogUtil.e(TAG, "Automatic recording contact lookup failed", e)
          return false
        }
    val currentCall = currentRecordableCall(callId) ?: return false
    if (!shouldCheckAutomaticRecording(currentCall, preferences)) {
      return false
    }
    return shouldAutoRecord(currentCall, entry, preferences)
  }

  private fun shouldCheckAutomaticRecording(
      call: CallSnapshot,
      preferences: CallRecordingPreferences
  ): Boolean {
    return eligibilityChecker
        .getDecision(call, preferences, true /* requireContactsPermission */)
        .shouldCheckAutomaticRecording()
  }

  private suspend fun shouldAutoRecord(
      call: CallSnapshot,
      entry: ContactInfo?,
      preferences: CallRecordingPreferences
  ): Boolean {
    if (entry == null || !entry.isLocalContact) {
      val shouldRecord = preferences.autoRecordNonContacts
      LogUtil.i(
          "$TAG.shouldAutoRecord",
          "Automatic recording non-contacts decision, shouldRecord=%b",
          shouldRecord)
      return shouldRecord
    }

    if (!preferences.autoRecordSelectedNumbersEnabled) {
      return false
    }
    val normalizedNumber = entry.normalizedNumber
    if (!normalizedNumber.isNullOrEmpty()) {
      val shouldRecord =
          CallRecordingPreferenceValues.containsSelectedNumber(preferences, normalizedNumber)
      LogUtil.i(
          "$TAG.shouldAutoRecord",
          "Automatic recording selected number decision, shouldRecord=%b",
          shouldRecord)
      return shouldRecord
    }

    val number = call.number
    if (number.isNullOrEmpty()) {
      return false
    }
    LogUtil.i(
        "$TAG.shouldAutoRecord",
        "Automatic recording local contact missing normalized number, normalizing fallback")
    val canonicalNumber =
        try {
          withContext(backgroundDispatcher) {
            PhoneNumberCanonicalizer.canonicalize(context, number)
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: RuntimeException) {
          LogUtil.e(TAG, "Automatic recording fallback normalization failed", e)
          return false
        }
    return CallRecordingPreferenceValues.containsSelectedNumber(preferences, canonicalNumber)
  }

  companion object {
    private const val TAG = "AutoRecordingDecider"
  }
}
