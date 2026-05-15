package com.android.dialer.callrecord

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import android.support.annotation.VisibleForTesting
import android.support.annotation.WorkerThread
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.android.dialer.common.Assert
import com.android.dialer.common.LogUtil
import com.android.dialer.common.concurrent.DialerExecutorComponent
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Credential encrypted, multi-process storage for call recording preferences. */
object CallRecordingPreferencesStore {

  const val KEY_CALL_RECORDING_USE_V2 = "call_recording_use_v2"
  const val KEY_CALL_RECORDING_AUDIO_SOURCE = "call_recording_audio_source"
  const val KEY_CALL_RECORDING_OUTPUT_FORMAT = "call_recording_output_format"
  const val KEY_CALL_RECORDING_OUTPUT_FORMAT_V2 = "call_recording_output_format_v2"
  const val KEY_AUTO_RECORD_NON_CONTACTS = "call_recording_auto_record_non_contacts"
  const val KEY_AUTO_RECORD_SELECTED_NUMBERS_ENABLED =
      "call_recording_auto_record_selected_numbers_enabled"
  const val KEY_AUTO_RECORD_SELECTED_NUMBERS = "call_recording_auto_record_selected_numbers"
  const val KEY_AUTO_RECORDING_SET_AT_LEAST_ONCE =
      "call_recording_auto_recording_set_at_least_once"
  const val KEY_RECORDING_WARNING_PRESENTED = "recording_warning_presented"

  @VisibleForTesting
  const val DATASTORE_FILE_NAME = "call_recording_preferences.pb"

  private val DEFAULT_PREFERENCES = CallRecordingPreferenceValues.DEFAULT_PREFERENCES
  private val DATASTORE_LOCK = Any()

  private var dataStore: DataStore<CallRecordingPreferences>? = null
  private var coroutineScope: CoroutineScope? = null

  /**
   * Returns DataStore's own cached preference Flow.
   *
   * The androidx DataStore implementation already keeps an in-memory cache behind this Flow, so
   * this store does not maintain a second process-wide snapshot. Most new Kotlin UI should collect
   * this directly. Java and service callers still use the Future or blocking bridges below until
   * those layers move away from callback style lifecycles.
   */
  fun preferencesFlow(context: Context): Flow<CallRecordingPreferences> {
    if (!isCredentialEncryptedStorageAvailable(context)) {
      return flowOf(DEFAULT_PREFERENCES)
    }
    return dataStore(context).data
  }

  suspend fun load(context: Context): CallRecordingPreferences =
      readPreferencesOrDefaultSuspend(context)

  /**
   * Java bridge for callers that cannot collect DataStore Flow yet.
   *
   * The returned Future reads through DataStore's cached Flow and is not tied to caller
   * cancellation because preference reads are shared DataStore work. Kotlin code should prefer
   * [load] or [preferencesFlow].
   */
  @JvmStatic
  fun loadAsync(context: Context): ListenableFuture<CallRecordingPreferences> {
    if (!isCredentialEncryptedStorageAvailable(context)) {
      return Futures.immediateFuture(DEFAULT_PREFERENCES)
    }
    return Futures.nonCancellationPropagating(
        runDataStoreAsync(context) { readPreferencesOrDefaultSuspend(context) })
  }

  /**
   * Adapts preference load futures for Java callers.
   *
   * Callers still own lifecycle guards and failure logging. This only avoids repeating anonymous
   * FutureCallback classes at Java callback boundaries.
   */
  @JvmStatic
  fun addLoadCallback(
      future: ListenableFuture<CallRecordingPreferences>,
      callbackExecutor: Executor,
      onSuccess: Consumer<CallRecordingPreferences>,
      onFailure: Consumer<Throwable>
  ) {
    Futures.addCallback(
        future,
        object : FutureCallback<CallRecordingPreferences> {
          override fun onSuccess(result: CallRecordingPreferences?) {
            onSuccess.accept(requireNotNull(result))
          }

          override fun onFailure(t: Throwable) {
            onFailure.accept(t)
          }
        },
        callbackExecutor)
  }

  fun interface PreferencesMutation {
    fun mutate(builder: CallRecordingPreferences.Builder)
  }

  /**
   * Blocking bridge for Java services and tests that must choose recorder parameters synchronously.
   *
   * DataStore owns its in-memory cache; this method should not be used to build another process
   * snapshot. Kotlin UI and policy code should prefer [load] or [preferencesFlow].
   */
  @JvmStatic
  @WorkerThread
  fun readBlocking(context: Context): CallRecordingPreferences =
      readPreferencesOrDefault(context)

  suspend fun update(
      context: Context,
      mutation: PreferencesMutation
  ): CallRecordingPreferences =
      runDataStoreOperation(context) { updatePreferences(context, mutation) }

  // DataStore updateData returns only the updated proto. Keep result-producing writes centralized
  // so UI callers do not hide operation results in variables captured by mutation lambdas.
  internal suspend fun <T> updateWithResult(
      context: Context,
      defaultResult: T,
      transform: (CallRecordingPreferences) -> Pair<CallRecordingPreferences, T>
  ): T {
    if (!isCredentialEncryptedStorageAvailable(context)) {
      LogUtil.w(
          "CallRecordingPreferencesStore.updateWithResult",
          "credential encrypted storage unavailable; ignoring write")
      return defaultResult
    }
    var result = defaultResult
    updatePreferencesWithoutMigrationSuspend(context) { preferences ->
      val update = transform(preferences)
      result = update.second
      update.first
    }
    return result
  }

  private suspend fun updatePreferences(
      context: Context,
      mutation: PreferencesMutation
  ): CallRecordingPreferences {
    return updatePreferencesSuspend(context) { preferences ->
      preferences.toBuilder().also { mutation.mutate(it) }.build()
    }
  }

  /** Blocking Java/test bridge for preference writes; UI code should prefer [update]. */
  @JvmStatic
  @WorkerThread
  fun updateBlocking(
      context: Context,
      mutation: PreferencesMutation
  ): CallRecordingPreferences {
    return runDataStoreBlocking { updatePreferences(context, mutation) }
  }

  @JvmStatic
  @VisibleForTesting
  fun getLegacySharedPreferencesForTesting(context: Context): SharedPreferences =
      getLegacySharedPreferences(context)

  @VisibleForTesting
  @JvmStatic
  fun resetForTesting(context: Context, sharedPreferencesMigrated: Boolean) {
    if (!isCredentialEncryptedStorageAvailable(context)) {
      return
    }
    val preferences =
        DEFAULT_PREFERENCES.toBuilder()
            .setSharedPreferencesMigrated(sharedPreferencesMigrated)
            .build()
    runDataStoreBlocking { updatePreferencesWithoutMigrationSuspend(context) { preferences } }
    resetDataStoreForTesting()
  }

  @WorkerThread
  private fun readPreferencesOrDefault(context: Context): CallRecordingPreferences {
    Assert.isWorkerThread("Blocking call recording preference reads must stay off the main thread")
    return runDataStoreBlocking { readPreferencesOrDefaultSuspend(context) }
  }

  private suspend fun readPreferencesOrDefaultSuspend(context: Context): CallRecordingPreferences {
    if (!isCredentialEncryptedStorageAvailable(context)) {
      return DEFAULT_PREFERENCES
    }
    return dataStore(context).data.first()
  }

  private suspend fun updatePreferencesSuspend(
      context: Context,
      transform: (CallRecordingPreferences) -> CallRecordingPreferences
  ): CallRecordingPreferences {
    if (!isCredentialEncryptedStorageAvailable(context)) {
      LogUtil.w(
          "CallRecordingPreferencesStore.updatePreferencesSuspend",
          "credential encrypted storage unavailable; ignoring write")
      return DEFAULT_PREFERENCES
    }
    return updatePreferencesWithoutMigrationSuspend(context, transform)
  }

  private suspend fun updatePreferencesWithoutMigrationSuspend(
      context: Context,
      transform: (CallRecordingPreferences) -> CallRecordingPreferences
  ): CallRecordingPreferences {
    return dataStore(context).updateData { input -> transform(input) }
  }

  private fun <T> runDataStoreAsync(
      context: Context,
      operation: suspend () -> T
  ): ListenableFuture<T> {
    val future = SettableFuture.create<T>()
    dataStoreScope(context).launch {
      try {
        future.set(operation())
      } catch (e: CancellationException) {
        future.cancel(false)
        throw e
      } catch (e: Exception) {
        future.setException(e)
      }
    }
    return future
  }

  private suspend fun <T> runDataStoreOperation(context: Context, operation: suspend () -> T): T {
    // Once a UI action reaches the store, the DataStore write belongs to the store scope rather
    // than the temporary caller scope. Cancelling the caller only stops waiting for the result.
    return dataStoreScope(context).async { operation() }.await()
  }

  @WorkerThread
  private fun migrateFromSharedPreferences(
      context: Context,
      preferences: CallRecordingPreferences
  ): CallRecordingPreferences {
    val prefs = getLegacySharedPreferences(context)
    val builder = preferences.toBuilder()
    // These are the old SharedPreferences XML keys. Keep migration pinned to these raw strings
    // so future DataStore or settings key changes do not change which legacy values are imported.
    if (prefs.contains("call_recording_use_v2")) {
      builder.setUseCallRecordingV2(prefs.getBoolean("call_recording_use_v2", false))
    }
    copyStringPreference(prefs, "call_recording_audio_source") { value ->
      builder.setCallRecordingAudioSource(value)
    }
    copyOutputFormatPreference(prefs, "call_recording_output_format") { value ->
      builder.setCallRecordingOutputFormat(value)
    }
    copyOutputFormatPreference(prefs, "call_recording_output_format_v2") { value ->
      builder.setCallRecordingOutputFormatV2(value)
    }
    LogUtil.i(
        "CallRecordingPreferencesStore.migrateFromSharedPreferences",
        "migrated legacy call recording preferences")
    return builder.build()
  }

  private fun clearMigratedSharedPreferencesKeys(context: Context) {
    getLegacySharedPreferences(context)
        .edit()
        .remove("call_recording_use_v2")
        .remove("call_recording_audio_source")
        .remove("call_recording_output_format")
        .remove("call_recording_output_format_v2")
        .commit()
  }

  private fun isCredentialEncryptedStorageAvailable(context: Context): Boolean {
    val userManager = appContext(context).getSystemService(UserManager::class.java)
    return userManager == null || userManager.isUserUnlocked
  }

  private fun dataStore(context: Context): DataStore<CallRecordingPreferences> {
    synchronized(DATASTORE_LOCK) {
      var current = dataStore
      if (current == null) {
        val appContext = appContext(context)
        // Use the normal application context so DataStore remains credential encrypted.
        // TODO: When AOSP prebuilts DataStore 1.3.0-alpha07 or newer, migrate to the
        // CoroutineContext builder API:
        // https://developer.android.com/jetpack/androidx/releases/datastore#1.3.0-alpha07
        // AOSP currently prebuilts 1.2.0-alpha03, so multiprocess creation still requires a
        // CoroutineScope. When updating, keep the Job scoped to the application so DataStore work
        // cannot be canceled by a temporary UI scope.
        current =
            MultiProcessDataStoreFactory.create(
                serializer = CallRecordingPreferencesSerializer,
                corruptionHandler =
                    ReplaceFileCorruptionHandler { exception ->
                      LogUtil.e(
                          "CallRecordingPreferencesStore.dataStore",
                          "replacing corrupted call recording preferences",
                          exception)
                      DEFAULT_PREFERENCES
                    },
                migrations = listOf(LegacySharedPreferencesMigration(appContext)),
                scope = dataStoreScope(appContext),
                produceFile = { appContext.dataStoreFile(DATASTORE_FILE_NAME) })
        dataStore = current
      }
      return current
    }
  }

  private fun <T> runDataStoreBlocking(operation: suspend () -> T): T {
    try {
      return runBlocking { operation() }
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw IllegalStateException("Interrupted while accessing call recording preferences", e)
    }
  }

  private fun dataStoreScope(context: Context): CoroutineScope {
    synchronized(DATASTORE_LOCK) {
      coroutineScope?.let {
        return it
      }
      val dispatcher =
          DialerExecutorComponent.get(appContext(context))
              .lowPriorityThreadPool()
              .asCoroutineDispatcher()
      return CoroutineScope(dispatcher + SupervisorJob()).also { scope ->
        coroutineScope = scope
      }
    }
  }

  @VisibleForTesting
  private fun resetDataStoreForTesting() {
    val scopeToCancel: CoroutineScope?
    synchronized(DATASTORE_LOCK) {
      dataStore = null
      scopeToCancel = coroutineScope
      coroutineScope = null
    }
    runDataStoreBlocking {
      scopeToCancel?.coroutineContext?.get(Job)?.cancelAndJoin()
    }
  }

  private fun copyStringPreference(
      prefs: SharedPreferences,
      key: String,
      setter: (String) -> Unit
  ) {
    if (!prefs.contains(key)) {
      return
    }
    val value = prefs.getString(key, null)
    if (!value.isNullOrEmpty()) {
      setter(value)
    }
  }

  private fun copyOutputFormatPreference(
      prefs: SharedPreferences,
      key: String,
      setter: (RecordingOutputFormat) -> Unit
  ) {
    if (!prefs.contains(key)) {
      return
    }
    val value = parseLegacyRecordingOutputFormat(prefs.getString(key, null))
    if (value != null) {
      setter(value)
    }
  }

  private fun parseLegacyRecordingOutputFormat(outputFormat: String?): RecordingOutputFormat? {
    return when (outputFormat?.toIntOrNull()) {
      0 -> RecordingOutputFormat.AAC_MPEG_4
      1 -> RecordingOutputFormat.AMR_WB
      2 -> RecordingOutputFormat.LPCM_WAV
      else -> null
    }
  }

  private fun getLegacySharedPreferences(context: Context): SharedPreferences {
    val prefName = appContext(context).packageName + "_preferences"
    // Legacy code used MODE_MULTI_PROCESS, but this single migration is not multiprocess.
    // The file name is unchanged; DataStore handles multiprocess access after import.
    return appContext(context).getSharedPreferences(prefName, Context.MODE_PRIVATE)
  }

  private fun appContext(context: Context): Context {
    return context.applicationContext ?: context
  }

  private class LegacySharedPreferencesMigration(private val context: Context) :
      DataMigration<CallRecordingPreferences> {
    override suspend fun shouldMigrate(currentData: CallRecordingPreferences): Boolean {
      return !currentData.sharedPreferencesMigrated
    }

    override suspend fun migrate(
        currentData: CallRecordingPreferences
    ): CallRecordingPreferences {
      return CallRecordingPreferencesStore.migrateFromSharedPreferences(context, currentData)
          .toBuilder()
          .setSharedPreferencesMigrated(true)
          .build()
    }

    override suspend fun cleanUp() {
      CallRecordingPreferencesStore.clearMigratedSharedPreferencesKeys(context)
    }
  }
}
