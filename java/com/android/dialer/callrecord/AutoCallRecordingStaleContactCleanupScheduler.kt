package com.android.dialer.callrecord

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import android.support.annotation.VisibleForTesting
import com.android.dialer.common.LogUtil
import com.android.dialer.common.concurrent.DialerExecutorComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Keeps the stale contact cleanup job in sync with automatic recording preferences. */
object AutoCallRecordingStaleContactCleanupScheduler {
  private val lock = Any()
  private var observerJob: Job? = null
  // One-shot retry registered while credential encrypted storage is unavailable. It is stored with
  // the registering context so a later unlocked start can unregister it even if the broadcast never
  // arrives.
  private var unlockRetry: UnlockRetry? = null

  @JvmStatic
  fun start(context: Context) {
    val appContext = applicationContext(context) ?: return
    var unlockRetryToClear: UnlockRetry? = null
    synchronized(lock) {
      if (observerJob != null) {
        return
      }
      if (!isCredentialEncryptedStorageAvailable(appContext)) {
        registerUnlockRetryLocked(appContext)
        return
      }
      unlockRetryToClear = takeUnlockRetryLocked()
      startObserverLocked(appContext)
    }
    unregisterUnlockRetry(unlockRetryToClear)
  }

  private fun registerUnlockRetryLocked(appContext: Context) {
    if (unlockRetry != null) {
      return
    }
    val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(unusedContext: Context, intent: Intent) {
            if (Intent.ACTION_USER_UNLOCKED != intent.action) {
              return
            }
            clearUnlockRetry(this)
            start(appContext)
          }
        }
    appContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
    unlockRetry = UnlockRetry(receiver, appContext)
  }

  private fun clearUnlockRetry(receiver: BroadcastReceiver) {
    val unlockRetryToClear =
        synchronized(lock) {
          if (unlockRetry?.receiver !== receiver) {
            null
          } else {
            takeUnlockRetryLocked()
          }
        }
    unregisterUnlockRetry(unlockRetryToClear)
  }

  private fun takeUnlockRetryLocked(): UnlockRetry? {
    return unlockRetry.also {
      unlockRetry = null
    }
  }

  private fun unregisterUnlockRetry(unlockRetryToClear: UnlockRetry?) {
    unlockRetryToClear?.context?.unregisterReceiver(unlockRetryToClear.receiver)
  }

  private fun startObserverLocked(appContext: Context) {
    val dispatcher =
        DialerExecutorComponent.get(appContext).lowPriorityThreadPool().asCoroutineDispatcher()
    observerJob =
        CoroutineScope(dispatcher + SupervisorJob()).launch {
          try {
            var lastShouldSchedule: Boolean? = null
            CallRecordingPreferencesStore.preferencesFlow(appContext).collect { preferences ->
              val shouldSchedule =
                  AutoCallRecordingStaleContactCleanupJobService.shouldScheduleCleanup(preferences)
              if (shouldSchedule != lastShouldSchedule) {
                lastShouldSchedule = shouldSchedule
                AutoCallRecordingStaleContactCleanupJobService.reconcileJobForPreferences(
                    appContext, preferences)
              }
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            LogUtil.e(
                "AutoCallRecordingStaleContactCleanupScheduler.start",
                "stale contact cleanup scheduler stopped",
                e)
            synchronized(lock) { observerJob = null }
          }
        }
  }

  @VisibleForTesting
  @JvmStatic
  fun resetForTesting() {
    var observerJobToCancel: Job? = null
    val receiverAndContext =
        synchronized(lock) {
          observerJobToCancel = observerJob
          observerJob = null
          takeUnlockRetryLocked()
        }
    // Tests mutate the same DataStore and JobScheduler state across methods. Wait for the old
    // collector to finish so it cannot reconcile stale preferences after the next test starts.
    runBlocking {
      observerJobToCancel?.cancelAndJoin()
    }
    unregisterUnlockRetry(receiverAndContext)
  }

  private fun isCredentialEncryptedStorageAvailable(context: Context): Boolean {
    val userManager = context.getSystemService(UserManager::class.java)
    return userManager == null || userManager.isUserUnlocked
  }

  private fun applicationContext(context: Context): Context? {
    val appContext = context.applicationContext
    if (appContext == null) {
      LogUtil.w(
          "AutoCallRecordingStaleContactCleanupScheduler.start",
          "application context unavailable")
    }
    return appContext
  }

  // Keep the receiver with the application context that registered it so any later unlocked start
  // can unregister the one-shot retry even if ACTION_USER_UNLOCKED has not been delivered yet.
  private data class UnlockRetry(
      val receiver: BroadcastReceiver,
      val context: Context
  )
}
