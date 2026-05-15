package com.android.dialer.callrecord

import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.support.annotation.VisibleForTesting
import com.android.dialer.common.LogUtil
import com.android.dialer.common.concurrent.DialerExecutorComponent
import com.android.dialer.constants.ScheduledJobIds
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Periodically removes selected automatic recording numbers that no longer resolve to contacts.
 *
 * JobScheduler owns retry policy. This service owns one coroutine Job so stopped work can be
 * canceled and ignored without a stale `jobFinished` callback.
 */
class AutoCallRecordingStaleContactCleanupJobService : JobService() {

  @Volatile private var cleanupJob: Job? = null

  override fun onStartJob(params: JobParameters): Boolean {
    LogUtil.enterBlock("AutoCallRecordingStaleContactCleanupJobService.onStartJob")
    val appContext = appContext(this)
    val dispatcher =
        DialerExecutorComponent.get(appContext)
            .lowPriorityThreadPool()
            .asCoroutineDispatcher()
    val job =
        CoroutineScope(dispatcher).launch(start = CoroutineStart.LAZY) {
          try {
            val preferences = CallRecordingPreferencesStore.load(appContext)
            if (shouldScheduleCleanup(preferences)) {
              AutoCallRecordingStaleContactCleaner.clean(appContext)
            } else {
              cancelJob(appContext)
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            LogUtil.e(
                "AutoCallRecordingStaleContactCleanupJobService.onStartJob",
                "cleanup failed",
                e)
          } finally {
            finishJob(params, requireNotNull(coroutineContext[Job]), false /* retry */)
          }
        }
    cleanupJob = job
    job.start()
    return true
  }

  override fun onStopJob(params: JobParameters): Boolean {
    LogUtil.enterBlock("AutoCallRecordingStaleContactCleanupJobService.onStopJob")
    val job = cleanupJob
    cleanupJob = null
    job?.cancel()
    return shouldRetryStoppedCleanupJob()
  }

  private fun finishJob(params: JobParameters, job: Job, retry: Boolean) {
    if (cleanupJob !== job) {
      return
    }
    cleanupJob = null
    jobFinished(params, retry)
  }

  companion object {
    private val CLEANUP_PERIOD_MILLIS = TimeUnit.HOURS.toMillis(24)

    @SuppressLint("MissingPermission") // Dialer has RECEIVE_BOOT_COMPLETED for persisted jobs.
    @JvmStatic
    fun reconcileJobForPreferences(inputContext: Context, preferences: CallRecordingPreferences) {
      val context = appContext(inputContext)
      if (!shouldScheduleCleanup(preferences)) {
        cancelJob(context)
        return
      }

      val jobScheduler = context.getSystemService(JobScheduler::class.java)
      if (jobScheduler == null) {
        LogUtil.w(
            "AutoCallRecordingStaleContactCleanupJobService.reconcileJobForPreferences",
            "JobScheduler unavailable")
        return
      }
      if (jobScheduler.getPendingJob(
              ScheduledJobIds.AUTO_CALL_RECORDING_STALE_CONTACT_CLEANUP_JOB) != null) {
        LogUtil.i(
            "AutoCallRecordingStaleContactCleanupJobService.reconcileJobForPreferences",
            "job already scheduled")
        return
      }

      val jobInfo =
          JobInfo.Builder(
                  ScheduledJobIds.AUTO_CALL_RECORDING_STALE_CONTACT_CLEANUP_JOB,
                  ComponentName(context, AutoCallRecordingStaleContactCleanupJobService::class.java))
              .setPeriodic(CLEANUP_PERIOD_MILLIS)
              .setPersisted(true)
              .build()
      if (jobScheduler.schedule(jobInfo) == JobScheduler.RESULT_SUCCESS) {
        LogUtil.i(
            "AutoCallRecordingStaleContactCleanupJobService.reconcileJobForPreferences",
            "job scheduled")
      } else {
        LogUtil.w(
            "AutoCallRecordingStaleContactCleanupJobService.reconcileJobForPreferences",
            "job scheduling failed")
      }
    }

    @JvmStatic
    fun cancelJob(context: Context) {
      val jobScheduler = context.getSystemService(JobScheduler::class.java)
      if (jobScheduler == null) {
        LogUtil.w(
            "AutoCallRecordingStaleContactCleanupJobService.cancelJob",
            "JobScheduler unavailable")
        return
      }
      jobScheduler.cancel(ScheduledJobIds.AUTO_CALL_RECORDING_STALE_CONTACT_CLEANUP_JOB)
      LogUtil.i("AutoCallRecordingStaleContactCleanupJobService.cancelJob", "job canceled")
    }

    @VisibleForTesting
    @JvmStatic
    fun shouldScheduleCleanup(preferences: CallRecordingPreferences): Boolean {
      // Only selected number recording depends on contact lookup state.
      return preferences.autoRecordSelectedNumbersEnabled &&
          preferences.autoRecordSelectedNumbersCount > 0
    }

    @VisibleForTesting
    @JvmStatic
    fun shouldRetryStoppedCleanupJob(): Boolean {
      // Cleanup is periodic and conservative; stopped work waits for the next scheduled run.
      return false
    }

    private fun appContext(context: Context): Context {
      return context.applicationContext ?: context
    }
  }
}
