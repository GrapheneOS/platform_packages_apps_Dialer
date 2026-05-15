package com.android.incallui.call

import android.content.Context
import android.support.annotation.VisibleForTesting
import android.text.TextUtils
import com.android.dialer.callrecord.CallRecording
import com.android.dialer.common.Assert
import com.android.dialer.common.LogUtil
import com.android.incallui.call.state.DialerCallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Coordinates manual and automatic call recording policy for the recorder service client.
 *
 * Automatic decisions are per-call jobs. The coordinator does not await a decision value; it keeps
 * each Job as the cancellation handle and identity token so stale completions cannot overwrite newer
 * call state.
 */
class CallRecordingCoordinator(
    context: Context,
    private val recorder: CallRecorder,
    dependencies: CallRecordingDependencies,
) {
  private val context: Context = context.applicationContext ?: context
  private val currentCalls = dependencies.currentCalls
  private val preferenceSource = dependencies.preferenceSource
  // incallui is still mostly Java/callback based. Keep coroutines internal and run them on
  // Dialer's app executors so decisions follow the same threading policy as the surrounding code.
  private val scope = CoroutineScope(SupervisorJob() + dependencies.uiDispatcher)
  private val autoRecordingDecider =
      AutoRecordingDecider(
          this.context,
          currentCalls,
          dependencies.contactLookup,
          dependencies.eligibilityChecker,
          dependencies.backgroundDispatcher)
  private val manualRecordingFlow =
      ManualRecordingFlow(
          this.context,
          scope,
          preferenceSource,
          dependencies.permissionChecker,
          ::startOrArmManualRecording)
  private val callStates = mutableMapOf<String, DecisionState>()
  // A manual stop applies to the live call session, including held calls reached by swap.
  private var sessionPolicy = SessionPolicy.ALLOW_AUTOMATIC_RECORDING

  fun onCallListChange(callList: CallList) {
    Assert.isMainThread()
    if (!callList.hasLiveCall()) {
      clear()
      return
    }
    val active = recorder.activeRecording
    if (active != null && isRecordedCallOnHold(callList, active)) {
      recorder.finishRecording()
    }
    maybeEvaluate(callList.outgoingCall.toCallSnapshot())
    maybeEvaluate(callList.activeCall.toCallSnapshot())
  }

  fun onRecorderServiceConnected() {
    Assert.isMainThread()
    maybeEvaluate(currentCalls.getActiveCall())
  }

  fun startManualRecording(request: ManualRecordingRequest) {
    Assert.isMainThread()
    manualRecordingFlow.start(request)
  }

  fun cancelManualRecordingStart() {
    manualRecordingFlow.cancel()
  }

  @VisibleForTesting
  fun isManualStartPending(): Boolean = manualRecordingFlow.isPending()

  fun onManualRecordingPermissionsResult(allGranted: Boolean) {
    manualRecordingFlow.onPermissionsResult(allGranted)
  }

  fun stopRecordingFromUi(call: DialerCall?) {
    Assert.isMainThread()
    cancelManualRecordingStart()
    sessionPolicy = SessionPolicy.USER_STOPPED_RECORDING
    call?.getId()?.let { callId ->
      setCallRecordingDisabledByUser(callId, true /* disabled */)
      recorder.disarmRecording(callId)
    }
    if (recorder.isRecording) {
      recorder.finishRecording()
    }
  }

  fun destroy() {
    clear()
    scope.cancel()
  }

  fun canStartArmedRecording(callId: String?, startedAutomatically: Boolean): Boolean {
    return !callId.isNullOrEmpty()
  }

  fun setIncomingCallRecordingEnabled(callId: String?, enabled: Boolean) {
    Assert.isMainThread()
    if (callId.isNullOrEmpty()) {
      return
    }
    if (enabled) {
      sessionPolicy = SessionPolicy.ALLOW_AUTOMATIC_RECORDING
    }
    cancelDecision(callId)
    callStates[callId] = DecisionState.Chosen(
        if (enabled) RecordingChoice.ENABLED else RecordingChoice.DISABLED)
    maybeEvaluate(currentCalls.getCallById(callId))
  }

  fun setCallRecordingDisabledByUser(callId: String?, disabled: Boolean) {
    Assert.isMainThread()
    if (callId.isNullOrEmpty()) {
      return
    }
    sessionPolicy =
        if (disabled) {
          SessionPolicy.USER_STOPPED_RECORDING
        } else {
          SessionPolicy.ALLOW_AUTOMATIC_RECORDING
        }
    cancelDecision(callId)
    if (disabled) {
      callStates[callId] = DecisionState.Chosen(RecordingChoice.DISABLED)
    } else {
      callStates.remove(callId)
    }
  }

  fun onDisconnect(call: DialerCall?) {
    Assert.isMainThread()
    val active = recorder.activeRecording
    val recordedCallDisconnected = active != null && call != null && isRecordedCall(call, active)
    if (active != null &&
        (recordedCallDisconnected || !currentCalls.hasActiveOrBackgroundCall())) {
      recorder.finishRecording()
    }
    call?.id?.let { callId ->
      cancelDecision(callId)
      callStates.remove(callId)
      recorder.disarmRecording(callId)
    }
    if (!currentCalls.hasLiveCall()) {
      clear()
    }
  }

  private fun startOrArmManualRecording(call: DialerCall) {
    setCallRecordingDisabledByUser(call.id, false /* disabled */)
    if (!recorder.startOrArmManualRecording(call)) {
      LogUtil.i("$TAG.start", "ignoring record request for callState: %d", call.state)
    }
  }

  private fun maybeEvaluate(call: CallSnapshot?) {
    val recordableCall = call?.takeIf(::isRecordableCall) ?: return
    val callId = recordableCall.id
    val state = callStates[callId]
    val choice = state.recordingChoice()
    if (!sessionPolicy.allowsAutomaticRecording(choice)) {
      return
    }
    if (state is DecisionState.Done
        || state is DecisionState.Evaluating
        || choice == RecordingChoice.DISABLED) {
      return
    }
    startDecision(callId, choice)
  }

  private fun startDecision(callId: String, choice: RecordingChoice?) {
    // Store the state before starting the job so immediate dispatchers cannot complete it before
    // the coroutine can observe the Evaluating state in its cleanup path.
    val job =
        scope.launch(start = CoroutineStart.LAZY) {
          try {
            decideCall(callId)
          } finally {
            val currentJob = coroutineContext[Job]
            if (currentJob != null) {
              finishDecisionIfCurrent(callId, currentJob)
            }
          }
        }
    callStates[callId] = DecisionState.Evaluating(job, choice)
    job.start()
  }

  private suspend fun decideCall(callId: String) {
    var completed = false
    try {
      runAutomaticDecision(callId)
      completed = true
    } finally {
      if (completed) {
        markCompleted(callId)
      }
    }
  }

  private suspend fun runAutomaticDecision(callId: String) {
    val preferences =
        preferenceSource.loadPreferencesOrNull(callId, "automatic recording preferences", TAG)
    if (preferences == null) {
      return
    }

    val call =
        autoRecordingDecider.currentRecordableCall(callId)
            ?: return
    val userChoice = callStates[callId].recordingChoice()
    val shouldRecord = autoRecordingDecider.shouldRecord(callId, call, preferences, userChoice)

    coroutineContext.ensureActive()
    val currentCall =
        autoRecordingDecider.currentRecordableCall(callId)
            ?: return
    if (callStates[callId].recordingChoice() == RecordingChoice.DISABLED) {
      return
    }
    if (shouldRecord) {
      armRecording(currentCall)
    }
  }

  private fun armRecording(call: CallSnapshot) {
    val callId = call.id
    if (!recorder.isRecording) {
      recorder.armRecording(callId, true /* startedAutomatically */)
    }
  }

  private fun markCompleted(callId: String) {
    callStates[callId] = DecisionState.Done
  }

  private fun clear() {
    cancelManualRecordingStart()
    callStates.values.forEach { it.cancelDecision() }
    callStates.clear()
    sessionPolicy = SessionPolicy.ALLOW_AUTOMATIC_RECORDING
    recorder.clearArmedRecording()
  }

  private fun cancelDecision(callId: String) {
    callStates[callId]?.cancelDecision()
  }

  private fun finishDecisionIfCurrent(callId: String, job: Job) {
    val latestState = callStates[callId]
    if (latestState is DecisionState.Evaluating && latestState.job === job) {
      if (latestState.choice == null) {
        callStates.remove(callId)
      } else {
        callStates[callId] = DecisionState.Chosen(latestState.choice)
      }
    }
  }

  private fun isRecordedCallOnHold(callList: CallList, active: CallRecording): Boolean {
    val callId = recorder.activeRecordingCallId
    if (!callId.isNullOrEmpty()) {
      val call = callList.getCallById(callId)
      if (call != null) {
        return call.state == DialerCallState.ONHOLD
      }
      if (active.phoneNumber.isNullOrEmpty()) {
        return false
      }
    }
    return callList.getCallWithStateAndNumber(DialerCallState.ONHOLD, active.phoneNumber) != null
  }

  private fun isRecordedCall(call: DialerCall, active: CallRecording): Boolean {
    val callId = recorder.activeRecordingCallId
    if (!callId.isNullOrEmpty()) {
      if (TextUtils.equals(call.id, callId)) {
        return true
      }
      if (active.phoneNumber.isNullOrEmpty()) {
        return false
      }
    }
    return TextUtils.equals(call.number, active.phoneNumber)
  }

  private sealed class DecisionState {
    open fun cancelDecision() {}

    open fun recordingChoice(): RecordingChoice? = null

    data class Chosen(val choice: RecordingChoice) : DecisionState() {
      override fun recordingChoice(): RecordingChoice = choice
    }

    data class Evaluating(val job: Job, val choice: RecordingChoice?) : DecisionState() {
      override fun cancelDecision() {
        job.cancel()
      }

      override fun recordingChoice(): RecordingChoice? = choice
    }

    object Done : DecisionState()
  }

  private fun DecisionState?.recordingChoice(): RecordingChoice? = this?.recordingChoice()

  private enum class SessionPolicy {
    ALLOW_AUTOMATIC_RECORDING,
    USER_STOPPED_RECORDING;

    fun allowsAutomaticRecording(choice: RecordingChoice?): Boolean =
        this == ALLOW_AUTOMATIC_RECORDING || choice == RecordingChoice.ENABLED
  }

  companion object {
    private const val TAG = "CallRecordingCoordinator"
  }
}
