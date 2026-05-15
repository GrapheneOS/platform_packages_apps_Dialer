/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.incallui;

import android.content.Context;
import android.os.SystemClock;
import android.support.annotation.FloatRange;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.support.v4.os.UserManagerCompat;
import android.telecom.VideoProfile;
import android.text.TextUtils;
import com.android.dialer.callrecord.CallRecordingPreferences;
import com.android.dialer.callrecord.CallRecordingPreferencesStore;
import com.android.dialer.common.Assert;
import com.android.dialer.common.LogUtil;
import com.android.dialer.common.concurrent.DialerExecutorComponent;
import com.android.dialer.common.concurrent.ThreadUtil;
import com.android.dialer.logging.DialerImpression;
import com.android.dialer.logging.Logger;
import com.android.incallui.ContactInfoCache.ContactCacheEntry;
import com.android.incallui.ContactInfoCache.ContactInfoCacheCallback;
import com.android.incallui.answer.protocol.AnswerScreen;
import com.android.incallui.answer.protocol.AnswerScreenDelegate;
import com.android.incallui.answerproximitysensor.AnswerProximitySensor;
import com.android.incallui.answerproximitysensor.PseudoScreenState;
import com.android.incallui.call.AutoCallRecordingEligibility;
import com.android.incallui.call.AutoCallRecordingEligibility.AutoRecordDecision;
import com.android.incallui.call.CallList;
import com.android.incallui.call.CallRecorder;
import com.android.incallui.call.DialerCall;
import com.android.incallui.call.DialerCallListener;
import com.android.incallui.incalluilock.InCallUiLock;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/** Manages changes for an incoming call screen. */
public class AnswerScreenPresenter
    implements AnswerScreenDelegate,
        DialerCall.CannedTextResponsesLoadedListener,
        ContactInfoCacheCallback {
  private static final int ACCEPT_REJECT_CALL_TIME_OUT_IN_MILLIS = 5000;

  @NonNull private final Context context;
  @NonNull private final AnswerScreen answerScreen;
  @NonNull private final DialerCall call;
  @NonNull private final IncomingCallRecordingChoiceUpdater callRecordingChoiceUpdater;
  private long actionPerformedTimeMillis;
  // Async preference and contact callbacks can outlive the answer screen.
  private boolean presenterAttached = true;
  // The microphone permission notice is intentionally shown at most once for this incoming call.
  private boolean automaticRecordingPermissionMessageShown;
  private CallRecordingSwitchState callRecordingSwitchState =
      CallRecordingSwitchState.AWAITING_SNAPSHOT;
  private CallRecordingPreferences callRecordingPreferences;

  private enum CallRecordingSwitchState {
    AWAITING_SNAPSHOT,
    AWAITING_CONTACT_LOOKUP,
    USER_CHOSE,
    AUTO_CHOSE,
    UNAVAILABLE
  }

  AnswerScreenPresenter(
      @NonNull Context context, @NonNull AnswerScreen answerScreen, @NonNull DialerCall call) {
    this(
        context,
        answerScreen,
        call,
        (callId, enabled) ->
            CallRecorder.getInstance().setIncomingCallRecordingEnabled(callId, enabled));
  }

  @VisibleForTesting
  AnswerScreenPresenter(
      @NonNull Context context,
      @NonNull AnswerScreen answerScreen,
      @NonNull DialerCall call,
      @NonNull IncomingCallRecordingChoiceUpdater callRecordingChoiceUpdater) {
    LogUtil.i("AnswerScreenPresenter.constructor", null);
    this.context = Assert.isNotNull(context);
    this.answerScreen = Assert.isNotNull(answerScreen);
    this.call = Assert.isNotNull(call);
    this.callRecordingChoiceUpdater = Assert.isNotNull(callRecordingChoiceUpdater);
    if (isSmsResponseAllowed(call)) {
      answerScreen.setTextResponses(call.getCannedSmsResponses());
    }
    call.addCannedTextResponsesLoadedListener(this);

    PseudoScreenState pseudoScreenState = InCallPresenter.getInstance().getPseudoScreenState();
    if (AnswerProximitySensor.shouldUse(context, call)) {
      new AnswerProximitySensor(context, call, pseudoScreenState);
    } else {
      pseudoScreenState.setOn(true);
    }
    initializeCallRecordingSwitch();
  }

  @Override
  public boolean isActionTimeout() {
    return actionPerformedTimeMillis != 0
        && SystemClock.elapsedRealtime() - actionPerformedTimeMillis
            >= ACCEPT_REJECT_CALL_TIME_OUT_IN_MILLIS;
  }

  @Override
  public InCallUiLock acquireInCallUiLock(String tag) {
    return InCallPresenter.getInstance().acquireInCallUiLock(tag);
  }

  @Override
  public void onAnswerScreenUnready() {
    presenterAttached = false;
    call.removeCannedTextResponsesLoadedListener(this);
  }

  private void initializeCallRecordingSwitch() {
    if (!presenterAttached) {
      return;
    }
    callRecordingSwitchState = CallRecordingSwitchState.AWAITING_SNAPSHOT;
    answerScreen.setCallRecordingSwitchVisible(false);
    answerScreen.setCallRecordingSwitchEnabled(false);
    answerScreen.setCallRecordingPermissionMessage(null);
    CallRecordingPreferencesStore.addLoadCallback(
        CallRecordingPreferencesStore.loadAsync(context),
        DialerExecutorComponent.get(context).uiExecutor(),
        result -> populateCallRecordingSwitch(result),
        t -> {
          LogUtil.e(
              "AnswerScreenPresenter.initializeCallRecordingSwitch",
              "failed to load call recording preferences",
              t);
          if (presenterAttached) {
            callRecordingSwitchState = CallRecordingSwitchState.UNAVAILABLE;
          }
        });
  }

  private void populateCallRecordingSwitch(CallRecordingPreferences preferences) {
    if (!presenterAttached) {
      return;
    }
    callRecordingPreferences = preferences;
    AutoRecordDecision switchDecision =
        AutoCallRecordingEligibility.getDecision(
            context, call, preferences, false /* requireContactsPermission */);
    boolean visible =
        !answerScreen.isVideoUpgradeRequest()
            && switchDecision.canShowIncomingCallRecordingSwitch();
    answerScreen.setCallRecordingSwitchVisible(visible);
    answerScreen.setCallRecordingSwitchEnabled(visible && switchDecision.canRecordIncomingCall());
    answerScreen.setCallRecordingSwitchChecked(false);
    CharSequence permissionMessage =
        visible ? getAutomaticRecordingPermissionMessage(switchDecision) : null;
    if (permissionMessage == null) {
      answerScreen.setCallRecordingPermissionMessage(null);
    } else if (!automaticRecordingPermissionMessageShown) {
      automaticRecordingPermissionMessageShown = true;
      answerScreen.setCallRecordingPermissionMessage(permissionMessage);
    }
    AutoRecordDecision automaticDecision =
        AutoCallRecordingEligibility.getDecision(
            context, call, preferences, true /* requireContactsPermission */);
    if (visible && automaticDecision.shouldCheckAutomaticRecording()) {
      callRecordingSwitchState = CallRecordingSwitchState.AWAITING_CONTACT_LOOKUP;
      ContactInfoCache.getInstance(context).findInfo(call, true /* isIncoming */, this);
    } else {
      callRecordingSwitchState = CallRecordingSwitchState.UNAVAILABLE;
    }
  }

  private CharSequence getAutomaticRecordingPermissionMessage(
      AutoRecordDecision decision) {
    if (!decision.isMicrophonePermissionMissing()) {
      return null;
    }
    return context.getString(R.string.auto_call_recording_mic_permission_message);
  }

  @Override
  public void onRejectCallWithMessage(String message) {
    call.reject(true /* rejectWithMessage */, message);
    addTimeoutCheck();
  }

  @Override
  public void onAnswer(boolean answerVideoAsAudio) {

    DialerCall incomingCall = CallList.getInstance().getIncomingCall();
    InCallActivity inCallActivity =
        (InCallActivity) answerScreen.getAnswerScreenFragment().getActivity();
    ListenableFuture<Void> answerPrecondition;

    if (incomingCall != null && inCallActivity != null) {
      answerPrecondition = inCallActivity.getSpeakEasyCallManager().onNewIncomingCall(incomingCall);
    } else {
      answerPrecondition = Futures.immediateFuture(null);
    }

    Futures.addCallback(
        answerPrecondition,
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void result) {
            onAnswerCallback(answerVideoAsAudio);
          }

          @Override
          public void onFailure(Throwable t) {
            onAnswerCallback(answerVideoAsAudio);
            // TODO(erfanian): Enumerate all error states and specify recovery strategies.
            throw new RuntimeException("Failed to successfully complete pre call tasks.", t);
          }
        },
        DialerExecutorComponent.get(context).uiExecutor());
    addTimeoutCheck();
  }

  private void onAnswerCallback(boolean answerVideoAsAudio) {

    if (answerScreen.isVideoUpgradeRequest()) {
      if (answerVideoAsAudio) {
        Logger.get(context)
            .logCallImpression(
                DialerImpression.Type.VIDEO_CALL_REQUEST_ACCEPTED_AS_AUDIO,
                call.getUniqueCallId(),
                call.getTimeAddedMs());
        call.getVideoTech().acceptVideoRequestAsAudio();
      } else {
        Logger.get(context)
            .logCallImpression(
                DialerImpression.Type.VIDEO_CALL_REQUEST_ACCEPTED,
                call.getUniqueCallId(),
                call.getTimeAddedMs());
        call.getVideoTech().acceptVideoRequest(context);
      }
    } else {
      if (answerVideoAsAudio) {
        call.answer(VideoProfile.STATE_AUDIO_ONLY);
      } else {
        call.answer();
      }
    }
  }

  @Override
  public void onReject() {
    if (answerScreen.isVideoUpgradeRequest()) {
      Logger.get(context)
          .logCallImpression(
              DialerImpression.Type.VIDEO_CALL_REQUEST_DECLINED,
              call.getUniqueCallId(),
              call.getTimeAddedMs());
      call.getVideoTech().declineVideoRequest();
    } else {
      call.reject(false /* rejectWithMessage */, null);
    }
    addTimeoutCheck();
  }

  @Override
  public void onSpeakEasyCall() {
    LogUtil.enterBlock("AnswerScreenPresenter.onSpeakEasyCall");
    DialerCall incomingCall = CallList.getInstance().getIncomingCall();
    if (incomingCall == null) {
      LogUtil.i("AnswerScreenPresenter.onSpeakEasyCall", "incomingCall == null");
      return;
    }
    incomingCall.setIsSpeakEasyCall(true);
  }

  @Override
  public void onCallRecordingSwitchChanged(boolean enabled) {
    // This presenter instance owns one incoming call, so late contact callbacks must not override
    // any recording choice the user already made for that call.
    callRecordingSwitchState = CallRecordingSwitchState.USER_CHOSE;
    callRecordingChoiceUpdater.setIncomingCallRecordingEnabled(call.getId(), enabled);
  }

  @Override
  public void onAnswerAndReleaseCall() {
    LogUtil.enterBlock("AnswerScreenPresenter.onAnswerAndReleaseCall");
    DialerCall activeCall = CallList.getInstance().getActiveCall();
    if (activeCall == null) {
      LogUtil.i("AnswerScreenPresenter.onAnswerAndReleaseCall", "activeCall == null");
      onAnswer(false);
    } else {
      activeCall.setReleasedByAnsweringSecondCall(true);
      activeCall.addListener(new AnswerOnDisconnected(activeCall));
      activeCall.disconnect();
    }
    addTimeoutCheck();
  }

  @Override
  public void onAnswerAndReleaseButtonDisabled() {
    DialerCall activeCall = CallList.getInstance().getActiveCall();
    if (activeCall != null) {
      activeCall.increaseSecondCallWithoutAnswerAndReleasedButtonTimes();
    }
  }

  @Override
  public void onAnswerAndReleaseButtonEnabled() {
    DialerCall activeCall = CallList.getInstance().getActiveCall();
    if (activeCall != null) {
      activeCall.increaseAnswerAndReleaseButtonDisplayedTimes();
    }
  }

  @Override
  public void onCannedTextResponsesLoaded(DialerCall call) {
    if (isSmsResponseAllowed(call)) {
      answerScreen.setTextResponses(call.getCannedSmsResponses());
    }
  }

  @Override
  public void onContactInfoComplete(String callId, ContactCacheEntry entry) {
    if (!presenterAttached
        || callRecordingSwitchState != CallRecordingSwitchState.AWAITING_CONTACT_LOOKUP
        || !TextUtils.equals(call.getId(), callId)) {
      return;
    }
    if (entry != null && entry.hasPendingContactLookup()) {
      return;
    }
    if (callRecordingPreferences == null) {
      return;
    }
    boolean shouldRecord =
        AutoCallRecordingEligibility.shouldAutoRecordCall(
            context, call, entry, callRecordingPreferences);
    callRecordingSwitchState = CallRecordingSwitchState.AUTO_CHOSE;
    answerScreen.setCallRecordingSwitchChecked(shouldRecord);
    callRecordingChoiceUpdater.setIncomingCallRecordingEnabled(call.getId(), shouldRecord);
  }

  @Override
  public void onImageLoadComplete(String callId, ContactCacheEntry entry) {}

  @Override
  public void updateWindowBackgroundColor(@FloatRange(from = -1f, to = 1.0f) float progress) {
    InCallActivity activity = (InCallActivity) answerScreen.getAnswerScreenFragment().getActivity();
    if (activity != null) {
      activity.updateWindowBackgroundColor(progress);
    }
  }

  private class AnswerOnDisconnected implements DialerCallListener {

    private final DialerCall disconnectingCall;

    AnswerOnDisconnected(DialerCall disconnectingCall) {
      this.disconnectingCall = disconnectingCall;
    }

    @Override
    public void onDialerCallDisconnect() {
      LogUtil.i(
          "AnswerScreenPresenter.AnswerOnDisconnected", "call disconnected, answering new call");
      call.answer();
      disconnectingCall.removeListener(this);
    }

    @Override
    public void onDialerCallUpdate() {}

    @Override
    public void onDialerCallChildNumberChange() {}

    @Override
    public void onDialerCallLastForwardedNumberChange() {}

    @Override
    public void onDialerCallUpgradeToVideo() {}

    @Override
    public void onDialerCallSessionModificationStateChange() {}

    @Override
    public void onWiFiToLteHandover() {}

    @Override
    public void onHandoverToWifiFailure() {}

    @Override
    public void onInternationalCallOnWifi() {}

    @Override
    public void onEnrichedCallSessionUpdate() {}
  }

  private boolean isSmsResponseAllowed(DialerCall call) {
    return UserManagerCompat.isUserUnlocked(context)
        && call.can(android.telecom.Call.Details.CAPABILITY_RESPOND_VIA_TEXT);
  }

  private void addTimeoutCheck() {
    actionPerformedTimeMillis = SystemClock.elapsedRealtime();
    if (answerScreen.getAnswerScreenFragment().isVisible()) {
      ThreadUtil.postDelayedOnUiThread(
          () -> {
            if (!answerScreen.getAnswerScreenFragment().isVisible()) {
              LogUtil.d(
                  "AnswerScreenPresenter.addTimeoutCheck",
                  "accept/reject call timed out, do nothing");
              return;
            }
            LogUtil.i("AnswerScreenPresenter.addTimeoutCheck", "accept/reject call timed out");
            // Force re-evaluate which fragment to show.
            InCallPresenter.getInstance().refreshUi();
          },
          ACCEPT_REJECT_CALL_TIME_OUT_IN_MILLIS);
    }
  }

  @VisibleForTesting
  interface IncomingCallRecordingChoiceUpdater {
    void setIncomingCallRecordingEnabled(String callId, boolean enabled);
  }
}
