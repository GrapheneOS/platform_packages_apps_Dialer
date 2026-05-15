package com.android.dialer.app.settings

import android.content.Context
import com.android.dialer.callrecord.CallRecordingPreferenceValues
import com.android.dialer.callrecord.CallRecordingPreferencesStore

internal object SelectedNumberPreferenceUpdater {
  enum class AddResult {
    FAILED,
    ADDED,
    ALREADY_ADDED
  }

  suspend fun add(context: Context, canonicalNumber: String): AddResult {
    return CallRecordingPreferencesStore.updateWithResult(
        context, AddResult.FAILED) { preferences ->
      val update = CallRecordingPreferenceValues.addSelectedNumber(preferences, canonicalNumber)
      update.first to when (update.second) {
        CallRecordingPreferenceValues.SelectedNumberAddResult.ADDED -> AddResult.ADDED
        CallRecordingPreferenceValues.SelectedNumberAddResult.ALREADY_ADDED ->
            AddResult.ALREADY_ADDED
      }
    }
  }
}
