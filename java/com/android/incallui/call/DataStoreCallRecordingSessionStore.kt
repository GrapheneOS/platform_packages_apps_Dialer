package com.android.incallui.call

import android.content.Context
import android.support.v4.os.UserManagerCompat
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.android.dialer.callrecord.CallRecordingSessionState
import com.android.dialer.common.LogUtil
import com.android.dialer.common.concurrent.DialerExecutorComponent
import com.google.protobuf.ExtensionRegistryLite
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first

/**
 * Credential encrypted DataStore for transient call recording session choices.
 *
 * DialerCall ids are process local, so this store keys calls with the Telecom creation time that
 * survives a Dialer process restart. The values are not user settings; they are cleared as soon as
 * the current Telecom call session no longer contains the stored calls.
 *
 * This intentionally uses a separate DataStore instead of the call recording preferences DataStore.
 * Automatic recording needs a small latch before it decides whether an already live call can start
 * recording after incallui process death. Keeping the latch outside user settings also keeps
 * temporary call session state out of durable preference migration and backup behavior.
 *
 * This does not use MultiProcessDataStore because only incallui reads and writes the latch. The
 * androidx DataStore implementation already keeps an in-memory cache behind data reads, so this
 * class does not maintain a second loaded flag or key set.
 */
class DataStoreCallRecordingSessionStore(context: Context) : CallRecordingSessionStore {
  private val context = context.applicationContext ?: context

  override suspend fun markAutomaticRecordingHandled(call: CallSnapshot) {
    val key = keyFor(call)
    if (key == null) {
      LogUtil.i("$TAG.markAutomaticRecordingHandled", "skipping call without stable identity")
      return
    }
    val changed = updateKeys { keys -> keys.add(key) } ?: return
    LogUtil.i(
        "$TAG.markAutomaticRecordingHandled",
        "processed automatic recording handled mark, changed=%b, keyHash=%s",
        changed,
        keyHash(key))
  }

  override suspend fun clearAutomaticRecordingHandled(call: CallSnapshot) {
    val key = keyFor(call)
    if (key == null) {
      LogUtil.i("$TAG.clearAutomaticRecordingHandled", "skipping call without stable identity")
      return
    }
    val changed = updateKeys { keys -> keys.remove(key) } ?: return
    LogUtil.i(
        "$TAG.clearAutomaticRecordingHandled",
        "processed automatic recording handled clear, changed=%b, keyHash=%s",
        changed,
        keyHash(key))
  }

  override suspend fun isAutomaticRecordingHandled(call: CallSnapshot): Boolean {
    val key = keyFor(call)
    if (key == null) {
      LogUtil.i("$TAG.isAutomaticRecordingHandled", "call has no stable identity")
      return false
    }
    if (!isCredentialEncryptedStorageAvailable()) {
      LogUtil.i(
          "$TAG.isAutomaticRecordingHandled",
          "treating automatic recording as handled because user is locked")
      return true
    }
    val handled = runDataStore {
      dataStore(context).data.first().automaticRecordingHandledCallKeysList.contains(key)
    }
    LogUtil.i(
        "$TAG.isAutomaticRecordingHandled",
        "automatic recording handled=%b, keyHash=%s",
        handled,
        keyHash(key))
    return handled
  }

  override suspend fun retainCalls(calls: Collection<CallSnapshot>) {
    if (calls.isEmpty()) {
      LogUtil.i("$TAG.retainCalls", "clearing session state because there are no live calls")
      clear()
      return
    }
    val liveCallKeys = calls.map { call -> keyFor(call) }
    if (liveCallKeys.any { key -> key == null }) {
      // A live call snapshot can arrive after process restart before it has the session identity
      // used by this store. Keep latches until all live calls are keyed or the call session ends.
      LogUtil.i(
          "$TAG.retainCalls",
          "keeping session state until live calls have stable identities, callCount=%d",
          calls.size)
      return
    }
    val currentKeys = liveCallKeys.filterNotNull().toSet()
    val changed = updateKeys { keys -> keys.retainAll(currentKeys) } ?: return
    LogUtil.i(
        "$TAG.retainCalls",
        "retained automatic recording session state, changed=%b, stableCallCount=%d",
        changed,
        currentKeys.size)
  }

  override suspend fun clear() {
    val changed = updateState(CallRecordingSessionState.getDefaultInstance()) ?: return
    LogUtil.i("$TAG.clear", "cleared automatic recording session state, changed=%b", changed)
  }

  private suspend fun updateKeys(update: (MutableSet<String>) -> Unit): Boolean? {
    if (!isCredentialEncryptedStorageAvailable()) {
      LogUtil.i("$TAG.updateKeys", "skipping session update because user is locked")
      return null
    }
    var changed = false
    runDataStore {
      dataStore(context).updateData { state ->
        val oldKeys = state.automaticRecordingHandledCallKeysList.toSet()
        val keys = oldKeys.toMutableSet()
        update(keys)
        changed = keys != oldKeys
        if (changed) sessionState(keys) else state
      }
    }
    return changed
  }

  private suspend fun updateState(newState: CallRecordingSessionState): Boolean? {
    if (!isCredentialEncryptedStorageAvailable()) {
      LogUtil.i("$TAG.updateState", "skipping session update because user is locked")
      return null
    }
    var changed = false
    runDataStore {
      dataStore(context).updateData { state ->
        changed = state != newState
        if (changed) newState else state
      }
    }
    return changed
  }

  private suspend fun <T> runDataStore(operation: suspend () -> T): T {
    try {
      return operation()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      LogUtil.e(TAG, "failed to access call recording session state", e)
      throw e
    }
  }

  private fun isCredentialEncryptedStorageAvailable(): Boolean =
      UserManagerCompat.isUserUnlocked(context)

  companion object {
    private const val TAG = "CallRecordingSessionStore"
    private const val DATASTORE_FILE_NAME = "call_recording_session.pb"
    private const val KEY_VERSION = "v1"
    private val DATASTORE_LOCK = Any()
    private var dataStore: DataStore<CallRecordingSessionState>? = null
    private var dataStoreScope: CoroutineScope? = null

    private fun keyFor(call: CallSnapshot): String? {
      if (!hasStableAutomaticRecordingSessionIdentity(call)) {
        return null
      }
      // DialerCall.getCreationTimeMillis delegates to Telecom Details#getCreationTimeMillis, which
      // Dialer documents as CallLog.Calls.DATE. Do not include DialerCall id, number, account,
      // presentation, or direction here; those are reconstructed Dialer object details and can
      // differ when the same live Telecom call is reported after process death.
      return "$KEY_VERSION:${call.creationTimeMillis}"
    }

    private fun keyHash(key: String): String = Integer.toHexString(key.hashCode())

    private fun sessionState(keys: Set<String>): CallRecordingSessionState =
        CallRecordingSessionState.newBuilder()
            .addAllAutomaticRecordingHandledCallKeys(keys.sorted())
            .build()

    private fun dataStore(context: Context): DataStore<CallRecordingSessionState> {
      synchronized(DATASTORE_LOCK) {
        dataStore?.let {
          return it
        }
        val appContext = context.applicationContext ?: context
        return DataStoreFactory.create(
                serializer = CallRecordingSessionStateSerializer,
                corruptionHandler =
                    ReplaceFileCorruptionHandler { exception ->
                      LogUtil.e(TAG, "replacing corrupted call recording session state", exception)
                      CallRecordingSessionState.getDefaultInstance()
                    },
                scope = dataStoreScope(appContext),
                produceFile = { appContext.dataStoreFile(DATASTORE_FILE_NAME) })
            .also { dataStore = it }
      }
    }

    private fun dataStoreScope(context: Context): CoroutineScope {
      synchronized(DATASTORE_LOCK) {
        dataStoreScope?.let {
          return it
        }
        val dispatcher =
            DialerExecutorComponent.get(context).lowPriorityThreadPool().asCoroutineDispatcher()
        return CoroutineScope(dispatcher + SupervisorJob()).also { dataStoreScope = it }
      }
    }
  }
}

private object CallRecordingSessionStateSerializer : Serializer<CallRecordingSessionState> {
  override val defaultValue: CallRecordingSessionState =
      CallRecordingSessionState.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): CallRecordingSessionState {
    try {
      return CallRecordingSessionState.parseFrom(input, ExtensionRegistryLite.getEmptyRegistry())
    } catch (e: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read call recording session state.", e)
    }
  }

  override suspend fun writeTo(t: CallRecordingSessionState, output: OutputStream) {
    t.writeTo(output)
  }
}
