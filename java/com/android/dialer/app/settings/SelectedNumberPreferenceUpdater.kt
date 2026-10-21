package com.android.dialer.app.settings

import android.content.Context
import com.android.dialer.callrecord.CallRecordingPreferenceValues
import com.android.dialer.callrecord.CallRecordingPreferencesStore
import com.android.dialer.callrecord.ContactRecordingMode

internal object SelectedNumberPreferenceUpdater {
  enum class AddResult {
    FAILED,
    ADDED,
    ALREADY_ADDED
  }

  suspend fun add(
      context: Context,
      canonicalNumber: String,
      expectedMode: ContactRecordingMode
  ): AddResult {
    return CallRecordingPreferencesStore.updateWithResult(
        context, AddResult.FAILED) { preferences ->
      if (preferences.contactRecordingMode != expectedMode) {
        return@updateWithResult preferences to AddResult.FAILED
      }
      val update = CallRecordingPreferenceValues.addSelectedNumber(preferences, canonicalNumber)
      update.first to when (update.second) {
        CallRecordingPreferenceValues.SelectedNumberAddResult.ADDED -> AddResult.ADDED
        CallRecordingPreferenceValues.SelectedNumberAddResult.ALREADY_ADDED ->
            AddResult.ALREADY_ADDED
      }
    }
  }
}
