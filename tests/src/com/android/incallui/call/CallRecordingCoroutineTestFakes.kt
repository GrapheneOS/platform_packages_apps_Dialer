package com.android.incallui.call

import com.android.dialer.callrecord.CallRecordingPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred

class TestPreferenceSource(private val preferences: CallRecordingPreferences) : PreferenceSource {
  override suspend fun load(): CallRecordingPreferences = preferences
}

class BlockingTestPreferenceSource : PreferenceSource {
  private val started = CountDownLatch(1)
  private val result = CompletableDeferred<CallRecordingPreferences>()

  override suspend fun load(): CallRecordingPreferences {
    started.countDown()
    return result.await()
  }

  fun complete(preferences: CallRecordingPreferences) {
    result.complete(preferences)
  }

  fun fail(throwable: Throwable) {
    result.completeExceptionally(throwable)
  }

  fun awaitStarted(): Boolean = started.await(5, TimeUnit.SECONDS)
}

class TestContactLookup(private val contactInfo: ContactInfo?) : ContactLookup {
  override suspend fun findInfo(call: CallSnapshot): ContactInfo? = contactInfo
}

class FailingTestContactLookup(private val throwable: Throwable) : ContactLookup {
  override suspend fun findInfo(call: CallSnapshot): ContactInfo? {
    throw throwable
  }
}

class BlockingTestContactLookup : ContactLookup {
  private val started = CountDownLatch(1)
  private val result = CompletableDeferred<ContactInfo?>()

  override suspend fun findInfo(call: CallSnapshot): ContactInfo? {
    started.countDown()
    return result.await()
  }

  fun complete(contactInfo: ContactInfo?) {
    result.complete(contactInfo)
  }

  fun fail(throwable: Throwable) {
    result.completeExceptionally(throwable)
  }

  fun awaitStarted(): Boolean = started.await(5, TimeUnit.SECONDS)
}
