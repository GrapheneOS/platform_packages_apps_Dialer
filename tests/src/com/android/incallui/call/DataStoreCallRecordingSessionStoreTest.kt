package com.android.incallui.call

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.incallui.call.state.DialerCallState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers DataStore-backed session key behavior because process recreation depends on persisted
 * state.
 */
@RunWith(AndroidJUnit4::class)
class DataStoreCallRecordingSessionStoreTest {
  private lateinit var store: DataStoreCallRecordingSessionStore

  @Before
  fun setUp() = runTest {
    store = DataStoreCallRecordingSessionStore(targetContext())
    store.clear()
  }

  @After fun tearDown() = runTest { store.clear() }

  @Test
  fun automaticRecordingChoiceSurvivesDialerCallRecreation() = runTest {
    store.markAutomaticRecordingHandled(call("call-before-restart", 1234L, "+12025550100"))

    val recreatedStore = DataStoreCallRecordingSessionStore(targetContext())

    assertThat(
            recreatedStore.isAutomaticRecordingHandled(
                call("call-after-restart", 1234L, null)))
        .isTrue()
  }

  @Test
  fun oldSessionIsClearedWhenFreshCallStarts() = runTest {
    val previousCall = call("previous-call", 1234L)
    store.markAutomaticRecordingHandled(previousCall)

    store.retainCalls(listOf(call("fresh-call", 5678L)))

    assertThat(store.isAutomaticRecordingHandled(previousCall)).isFalse()
  }

  @Test
  fun sessionIsKeptUntilAllLiveCallsHaveStableIdentities() = runTest {
    val previousCall = call("previous-call", 1234L)
    store.markAutomaticRecordingHandled(previousCall)

    store.retainCalls(
        listOf(
            call("same-live-call-before-details", 0L),
            call("already-keyed-live-call", 5678L),
        ))

    assertThat(store.isAutomaticRecordingHandled(previousCall)).isTrue()
  }

  @Test
  fun clearRemovesHandledAutomaticRecording() = runTest {
    val call = call("call-1", 1234L)
    store.markAutomaticRecordingHandled(call)

    store.clear()

    assertThat(store.isAutomaticRecordingHandled(call)).isFalse()
  }

  private fun call(
      callId: String,
      creationTimeMillis: Long,
      number: String? = "+12025550100",
  ): CallSnapshot =
      CallSnapshot(
          id = callId,
          number = number,
          state = DialerCallState.ACTIVE,
          isVideoCall = false,
          isConferenceCall = false,
          dialerCall = null,
          creationTimeMillis = creationTimeMillis,
      )

  private fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext
}
