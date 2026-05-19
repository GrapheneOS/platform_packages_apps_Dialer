package com.android.dialer.integration

import android.Manifest
import android.app.KeyguardManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.android.dialer.R
import com.android.dialer.notification.NotificationChannelId
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Integration coverage for recorder failures after recording has already started. */
@RunWith(AndroidJUnit4::class)
internal class AutoCallRecordingRecorderErrorIntegrationTest :
    AutoCallRecordingIntegrationTestBase() {

  private lateinit var device: UiDevice

  @Before
  fun setUpUiDevice() {
    device = UiDevice.getInstance(instrumentation)
    device.wakeUp()
    val keyguardManager = targetContext.getSystemService(KeyguardManager::class.java)
    assumeTrue(keyguardManager == null || !keyguardManager.isKeyguardLocked)
  }

  @Test
  fun recordingErrorWarnsUserAndStopsAutomaticRecording() {
    assumeTrue(Build.IS_USERDEBUG || Build.IS_ENG)
    assumeTrue(isUserUnlocked())
    grantTargetPermission(Manifest.permission.RECORD_AUDIO)
    grantTargetPermission(Manifest.permission.POST_NOTIFICATIONS)
    grantTargetPermission(Manifest.permission.READ_CONTACTS)
    assumeTrue(numberIsNotInContacts(TEST_NUMBER))
    seedAutomaticRecordingPreferences()
    cleanupRecordingsCreatedByTest()
    val recordingCountBeforeTest = generatedRecordingCount(TEST_NUMBER)

    prepareNextRecordingFailureForTesting()
    try {
      addIncomingCall(TEST_NUMBER)
      answerIncomingCall()
      showInCallScreen()
      waitForRecordingToStart()

      reportActiveRecordingFailureForTesting()

      waitForCallRecordingErrorMessage()
      waitForCallRecordingErrorNotification()
      waitForRecordingToStop()
      assertRecordingStaysOff()
      waitUntil("partial recording to be removed") {
        generatedRecordingCount(TEST_NUMBER) == recordingCountBeforeTest
      }
    } finally {
      clearRecordingFailureForTesting()
    }
  }

  @Test
  fun finishFailureWarnsUserAndStopsAutomaticRecording() {
    assumeTrue(Build.IS_USERDEBUG || Build.IS_ENG)
    assumeTrue(isUserUnlocked())
    grantTargetPermission(Manifest.permission.RECORD_AUDIO)
    grantTargetPermission(Manifest.permission.POST_NOTIFICATIONS)
    grantTargetPermission(Manifest.permission.READ_CONTACTS)
    assumeTrue(numberIsNotInContacts(TEST_NUMBER))
    seedAutomaticRecordingPreferences()
    cleanupRecordingsCreatedByTest()
    val recordingCountBeforeTest = generatedRecordingCount(TEST_NUMBER)

    prepareNextRecordingFinishFailureForTesting()
    try {
      addIncomingCall(TEST_NUMBER)
      answerIncomingCall()
      waitForRecordingToStart()

      stopRecordingIfNeeded()

      waitForCallRecordingErrorNotification()
      waitForRecordingToStop()
      assertRecordingStaysOff()
      assertThat(generatedRecordingCount(TEST_NUMBER)).isEqualTo(recordingCountBeforeTest)
    } finally {
      clearRecordingFailureForTesting()
    }
  }

  private fun waitForCallRecordingErrorMessage() {
    val message =
        device.wait(
            Until.findObject(
                By.res(targetContext.packageName, "auto_call_recording_message")
                    .text(targetContext.getString(R.string.call_recording_error_message))),
            TIMEOUT_MILLIS)
    assertThat(message).isNotNull()
  }

  private fun waitForCallRecordingErrorNotification() {
    waitForDialerNotificationText(
        targetContext.getString(R.string.call_recording_error_message),
        NotificationChannelId.CALL_RECORDING_ERROR)
  }
}
