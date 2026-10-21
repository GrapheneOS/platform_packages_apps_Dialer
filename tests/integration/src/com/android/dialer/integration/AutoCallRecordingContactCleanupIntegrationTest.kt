package com.android.dialer.integration

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.dialer.callrecord.AutoCallRecordingStaleContactCleaner
import com.android.dialer.callrecord.AutoCallRecordingStaleContactCleanupJobService
import com.android.dialer.callrecord.AutoCallRecordingStaleContactCleanupScheduler
import com.android.dialer.callrecord.CallRecordingPreferenceValues
import com.android.dialer.callrecord.CallRecordingPreferencesStore
import com.android.dialer.constants.ScheduledJobIds
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contact cleanup integration coverage for selected automatic recording numbers.
 *
 * The direct cleaner tests keep contact resolution failures easy to diagnose and cover preserving
 * selected numbers that still resolve to contacts. The scheduled cleanup test keeps the setup to
 * stale numbers so it only exercises the JobService entrypoint.
 */
@RunWith(AndroidJUnit4::class)
internal class AutoCallRecordingContactCleanupIntegrationTest :
    AutoCallRecordingIntegrationTestBase() {

  @Test
  fun cleanupKeepsSelectedNumbersThatStillResolveToContacts() {
    runBlocking {
      assumeTrue(isUserUnlocked())
      grantTargetPermission(Manifest.permission.READ_CONTACTS)
      grantTargetPermission(Manifest.permission.WRITE_CONTACTS)
      resetScheduledCleanup()
      try {
        insertLocalContact("Dialer Integration A", TEST_NUMBER_FORMATTED)
        insertLocalContact("Dialer Integration B", SECOND_TEST_NUMBER_FORMATTED)
        waitForContactNumber(TEST_NUMBER)
        waitForContactNumber(SECOND_TEST_NUMBER)
        seedSelectedNumberRecordingPreferences(TEST_NUMBER, SECOND_TEST_NUMBER)

        val result = AutoCallRecordingStaleContactCleaner.clean(targetContext)

        assertThat(result.selectedNumberCount).isEqualTo(2)
        assertThat(result.staleNumberCount).isEqualTo(0)
        assertThat(result.changed).isFalse()
        assertThat(selectedNumbers()).containsExactly(TEST_NUMBER, SECOND_TEST_NUMBER)
      } finally {
        resetScheduledCleanup()
      }
    }
  }

  @Test
  fun cleanupRemovesOnlySelectedNumbersThatNoLongerResolveToContacts() {
    runBlocking {
      assumeTrue(isUserUnlocked())
      grantTargetPermission(Manifest.permission.READ_CONTACTS)
      grantTargetPermission(Manifest.permission.WRITE_CONTACTS)
      resetScheduledCleanup()
      try {
        insertLocalContact("Dialer Integration A", TEST_NUMBER_FORMATTED)
        insertLocalContact("Dialer Integration C", THIRD_TEST_NUMBER_FORMATTED)
        waitForContactNumber(TEST_NUMBER)
        waitForContactNumber(THIRD_TEST_NUMBER)
        seedSelectedNumberRecordingPreferences(TEST_NUMBER, SECOND_TEST_NUMBER, THIRD_TEST_NUMBER)
        assumeTrue(numberIsNotInContacts(SECOND_TEST_NUMBER))

        val result = AutoCallRecordingStaleContactCleaner.clean(targetContext)

        assertThat(result.selectedNumberCount).isEqualTo(3)
        assertThat(result.staleNumberCount).isEqualTo(1)
        assertThat(result.changed).isTrue()
        assertThat(selectedNumbers()).containsExactly(TEST_NUMBER, THIRD_TEST_NUMBER)
      } finally {
        resetScheduledCleanup()
      }
    }
  }

  @Test
  fun scheduledCleanupRemovesSelectedNumbersThatDoNotResolveToContacts() {
    assumeTrue(isUserUnlocked())
    grantTargetPermission(Manifest.permission.READ_CONTACTS)
    seedSelectedNumberRecordingPreferences(TEST_NUMBER, SECOND_TEST_NUMBER, THIRD_TEST_NUMBER)
    assumeTrue(numberIsNotInContacts(TEST_NUMBER))
    assumeTrue(numberIsNotInContacts(SECOND_TEST_NUMBER))
    assumeTrue(numberIsNotInContacts(THIRD_TEST_NUMBER))

    AutoCallRecordingStaleContactCleanupScheduler.resetForTesting()
    try {
      scheduleJob(
          ScheduledJobIds.AUTO_CALL_RECORDING_STALE_CONTACT_CLEANUP_JOB,
          AutoCallRecordingStaleContactCleanupJobService::class.java)
      runScheduledJob(ScheduledJobIds.AUTO_CALL_RECORDING_STALE_CONTACT_CLEANUP_JOB)

      waitUntil("scheduled cleanup to clear stale selected numbers") {
        selectedNumbers().isEmpty()
      }
    } finally {
      AutoCallRecordingStaleContactCleanupScheduler.resetForTesting()
      AutoCallRecordingStaleContactCleanupJobService.cancelJob(targetContext)
    }
  }

  private fun selectedNumbers(): Set<String> {
    return CallRecordingPreferenceValues.selectedNumbers(
        CallRecordingPreferencesStore.loadAsync(targetContext).get())
  }

  private fun resetScheduledCleanup() {
    // Direct cleaner tests own their cleanup trigger; keep the app observer from consuming the
    // preference state before the explicit clean() call.
    AutoCallRecordingStaleContactCleanupScheduler.resetForTesting()
    AutoCallRecordingStaleContactCleanupJobService.cancelJob(targetContext)
  }
}
