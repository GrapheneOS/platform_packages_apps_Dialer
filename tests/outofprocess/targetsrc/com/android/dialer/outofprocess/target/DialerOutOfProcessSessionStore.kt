package com.android.dialer.outofprocess.target

import android.content.Context
import com.android.incallui.call.DataStoreCallRecordingSessionStore
import kotlinx.coroutines.runBlocking

/** Test-only blocking bridge for Java target command receivers. */
object DialerOutOfProcessSessionStore {
  @JvmStatic
  fun clearCallRecordingSessionState(context: Context) {
    try {
      runBlocking {
        DataStoreCallRecordingSessionStore(context).clear()
      }
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw IllegalStateException("Interrupted while clearing call recording session state", e)
    }
  }
}
