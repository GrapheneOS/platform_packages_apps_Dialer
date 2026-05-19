package com.android.incallui.call

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.dialer.callrecord.CallRecording
import com.android.dialer.callrecord.ICallRecorderService
import com.android.dialer.callrecord.ICallRecorderServiceCallback
import com.android.incallui.call.CallRecordingTestSupport.call
import com.android.incallui.call.state.DialerCallState
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallRecorderServiceErrorTest {

  @Test
  fun recordingErrorNotifiesOnceAndAllowsNewRecording() {
    val service = ErrorReportingRecorderService()
    val recorder = CallRecorder(
        Handler(Looper.getMainLooper()),
        ConnectedServiceBinding(service))
    val errorCallbackCount = AtomicInteger()
    recorder.addRecordingErrorListener { errorCallbackCount.incrementAndGet() }
    recorder.attachContext(InstrumentationRegistry.getInstrumentation().targetContext)

    assertThat(
        recorder.startOrArmManualRecording(
            call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L)))
        .isTrue()

    service.reportRecordingError()
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()

    assertThat(errorCallbackCount.get()).isEqualTo(1)
    assertThat(recorder.isRecording).isFalse()
    assertThat(recorder.isRecordingStopPending).isFalse()

    service.reportRecordingError()
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    assertThat(errorCallbackCount.get()).isEqualTo(1)

    assertThat(
        recorder.startOrArmManualRecording(
            call("call-2", DialerCallState.ACTIVE, "+15557654321", 2345L)))
        .isTrue()
    assertThat(service.startCount).isEqualTo(2)
  }

  @Test
  fun finishFailureNotifiesRecordingErrorAfterStopRequest() {
    val service = ErrorReportingRecorderService(reportErrorOnStop = true)
    val recorder = CallRecorder(
        Handler(Looper.getMainLooper()),
        ConnectedServiceBinding(service))
    val errorCallbackCount = AtomicInteger()
    recorder.addRecordingErrorListener { errorCallbackCount.incrementAndGet() }
    recorder.attachContext(InstrumentationRegistry.getInstrumentation().targetContext)

    assertThat(
        recorder.startOrArmManualRecording(
            call("call-1", DialerCallState.ACTIVE, "+15551234567", 1234L)))
        .isTrue()

    recorder.finishRecording()
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()

    assertThat(errorCallbackCount.get()).isEqualTo(1)
    assertThat(recorder.isRecording).isFalse()
    assertThat(recorder.isRecordingStopPending).isFalse()
  }

  private class ConnectedServiceBinding(
      private val service: ICallRecorderService,
  ) : CallRecorderServiceBinding {
    override fun isBound(): Boolean = true

    override fun getService(): ICallRecorderService = service

    override fun bind(
        context: Context,
        serviceIntent: Intent,
        listener: CallRecorderServiceBinding.Listener,
    ): Boolean = true

    override fun unbind(context: Context) {}
  }

  private class ErrorReportingRecorderService(
      private val reportErrorOnStop: Boolean = false,
  ) : ICallRecorderService.Stub() {
    private var callback: ICallRecorderServiceCallback? = null
    private var activeRecording: CallRecording? = null
    var startCount: Int = 0
      private set

    override fun setCallback(callback: ICallRecorderServiceCallback?) {
      this.callback = callback
    }

    override fun startRecording(phoneNumber: String?, creationTime: Long): Boolean {
      startCount++
      activeRecording =
          CallRecording(
              phoneNumber,
              creationTime,
              if (phoneNumber.isNullOrEmpty()) "unknown.m4a" else "$phoneNumber.m4a",
              System.currentTimeMillis(),
              1L)
      return true
    }

    override fun stopRecording() {
      val recording = activeRecording
      activeRecording = null
      try {
        if (reportErrorOnStop) {
          callback?.onRecordingError()
        } else {
          callback?.onRecordingStopped(recording)
        }
      } catch (e: RemoteException) {
        throw AssertionError(e)
      }
    }

    fun reportRecordingError() {
      activeRecording = null
      val currentCallback = callback ?: throw AssertionError("recorder callback was not registered")
      try {
        currentCallback.onRecordingError()
      } catch (e: RemoteException) {
        throw AssertionError(e)
      }
    }
  }
}
