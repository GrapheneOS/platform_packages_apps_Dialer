package com.android.incallui.call

import android.content.Context
import com.android.dialer.callrecord.CallRecordingPreferencesStore
import com.android.dialer.callrecord.CallRecordingWarningHelper
import com.android.dialer.common.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Runs the manual record button flow while the rest of incallui remains callback based. */
internal class ManualRecordingFlow(
    context: Context,
    private val scope: CoroutineScope,
    private val preferenceSource: PreferenceSource,
    private val system: CallRecordingSystem,
    private val startRecording: (DialerCall) -> Unit,
) {
  private val context = context.applicationContext ?: context
  private var activeSession: ManualRecordingSession? = null

  fun start(request: ManualRecordingRequest) {
    if (activeSession != null) {
      LogUtil.i(TAG, "manual recording start already pending")
      return
    }
    val call = request.callProvider.get()
    val callId = call?.id
    if (callId.isNullOrEmpty()) {
      LogUtil.i(TAG, "ignoring record request without call")
      return
    }
    if (!system.isUserUnlocked()) {
      system.showLockedUserMessage()
      return
    }

    val session = ManualRecordingSession(callId, request)
    activeSession = session
    session.job =
        scope.launch {
          try {
            val preferences =
                preferenceSource.loadPreferencesOrNull(callId, "manual recording start", TAG)
                    ?: return@launch
            if (currentCallOrNull(session) == null) {
              return@launch
            }
            if (!awaitWarningIfNeeded(session, preferences.recordingWarningPresented)) {
              return@launch
            }
            if (!awaitPermissionsIfNeeded(session)) {
              return@launch
            }
            val pendingCall = currentCallOrNull(session) ?: return@launch
            startRecording(pendingCall)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            LogUtil.e(TAG, "manual recording start failed", e)
          } finally {
            if (activeSession === session) {
              activeSession = null
            }
          }
        }
  }

  fun cancel() {
    activeSession?.cancel()
    activeSession = null
  }

  fun isPending(): Boolean = activeSession != null

  fun onPermissionsResult(allGranted: Boolean) {
    activeSession?.permissionResult?.complete(allGranted)
  }

  // TODO: If incallui becomes coroutine aware, replace the CompletableDeferred bridges below
  // with suspend APIs for warning acknowledgement and permission results.
  private suspend fun awaitWarningIfNeeded(
      session: ManualRecordingSession,
      warningPresented: Boolean
  ): Boolean {
    if (warningPresented) {
      return true
    }
    val activity =
        session.request.activityProvider.get()
            ?: run {
              LogUtil.i(TAG, "incallui gone before recording warning check")
              return false
            }
    val result = CompletableDeferred<Boolean>()
    val shown =
        CallRecordingWarningHelper.requestAcknowledgementIfNeeded(
            activity,
            false /* warningPresented */,
            { onSuccess, onFailure ->
              scope.launch {
                try {
                  CallRecordingPreferencesStore.update(context) { builder ->
                    builder.setRecordingWarningPresented(true)
                  }
                } catch (e: CancellationException) {
                  result.cancel(e)
                  throw e
                } catch (e: Exception) {
                  onFailure.onFailure(e)
                  return@launch
                }
                onSuccess.run()
              }
            },
            { result.complete(true) },
            { throwable ->
              LogUtil.e(TAG, "failed to store recording warning state", throwable)
              result.complete(false)
            },
            { result.complete(false) })
    if (!shown) {
      return true
    }
    return result.await() && currentCallOrNull(session) != null
  }

  private suspend fun awaitPermissionsIfNeeded(session: ManualRecordingSession): Boolean {
    if (system.hasAllPermissions(CallRecorder.REQUIRED_PERMISSIONS)) {
      return true
    }
    val inCallButtonUi =
        session.request.buttonUiProvider.get()
            ?: run {
              LogUtil.i(TAG, "incallui gone before recording permission request")
              return false
            }
    val result = CompletableDeferred<Boolean>()
    session.permissionResult = result
    inCallButtonUi.requestCallRecordingPermissions(CallRecorder.REQUIRED_PERMISSIONS)
    return result.await()
  }

  private fun currentCallOrNull(session: ManualRecordingSession): DialerCall? {
    val call = session.request.callProvider.get()
    if (call == null || call.id != session.callId) {
      LogUtil.i(TAG, "manual recording start no longer matches current call")
      return null
    }
    return call
  }

  private class ManualRecordingSession(
      val callId: String,
      val request: ManualRecordingRequest,
  ) {
    var job: Job? = null
    var permissionResult: CompletableDeferred<Boolean>? = null

    fun cancel() {
      permissionResult?.complete(false)
      job?.cancel()
    }
  }

  companion object {
    private const val TAG = "ManualRecordingFlow"
  }
}
