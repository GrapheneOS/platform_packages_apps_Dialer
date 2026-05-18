package com.android.incallui.call

import android.content.Context
import android.support.annotation.MainThread
import android.support.annotation.VisibleForTesting
import android.text.TextUtils
import com.android.dialer.callrecord.CallRecording
import com.android.dialer.common.Assert
import com.android.dialer.common.LogUtil
import com.android.incallui.call.state.DialerCallState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Coordinates manual and automatic call recording policy for the recorder service client.
 *
 * Automatic decisions are per-call jobs. The coordinator does not await a decision value; it keeps
 * each Job as the cancellation handle and identity token so stale completions cannot overwrite newer
 * call state. Cleanup after cancellation is dispatched back to the coordinator dispatcher before it
 * mutates call state.
 *
 * The recorder service process can restart while this coordinator and its in-memory call policy
 * state remain alive. Automatic recording that was armed by policy is therefore a distinct
 * completed state: it may re-enter the decision path after recorder reconnect, while user disabled
 * and conference/manual decisions remain terminal.
 */
class CallRecordingCoordinator(
    context: Context,
    private val recorder: CallRecorder,
    dependencies: CallRecordingDependencies,
) {
  private val context: Context = context.applicationContext ?: context
  private val currentCalls = dependencies.currentCalls
  private val preferenceSource = dependencies.preferenceSource
  private val sessionStore = dependencies.sessionStore
  private val system = dependencies.system
  private val uiDispatcher: CoroutineDispatcher = dependencies.uiDispatcher
  private val backgroundDispatcher: CoroutineDispatcher = dependencies.backgroundDispatcher
  // incallui is still mostly Java/callback based. Keep coroutines internal and run them on
  // Dialer's app executors so decisions follow the same threading policy as the surrounding code.
  private val scope = CoroutineScope(SupervisorJob() + uiDispatcher)
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
          dependencies.system,
          ::startOrArmManualRecording)
  private val callStates = mutableMapOf<String, DecisionState>()
  private var sessionState = SessionState()

  @MainThread
  fun onCallListChange(callList: CallList) {
    Assert.isMainThread()
    if (!RecordingRules.hasOngoingCall(callList)) {
      // After Dialer starts during an existing call, CallList can publish an empty snapshot before
      // Telecom redelivers the live call. Only clear per-call session state after this coordinator
      // has observed an ongoing call.
      if (sessionState.hasObservedOngoingCall) {
        clearEndedCallSession()
      }
      return
    }
    sessionState = sessionState.withObservedOngoingCall()
    // Prune latches for ended calls; automatic decisions check their own call key before arming.
    val liveCalls = callList.allCalls.mapNotNull { it.toCallSnapshot() }
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      writeSessionState("retain live calls") {
        sessionStore.retainCalls(liveCalls)
      }
    }
    val requiresManualStart = RecordingRules.requiresManualRecordingStart(callList)
    val conferenceCallIds = if (requiresManualStart) conferenceCallIds(callList) else emptySet()
    // When a call becomes a conference, recording stays off until the user presses record again.
    // Later snapshots of the same conference should not stop that manual recording, but a changed
    // participant set needs another record press.
    val conferenceParticipantsChanged =
        requiresManualStart &&
            sessionState.manualStartRequired &&
            sessionState.conferenceCallIds.isNotEmpty() &&
            sessionState.conferenceCallIds != conferenceCallIds
    if (requiresManualStart &&
        (!sessionState.manualStartRequired || conferenceParticipantsChanged)) {
      recorder.clearArmedRecording()
      if (recorder.isRecording) {
        LogUtil.i("$TAG.onCallListChange", "Stopping recording for conference call")
        recorder.finishRecording()
      }
    } else if (!requiresManualStart) {
      val active = recorder.activeRecording
      if (active != null && isRecordedCallOnHold(callList, active)) {
        recorder.finishRecording()
      }
    }
    sessionState = sessionState.withManualStartRequired(requiresManualStart, conferenceCallIds)
    if (requiresManualStart) {
      // New call participants require an explicit record press before another recording starts.
      completeCurrentAutomaticDecisions(callList)
      return
    }
    maybeEvaluate(callList.pendingOutgoingCall.toCallSnapshot())
    maybeEvaluate(callList.outgoingCall.toCallSnapshot())
    maybeEvaluate(callList.activeCall.toCallSnapshot())
  }

  @MainThread
  fun onRecorderServiceConnected() {
    Assert.isMainThread()
    if (sessionState.manualStartRequired || currentCalls.requiresManualRecordingStart()) {
      completeCurrentAutomaticDecisions()
      return
    }
    val activeCall = currentCalls.getActiveCall()
    if (activeCall != null
        && !recorder.isRecording
        && !recorder.isRecordingArmed(activeCall.id)) {
      // If the automatic arm is still pending, CallRecordingController will consume it after this
      // callback. Reopening policy here would create a duplicate decision before recording starts.
      // Only completed automatic recording decisions are reopened here; user disabled and
      // conference decisions are terminal.
      allowRestartableAutomaticRecordingAfterRecorderReconnect(activeCall)
    }
    maybeEvaluate(activeCall)
  }

  @MainThread
  fun startManualRecording(request: ManualRecordingRequest) {
    Assert.isMainThread()
    manualRecordingFlow.start(request)
  }

  @MainThread
  fun cancelManualRecordingStart() {
    Assert.isMainThread()
    manualRecordingFlow.cancel()
  }

  @VisibleForTesting
  fun isManualStartPending(): Boolean = manualRecordingFlow.isPending()

  @MainThread
  fun onManualRecordingPermissionsResult(allGranted: Boolean) {
    Assert.isMainThread()
    manualRecordingFlow.onPermissionsResult(allGranted)
  }

  @MainThread
  fun stopRecordingFromUi(call: DialerCall?) {
    Assert.isMainThread()
    cancelManualRecordingStart()
    sessionState = sessionState.userStoppedRecording()
    call?.id?.let { callId ->
      setCallRecordingDisabledByUser(callId, true /* disabled */, call.toCallSnapshot())
      recorder.disarmRecording(callId)
    }
    if (recorder.isRecording) {
      recorder.finishRecording()
    }
  }

  @MainThread
  fun destroy() {
    Assert.isMainThread()
    clearInMemoryState()
    scope.cancel()
  }

  private fun noteManualRecordingStartRequested() {
    // If the call list is already a conference, this press satisfies the explicit restart rule.
    if (currentCalls.requiresManualRecordingStart()) {
      sessionState = sessionState.withManualStartRequired(true)
    }
  }

  @MainThread
  fun setIncomingCallRecordingEnabled(callId: String?, enabled: Boolean) {
    Assert.isMainThread()
    if (callId.isNullOrEmpty()) {
      return
    }
    if (enabled) {
      sessionState = sessionState.allowAutomaticRecording()
    }
    cancelDecision(callId)
    callStates[callId] = DecisionState.Chosen(
        if (enabled) RecordingChoice.ENABLED else RecordingChoice.DISABLED)
    val call = currentCalls.getCallById(callId)
    if (call == null) {
      return
    }
    if (enabled) {
      scope.launch(start = CoroutineStart.UNDISPATCHED) {
        if (
            writeSessionState("clear incoming recording choice") {
              sessionStore.clearAutomaticRecordingHandled(call)
            }
        ) {
          maybeEvaluate(currentCalls.getCallById(callId))
        }
      }
    } else {
      scope.launch(start = CoroutineStart.UNDISPATCHED) {
        writeSessionState("mark incoming recording choice") {
          sessionStore.markAutomaticRecordingHandled(call)
        }
      }
    }
  }

  @MainThread
  fun setCallRecordingDisabledByUser(callId: String?, disabled: Boolean) {
    setCallRecordingDisabledByUser(callId, disabled, null /* fallbackCall */)
  }

  private fun setCallRecordingDisabledByUser(
      callId: String?,
      disabled: Boolean,
      fallbackCall: CallSnapshot?,
  ) {
    Assert.isMainThread()
    if (callId.isNullOrEmpty()) {
      return
    }
    sessionState =
        if (disabled) {
          sessionState.userStoppedRecording()
        } else {
          sessionState.allowAutomaticRecording()
        }
    cancelDecision(callId)
    (currentCalls.getCallById(callId) ?: fallbackCall)?.let { call ->
      if (disabled) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
          writeSessionState("mark stopped recording choice") {
            sessionStore.markAutomaticRecordingHandled(call)
          }
        }
      } else {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
          writeSessionState("clear stopped recording choice") {
            sessionStore.clearAutomaticRecordingHandled(call)
          }
        }
      }
    }
    if (disabled) {
      callStates[callId] = DecisionState.Chosen(RecordingChoice.DISABLED)
    } else {
      callStates.remove(callId)
    }
  }

  @MainThread
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
    if (!currentCalls.hasOngoingCall()) {
      clearEndedCallSession()
    }
  }

  private fun startOrArmManualRecording(call: DialerCall) {
    setCallRecordingDisabledByUser(call.id, false /* disabled */, call.toCallSnapshot())
    noteManualRecordingStartRequested()
    // A call can become active before unlock. Pressing record after unlock should retry binding.
    recorder.bindIfNeeded()
    if (!recorder.startOrArmManualRecording(call)) {
      LogUtil.i("$TAG.start", "ignoring record request for callState: %d", call.state)
    }
  }

  private fun conferenceCallIds(callList: CallList): Set<String> {
    return callList.allCalls
        .filter(RecordingRules::isConferenceCall)
        .mapNotNull { it.id }
        .filter(String::isNotEmpty)
        .toSet()
  }

  private fun maybeEvaluate(call: CallSnapshot?) {
    val recordableCall = call?.takeIf(::isRecordableCall) ?: return
    if (!hasStableAutomaticRecordingSessionIdentity(recordableCall)) {
      return
    }
    if (!system.isUserUnlocked()) {
      return
    }
    val callId = recordableCall.id
    val state = callStates[callId]
    val choice = state.recordingChoice()
    if (state.isComplete()
        || state is DecisionState.Evaluating
        || !allowsAutomaticDecision(choice)) {
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
              withContext(NonCancellable + uiDispatcher) {
                finishDecisionIfCurrent(callId, currentJob)
              }
            }
          }
        }
    callStates[callId] = DecisionState.Evaluating(job, choice)
    job.start()
  }

  private suspend fun decideCall(callId: String) {
    var completed = false
    var completedState: DecisionState = DecisionState.Done
    try {
      completedState = runAutomaticDecision(callId)
      completed = true
    } finally {
      if (completed) {
        markCompleted(callId, completedState)
      }
    }
  }

  private suspend fun runAutomaticDecision(callId: String): DecisionState {
    val preferences =
        preferenceSource.loadPreferencesOrNull(callId, "automatic recording preferences", TAG)
    if (preferences == null) {
      return DecisionState.Done
    }

    val call =
        autoRecordingDecider.currentRecordableCall(callId)
            ?: return DecisionState.Done
    if (isAutomaticRecordingHandled(call)) {
      return DecisionState.Done
    }
    val userChoice = callStates[callId].recordingChoice()
    if (!allowsAutomaticDecision(userChoice)) {
      return DecisionState.Done
    }
    val shouldRecord = autoRecordingDecider.shouldRecord(callId, call, preferences, userChoice)

    // Contact lookup can finish after the call changes or after the user stops recording.
    // Re-read the live call, persisted session choice, and user choice so a stale lookup result
    // cannot start recording.
    coroutineContext.ensureActive()
    val currentCall =
        autoRecordingDecider.currentRecordableCall(callId)
            ?: return DecisionState.Done
    if (isAutomaticRecordingHandled(currentCall)) {
      return DecisionState.Done
    }
    val latestChoice = callStates[callId].recordingChoice()
    if (!allowsAutomaticDecision(latestChoice)) {
      return DecisionState.Done
    }
    if (shouldRecord) {
      return armRecording(currentCall)
    }
    return DecisionState.Done
  }

  private fun armRecording(call: CallSnapshot): DecisionState {
    val callId = call.id
    if (!recorder.isRecording) {
      recorder.armRecording(callId, true /* startedAutomatically */)
      // The async decision can finish after the latest call list update, so try to start now too.
      recorder.maybeStartArmedRecording(call.dialerCall)
      return DecisionState.AutomaticRecordingArmed
    }
    return DecisionState.Done
  }

  private suspend fun isAutomaticRecordingHandled(call: CallSnapshot): Boolean =
      withContext(backgroundDispatcher) {
        sessionStore.isAutomaticRecordingHandled(call)
      }

  private suspend fun writeSessionState(
      operation: String,
      write: suspend () -> Unit
  ): Boolean {
    // Main-thread call events update callStates first. Durable session writes run on the background
    // executor; paths that depend on the persisted value resume only after this returns true.
    return try {
      withContext(NonCancellable + backgroundDispatcher) {
        write()
      }
      coroutineContext.ensureActive()
      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      LogUtil.e(TAG, "failed to $operation", e)
      false
    }
  }

  private fun markCompleted(callId: String, state: DecisionState) {
    callStates[callId] = state
  }

  private fun allowRestartableAutomaticRecordingAfterRecorderReconnect(call: CallSnapshot) {
    val state = callStates[call.id]
    if (state is DecisionState.AutomaticRecordingArmed) {
      LogUtil.i(
          "$TAG.onRecorderServiceConnected",
          "re-evaluating automatic recording after recorder reconnect")
      callStates.remove(call.id)
    }
  }

  private fun clearInMemoryState() {
    cancelManualRecordingStart()
    callStates.values.forEach { it.cancelDecision() }
    callStates.clear()
    sessionState = SessionState()
    recorder.clearArmedRecording()
  }

  private fun clearEndedCallSession() {
    clearInMemoryState()
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      writeSessionState("clear ended call session") {
        sessionStore.clear()
      }
    }
  }

  private fun completeCurrentAutomaticDecisions(callList: CallList? = null) {
    // Do not silently restart automatic recording if this call list later becomes recordable.
    callList?.allCalls?.forEach { call ->
      val callId = call.id
      if (!callId.isNullOrEmpty()) {
        call.toCallSnapshot()?.let { snapshot ->
          scope.launch(start = CoroutineStart.UNDISPATCHED) {
            writeSessionState("mark conference manual restart") {
              sessionStore.markAutomaticRecordingHandled(snapshot)
            }
          }
        }
        cancelAndMarkCompleted(callId)
      }
    }
    callStates.keys.toList().forEach(::cancelAndMarkCompleted)
    recorder.clearAutomaticArmedRecording()
  }

  private fun cancelAndMarkCompleted(callId: String) {
    cancelDecision(callId)
    callStates[callId] = DecisionState.Done
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

  private fun allowsAutomaticDecision(choice: RecordingChoice?): Boolean {
    return sessionState.allowsAutomaticDecision(choice)
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

    open fun isComplete(): Boolean = false

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

    object Done : DecisionState() {
      override fun isComplete(): Boolean = true
    }

    // Completed automatic decision that armed recording. Keep this distinct from Done so recorder
    // service reconnect can restart active automatic recording without reopening completed
    // decisions that intentionally left recording off.
    object AutomaticRecordingArmed : DecisionState() {
      override fun isComplete(): Boolean = true
    }
  }

  private fun DecisionState?.recordingChoice(): RecordingChoice? = this?.recordingChoice()

  private fun DecisionState?.isComplete(): Boolean = this?.isComplete() ?: false

  private data class SessionState(
      val automaticPolicy: AutomaticPolicy = AutomaticPolicy.ALLOW_AUTOMATIC_RECORDING,
      val manualStartRequired: Boolean = false,
      val conferenceCallIds: Set<String> = emptySet(),
      val hasObservedOngoingCall: Boolean = false,
  ) {
    fun allowsAutomaticDecision(choice: RecordingChoice?): Boolean {
      return choice != RecordingChoice.DISABLED && automaticPolicy.allowsAutomaticRecording(choice)
    }

    fun allowAutomaticRecording(): SessionState =
        copy(automaticPolicy = AutomaticPolicy.ALLOW_AUTOMATIC_RECORDING)

    fun userStoppedRecording(): SessionState =
        copy(automaticPolicy = AutomaticPolicy.USER_STOPPED_RECORDING)

    fun withObservedOngoingCall(): SessionState = copy(hasObservedOngoingCall = true)

    fun withManualStartRequired(
        required: Boolean,
        conferenceCallIds: Set<String> = this.conferenceCallIds,
    ): SessionState =
        copy(
            manualStartRequired = required,
            conferenceCallIds = if (required) conferenceCallIds else emptySet())
  }

  private enum class AutomaticPolicy {
    ALLOW_AUTOMATIC_RECORDING,
    USER_STOPPED_RECORDING;

    fun allowsAutomaticRecording(choice: RecordingChoice?): Boolean =
        this == ALLOW_AUTOMATIC_RECORDING || choice == RecordingChoice.ENABLED
  }

  companion object {
    private const val TAG = "CallRecordingCoordinator"
  }
}
