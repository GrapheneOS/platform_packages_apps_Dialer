package com.android.dialer.callrecord

/** Pure helpers for the call recording preferences proto. */
object CallRecordingPreferenceValues {
  internal val DEFAULT_PREFERENCES: CallRecordingPreferences =
      CallRecordingPreferences.getDefaultInstance()

  enum class SelectedNumberAddResult {
    ADDED,
    ALREADY_ADDED
  }

  private val SENSITIVE_BACKUP_KEYS =
      setOf(
          CallRecordingPreferencesStore.KEY_CALL_RECORDING_USE_V2,
          CallRecordingPreferencesStore.KEY_CALL_RECORDING_AUDIO_SOURCE,
          CallRecordingPreferencesStore.KEY_CALL_RECORDING_OUTPUT_FORMAT,
          CallRecordingPreferencesStore.KEY_CALL_RECORDING_OUTPUT_FORMAT_V2,
          CallRecordingPreferencesStore.KEY_AUTO_RECORD_NON_CONTACTS,
          CallRecordingPreferencesStore.KEY_AUTO_RECORD_SELECTED_NUMBERS_ENABLED,
          CallRecordingPreferencesStore.KEY_AUTO_RECORD_SELECTED_NUMBERS,
          CallRecordingPreferencesStore.KEY_AUTO_RECORDING_SET_AT_LEAST_ONCE,
          CallRecordingPreferencesStore.KEY_RECORDING_WARNING_PRESENTED)

  @JvmStatic
  fun setSelectedNumbers(
      builder: CallRecordingPreferences.Builder,
      canonicalNumbers: Set<String>?
  ) {
    val cleaned = canonicalNumbers.orEmpty().filter { it.isNotEmpty() }.sorted()
    builder.clearAutoRecordSelectedNumbers().addAllAutoRecordSelectedNumbers(cleaned)
  }

  @JvmStatic
  fun selectedNumbers(preferences: CallRecordingPreferences): Set<String> {
    val stored = preferences.autoRecordSelectedNumbersList
    return if (stored.isEmpty()) emptySet() else HashSet(stored)
  }

  @JvmStatic
  fun addSelectedNumber(
      preferences: CallRecordingPreferences,
      canonicalNumber: String
  ): Pair<CallRecordingPreferences, SelectedNumberAddResult> {
    val selectedNumbers = selectedNumbers(preferences)
    if (selectedNumbers.contains(canonicalNumber)) {
      return preferences to SelectedNumberAddResult.ALREADY_ADDED
    }
    val builder = preferences.toBuilder()
    setSelectedNumbers(builder, selectedNumbers + canonicalNumber)
    return builder.build() to SelectedNumberAddResult.ADDED
  }

  @JvmStatic
  fun containsSelectedNumber(
      preferences: CallRecordingPreferences,
      canonicalNumber: String?
  ): Boolean {
    return !canonicalNumber.isNullOrEmpty() &&
        preferences.autoRecordSelectedNumbersList.contains(canonicalNumber)
  }

  @JvmStatic
  fun shouldRecordContactNumber(
      preferences: CallRecordingPreferences,
      canonicalNumber: String?
  ): Boolean {
    if (!preferences.autoRecordContactsEnabled || canonicalNumber.isNullOrEmpty()) {
      return false
    }
    val selected = containsSelectedNumber(preferences, canonicalNumber)
    return when (preferences.contactRecordingMode) {
      ContactRecordingMode.SELECTED_NUMBERS -> selected
      ContactRecordingMode.ALL_EXCEPT_SELECTED_NUMBERS -> !selected
      else -> false
    }
  }

  /** Reject stale UI choices rather than discarding numbers the user has not confirmed. */
  fun switchContactRecordingMode(
      builder: CallRecordingPreferences.Builder,
      expectedMode: ContactRecordingMode,
      expectedNumbers: Set<String>,
      mode: ContactRecordingMode
  ) {
    check(builder.contactRecordingMode == expectedMode &&
        builder.autoRecordSelectedNumbersList.toSet() == expectedNumbers) {
      "Contact recording settings changed while choosing a mode"
    }
    if (mode != expectedMode) {
      builder.setContactRecordingMode(mode)
          .clearAutoRecordSelectedNumbers()
          .setAutoRecordingSetAtLeastOnce(true)
    }
  }

  @JvmStatic
  fun isAnyAutoRecordingSettingEnabled(preferences: CallRecordingPreferences): Boolean {
    return preferences.autoRecordNonContacts || preferences.autoRecordContactsEnabled
  }

  @JvmStatic
  fun isSensitiveBackupKey(key: String?): Boolean {
    return SENSITIVE_BACKUP_KEYS.contains(key)
  }
}
