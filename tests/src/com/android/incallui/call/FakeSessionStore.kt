package com.android.incallui.call

/**
 * In-memory session store for coordinator policy tests.
 *
 * Implements the session store contract the coordinator depends on: calls without stable identities
 * do not match stored latches, and retainCalls only drops latches once keyed live calls are
 * available. DataStore persistence is covered by DataStoreCallRecordingSessionStoreTest.
 */
class FakeSessionStore : CallRecordingSessionStore {
  private val handledCalls = mutableSetOf<String>()

  @JvmField var clearCount = 0

  override suspend fun markAutomaticRecordingHandled(call: CallSnapshot) {
    markAutomaticRecordingHandledForTesting(call)
  }

  fun markAutomaticRecordingHandledForTesting(call: CallSnapshot) {
    keyFor(call)?.let { handledCalls.add(it) }
  }

  override suspend fun clearAutomaticRecordingHandled(call: CallSnapshot) {
    clearAutomaticRecordingHandledForTesting(call)
  }

  fun clearAutomaticRecordingHandledForTesting(call: CallSnapshot) {
    keyFor(call)?.let { handledCalls.remove(it) }
  }

  override suspend fun isAutomaticRecordingHandled(call: CallSnapshot): Boolean =
      isAutomaticRecordingHandledForTesting(call)

  fun isAutomaticRecordingHandledForTesting(call: CallSnapshot): Boolean =
      keyFor(call)?.let { handledCalls.contains(it) } ?: false

  override suspend fun retainCalls(calls: Collection<CallSnapshot>) {
    if (calls.isEmpty()) {
      clear()
      return
    }
    val liveCallKeys = calls.map(::keyFor)
    if (liveCallKeys.any { key -> key == null }) {
      return
    }
    val keys = liveCallKeys.filterNotNull().toSet()
    handledCalls.retainAll(keys)
  }

  override suspend fun clear() {
    handledCalls.clear()
    clearCount++
  }

  private fun keyFor(call: CallSnapshot): String? {
    // Telecom creation time is the stable per-call identity used by the session store contract.
    if (call.creationTimeMillis <= 0L) {
      return null
    }
    return call.creationTimeMillis.toString()
  }
}
