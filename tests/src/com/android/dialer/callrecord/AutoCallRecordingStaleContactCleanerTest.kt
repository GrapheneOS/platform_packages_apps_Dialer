package com.android.dialer.callrecord

import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.dialer.callrecord.AutoCallRecordingContactResolver.ResolveResult
import com.android.dialer.callrecord.AutoCallRecordingContactResolver.ResolvedSelectedNumber
import com.android.dialer.constants.ScheduledJobIds
import com.android.dialer.inject.HasRootComponent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

@RunWith(AndroidJUnit4::class)
class AutoCallRecordingStaleContactCleanerTest {

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    AutoCallRecordingStaleContactCleanupScheduler.resetForTesting()
    clearPrefs()
  }

  @After
  fun tearDown() {
    AutoCallRecordingStaleContactCleanupScheduler.resetForTesting()
    clearPrefs()
    AutoCallRecordingStaleContactCleanupJobService.cancelJob(context)
  }

  @Test
  fun cleanKeepsSelectedNumbersWhenAllStillMatchContacts() = runTest {
    writeSettings(true, setOf(LOCAL_NUMBER, OTHER_LOCAL_NUMBER))

    val result =
        AutoCallRecordingStaleContactCleaner.clean(context) { _, selectedNumbers ->
          resolveResult(selectedNumbers)
        }

    assertThat(getSelectedNumbers()).containsExactly(LOCAL_NUMBER, OTHER_LOCAL_NUMBER)
    assertThat(result.selectedNumberCount).isEqualTo(2)
    assertThat(result.staleNumberCount).isEqualTo(0)
    assertThat(result.changed).isFalse()
  }

  @Test
  fun cleanRemovesOnlyNumbersThatNoLongerResolveToContacts() = runTest {
    writeSettings(true, setOf(LOCAL_NUMBER, STALE_NUMBER))

    val result =
        AutoCallRecordingStaleContactCleaner.clean(context) { _, selectedNumbers ->
          assertThat(selectedNumbers).containsExactly(LOCAL_NUMBER, STALE_NUMBER)
          resolveResult(selectedNumbers, STALE_NUMBER)
        }

    assertThat(getSelectedNumbers()).containsExactly(LOCAL_NUMBER)
    assertThat(result.selectedNumberCount).isEqualTo(2)
    assertThat(result.staleNumberCount).isEqualTo(1)
    assertThat(result.changed).isTrue()
  }

  @Test
  fun cleanKeepsNumbersAddedWhileCleanupIsResolvingContacts() = runTest {
    writeSettings(true, setOf(LOCAL_NUMBER, STALE_NUMBER))

    val result =
        AutoCallRecordingStaleContactCleaner.clean(context) { _, selectedNumbers ->
          writeSettings(true, setOf(LOCAL_NUMBER, STALE_NUMBER, OTHER_LOCAL_NUMBER))
          resolveResult(selectedNumbers, STALE_NUMBER)
        }

    assertThat(getSelectedNumbers()).containsExactly(LOCAL_NUMBER, OTHER_LOCAL_NUMBER)
    assertThat(result.selectedNumberCount).isEqualTo(2)
    assertThat(result.staleNumberCount).isEqualTo(1)
    assertThat(result.changed).isTrue()
  }

  @Test
  fun cleanPreservesNumbersWhenContactLookupFails() = runTest {
    writeSettings(true, setOf(LOCAL_NUMBER, STALE_NUMBER))

    val result =
        AutoCallRecordingStaleContactCleaner.clean(context) { _, _ ->
          throw RuntimeException("lookup failed")
        }

    assertThat(getSelectedNumbers()).containsExactly(LOCAL_NUMBER, STALE_NUMBER)
    assertThat(result.selectedNumberCount).isEqualTo(2)
    assertThat(result.staleNumberCount).isEqualTo(0)
    assertThat(result.changed).isFalse()
  }

  @Test
  fun cleanSkipsWhenSelectedNumberRecordingIsDisabled() = runTest {
    writeSettings(false, setOf(STALE_NUMBER))

    val result =
        AutoCallRecordingStaleContactCleaner.clean(context) { _, _ ->
          throw AssertionError("lookup should not run when selected number recording is off")
        }

    assertThat(getSelectedNumbers()).containsExactly(STALE_NUMBER)
    assertThat(result.selectedNumberCount).isEqualTo(1)
    assertThat(result.staleNumberCount).isEqualTo(0)
    assertThat(result.changed).isFalse()
  }

  @Test
  fun cleanupIsScheduledOnlyWhenSelectedNumberRecordingHasStoredNumbers() {
    writeSettings(false, setOf(LOCAL_NUMBER))
    CallRecordingPreferencesStore.updateBlocking(context) { builder ->
      builder.setAutoRecordNonContacts(true).setAutoRecordingSetAtLeastOnce(true)
    }

    assertThat(
            AutoCallRecordingStaleContactCleanupJobService.shouldScheduleCleanup(
                CallRecordingPreferencesStore.readBlocking(context)))
        .isFalse()

    writeSettings(true, emptySet())
    assertThat(
            AutoCallRecordingStaleContactCleanupJobService.shouldScheduleCleanup(
                CallRecordingPreferencesStore.readBlocking(context)))
        .isFalse()

    writeSettings(true, setOf(LOCAL_NUMBER))
    assertThat(
            AutoCallRecordingStaleContactCleanupJobService.shouldScheduleCleanup(
                CallRecordingPreferencesStore.readBlocking(context)))
        .isTrue()
  }

  @Test
  fun cleanupSchedulerStartsAfterUserUnlock() {
    writeSettings(true, setOf(LOCAL_NUMBER))
    AutoCallRecordingStaleContactCleanupJobService.cancelJob(context)
    val lockedContext = UnlockableUserContext(context)

    AutoCallRecordingStaleContactCleanupScheduler.start(lockedContext)

    assertThat(lockedContext.registeredForUserUnlock()).isTrue()
    assertThat(cleanupJobIsScheduled()).isFalse()

    lockedContext.unlock()

    waitUntil(::cleanupJobIsScheduled)
    assertThat(lockedContext.unregisteredUnlockReceiver()).isTrue()
  }

  @Test
  fun cleanupSchedulerStartsIfDialerRunsAgainAfterUnlock() {
    writeSettings(true, setOf(LOCAL_NUMBER))
    AutoCallRecordingStaleContactCleanupJobService.cancelJob(context)
    val lockedContext = UnlockableUserContext(context)

    AutoCallRecordingStaleContactCleanupScheduler.start(lockedContext)
    lockedContext.setUnlocked()

    // Model a later Dialer process after unlock when ACTION_USER_UNLOCKED was not delivered to the
    // previous process. Reset process local scheduler state so this does not depend on an old
    // receiver path while the device screen or keyguard state changes.
    AutoCallRecordingStaleContactCleanupScheduler.resetForTesting()

    AutoCallRecordingStaleContactCleanupScheduler.start(lockedContext)

    // Do not require inline scheduling here; the cleanup is periodic and the app may reconcile
    // through observer work that is delayed while the screen is off.
    waitUntil(::cleanupJobIsScheduled)
    assertThat(lockedContext.registeredForUserUnlock()).isFalse()
    assertThat(lockedContext.unregisteredUnlockReceiver()).isTrue()
  }

  @Test
  fun cleanupJobDoesNotRequestImmediateRetryWhenStopped() {
    assertThat(AutoCallRecordingStaleContactCleanupJobService.shouldRetryStoppedCleanupJob())
        .isFalse()
  }

  private fun writeSettings(
      selectedNumberRecordingEnabled: Boolean,
      selectedNumbers: Set<String>,
  ) {
    CallRecordingPreferencesStore.updateBlocking(context) { builder ->
      builder
          .setAutoRecordSelectedNumbersEnabled(selectedNumberRecordingEnabled)
          .setAutoRecordingSetAtLeastOnce(true)
      CallRecordingPreferenceValues.setSelectedNumbers(builder, selectedNumbers)
    }
  }

  private fun getSelectedNumbers(): Set<String> {
    return CallRecordingPreferenceValues.selectedNumbers(
        CallRecordingPreferencesStore.readBlocking(context))
  }

  private fun clearPrefs() {
    CallRecordingPreferencesStore.resetForTesting(context, true)
    assertThat(
            CallRecordingPreferencesStore.getLegacySharedPreferencesForTesting(context)
                .edit()
                .remove(CallRecordingPreferencesStore.KEY_AUTO_RECORD_NON_CONTACTS)
                .remove(CallRecordingPreferencesStore.KEY_AUTO_RECORD_SELECTED_NUMBERS_ENABLED)
                .remove(CallRecordingPreferencesStore.KEY_AUTO_RECORD_SELECTED_NUMBERS)
                .remove(CallRecordingPreferencesStore.KEY_AUTO_RECORDING_SET_AT_LEAST_ONCE)
                .remove(CallRecordingPreferencesStore.KEY_RECORDING_WARNING_PRESENTED)
                .commit())
        .isTrue()
  }

  private fun cleanupJobIsScheduled(): Boolean {
    val jobScheduler = context.getSystemService(JobScheduler::class.java)
    return jobScheduler.getPendingJob(
        ScheduledJobIds.AUTO_CALL_RECORDING_STALE_CONTACT_CLEANUP_JOB) != null
  }

  private class UnlockableUserContext(private val baseContext: Context) :
      ContextWrapper(baseContext), HasRootComponent {
    private val userManager = mock(UserManager::class.java)
    private var unlockReceiver: BroadcastReceiver? = null
    private var unlocked = false
    private var unregisteredUnlockReceiver = false

    init {
      `when`(userManager.isUserUnlocked).thenAnswer { unlocked }
    }

    override fun getApplicationContext(): Context = this

    override fun component(): Any {
      return (baseContext.applicationContext as HasRootComponent).component()
    }

    override fun getSystemService(name: String): Any? {
      if (Context.USER_SERVICE == name) {
        return userManager
      }
      return super.getSystemService(name)
    }

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
      assertThat(filter?.hasAction(Intent.ACTION_USER_UNLOCKED)).isTrue()
      unlockReceiver = requireNotNull(receiver)
      return null
    }

    override fun unregisterReceiver(receiver: BroadcastReceiver?) {
      assertThat(receiver).isSameInstanceAs(unlockReceiver)
      unregisteredUnlockReceiver = true
      unlockReceiver = null
    }

    fun unlock() {
      setUnlocked()
      unlockReceiver!!.onReceive(this, Intent(Intent.ACTION_USER_UNLOCKED))
    }

    fun setUnlocked() {
      unlocked = true
    }

    fun registeredForUserUnlock(): Boolean = unlockReceiver != null

    fun unregisteredUnlockReceiver(): Boolean = unregisteredUnlockReceiver
  }

  companion object {
    private const val LOCAL_NUMBER = "+15551230001"
    private const val OTHER_LOCAL_NUMBER = "+15551230002"
    private const val STALE_NUMBER = "+15551230003"

    private fun resolveResult(
        selectedNumbers: Set<String>,
        vararg staleNumbers: String,
    ): ResolveResult {
      val stale = staleNumbers.toSet()
      val resolvedNumbers =
          selectedNumbers.associateWith { selectedNumber ->
            if (selectedNumber in stale) {
              ResolvedSelectedNumber.createUnresolved(selectedNumber)
            } else {
              ResolvedSelectedNumber(
                  selectedNumber,
                  "Local contact",
                  selectedNumber,
                  null,
                  true)
            }
          }
      return ResolveResult(resolvedNumbers, true)
    }

    private fun waitUntil(condition: () -> Boolean) {
      val deadlineMillis = System.currentTimeMillis() + 5000
      while (System.currentTimeMillis() < deadlineMillis) {
        if (condition()) {
          return
        }
        Thread.sleep(25)
      }
      assertThat(condition()).isTrue()
    }
  }
}
