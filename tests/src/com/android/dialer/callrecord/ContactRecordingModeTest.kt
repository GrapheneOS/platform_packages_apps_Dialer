package com.android.dialer.callrecord

import com.android.dialer.callrecord.ContactRecordingMode.ALL_EXCEPT_SELECTED_NUMBERS
import com.android.dialer.callrecord.ContactRecordingMode.SELECTED_NUMBERS
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ContactRecordingModeTest {
  @Test
  fun upgradingPreservesEnabledContactRecording() {
    // Wire field 7 was auto_record_selected_numbers_enabled before it was renamed.
    val preferences = CallRecordingPreferences.parseFrom(byteArrayOf(0x38, 0x01))

    assertThat(preferences.autoRecordContactsEnabled).isTrue()
    assertThat(preferences.contactRecordingMode).isEqualTo(SELECTED_NUMBERS)
    assertThat(preferences.hasContactRecordingMode()).isFalse()
  }

  @Test
  fun selectedModeRecordsOnlyListedContactNumbers() {
    val preferences = preferences(SELECTED_NUMBERS).addAutoRecordSelectedNumbers(NUMBER).build()

    assertThat(CallRecordingPreferenceValues.shouldRecordContactNumber(preferences, NUMBER))
        .isTrue()
    assertThat(CallRecordingPreferenceValues.shouldRecordContactNumber(preferences, OTHER_NUMBER))
        .isFalse()
  }

  @Test
  fun exceptionModeRecordsOnlyUnlistedContactNumbers() {
    val preferences =
        preferences(ALL_EXCEPT_SELECTED_NUMBERS).addAutoRecordSelectedNumbers(NUMBER).build()

    assertThat(CallRecordingPreferenceValues.shouldRecordContactNumber(preferences, NUMBER))
        .isFalse()
    assertThat(CallRecordingPreferenceValues.shouldRecordContactNumber(preferences, OTHER_NUMBER))
        .isTrue()
  }

  @Test
  fun emptyListRecordsAllContactsOnlyInExceptionMode() {
    assertThat(
        CallRecordingPreferenceValues.shouldRecordContactNumber(
            preferences(SELECTED_NUMBERS).build(), NUMBER))
        .isFalse()
    assertThat(
        CallRecordingPreferenceValues.shouldRecordContactNumber(
            preferences(ALL_EXCEPT_SELECTED_NUMBERS).build(), NUMBER))
        .isTrue()
  }

  @Test
  fun turningOffContactRecordingStopsRecordingUnlistedContacts() {
    val preferences =
        preferences(ALL_EXCEPT_SELECTED_NUMBERS)
            .addAutoRecordSelectedNumbers(NUMBER)
            .setAutoRecordContactsEnabled(false)
            .build()

    assertThat(CallRecordingPreferenceValues.shouldRecordContactNumber(preferences, OTHER_NUMBER))
        .isFalse()
  }

  @Test
  fun contactsWithoutAUsableNumberAreNotRecorded() {
    val preferences = preferences(ALL_EXCEPT_SELECTED_NUMBERS).build()

    assertThat(CallRecordingPreferenceValues.shouldRecordContactNumber(preferences, null)).isFalse()
    assertThat(CallRecordingPreferenceValues.shouldRecordContactNumber(preferences, "")).isFalse()
  }

  @Test
  fun switchingEitherDirectionClearsNumbersAndPreservesEnablement() {
    val transitions = listOf(
        SELECTED_NUMBERS to ALL_EXCEPT_SELECTED_NUMBERS,
        ALL_EXCEPT_SELECTED_NUMBERS to SELECTED_NUMBERS)
    for ((from, to) in transitions) {
      val builder = preferences(from).addAutoRecordSelectedNumbers(NUMBER)
      CallRecordingPreferenceValues.switchContactRecordingMode(builder, from, setOf(NUMBER), to)
      val saved = CallRecordingPreferences.parseFrom(builder.build().toByteArray())

      assertThat(saved.contactRecordingMode).isEqualTo(to)
      assertThat(saved.autoRecordSelectedNumbersList).isEmpty()
      assertThat(saved.autoRecordContactsEnabled).isTrue()
    }
  }

  @Test
  fun confirmingAnOldListDoesNotDiscardNewNumbers() {
    val builder =
        preferences(SELECTED_NUMBERS)
            .addAutoRecordSelectedNumbers(NUMBER)
            .addAutoRecordSelectedNumbers(OTHER_NUMBER)
    val before = builder.build()

    assertThrows(IllegalStateException::class.java) {
      CallRecordingPreferenceValues.switchContactRecordingMode(
          builder, SELECTED_NUMBERS, setOf(NUMBER), ALL_EXCEPT_SELECTED_NUMBERS)
    }
    assertThat(builder.build()).isEqualTo(before)
  }

  @Test
  fun confirmingAnOldChoiceDoesNotClearTheNewMode() {
    val builder = preferences(ALL_EXCEPT_SELECTED_NUMBERS).addAutoRecordSelectedNumbers(NUMBER)
    val before = builder.build()

    assertThrows(IllegalStateException::class.java) {
      CallRecordingPreferenceValues.switchContactRecordingMode(
          builder, SELECTED_NUMBERS, setOf(NUMBER), ALL_EXCEPT_SELECTED_NUMBERS)
    }
    assertThat(builder.build()).isEqualTo(before)
  }

  private fun preferences(mode: ContactRecordingMode) =
      CallRecordingPreferences.newBuilder()
          .setAutoRecordContactsEnabled(true)
          .setContactRecordingMode(mode)

  private companion object {
    const val NUMBER = "+15551230001"
    const val OTHER_NUMBER = "+15551230002"
  }
}
