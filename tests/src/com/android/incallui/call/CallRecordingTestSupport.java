package com.android.incallui.call;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Looper;
import android.text.TextUtils;
import androidx.test.platform.app.InstrumentationRegistry;
import com.android.incallui.call.state.DialerCallState;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

final class CallRecordingTestSupport {
  private CallRecordingTestSupport() {}

  static DialerCall call(String callId) {
    return call(callId, DialerCallState.ACTIVE, null);
  }

  static DialerCall call(String callId, int state, String number) {
    return call(callId, state, number, 1234L);
  }

  static DialerCall call(String callId, int state, String number, long creationTime) {
    DialerCall call = mock(DialerCall.class);
    when(call.getId()).thenReturn(callId);
    when(call.getState()).thenReturn(state);
    when(call.getNumber()).thenReturn(number);
    when(call.getCreationTimeMillis()).thenReturn(creationTime);
    when(call.isVideoCall()).thenReturn(false);
    when(call.isConferenceCall()).thenReturn(false);
    when(call.getParentId()).thenReturn(null);
    return call;
  }

  static DialerCall conferenceCall(String callId, String number) {
    DialerCall call = call(callId, DialerCallState.ACTIVE, number);
    when(call.isConferenceCall()).thenReturn(true);
    return call;
  }

  static DialerCall conferenceChildCall(String callId, String number) {
    DialerCall call = call(callId, DialerCallState.CONFERENCED, number);
    when(call.getParentId()).thenReturn("conference-1");
    return call;
  }

  static TestCallList testCallList(DialerCall... calls) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      return new TestCallList(calls);
    }
    AtomicReference<TestCallList> callList = new AtomicReference<>();
    // CallList creates a Handler in its constructor, so build test instances on the main looper.
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> callList.set(new TestCallList(calls)));
    return callList.get();
  }

  static final class TestCallList extends CallList {
    private final AtomicReference<Collection<DialerCall>> calls;

    TestCallList(DialerCall... calls) {
      this.calls = new AtomicReference<>(Arrays.asList(calls));
    }

    DialerCall getOnlyCall() {
      return getAllCalls().iterator().next();
    }

    void setCall(DialerCall call) {
      calls.set(Arrays.asList(call));
    }

    void setCalls(DialerCall... calls) {
      this.calls.set(Arrays.asList(calls));
    }

    @Override
    public Collection<DialerCall> getAllCalls() {
      return calls.get();
    }

    @Override
    public DialerCall getActiveCall() {
      return firstCallWithState(DialerCallState.ACTIVE);
    }

    @Override
    public DialerCall getOutgoingCall() {
      for (DialerCall call : getAllCalls()) {
        if (DialerCallState.isDialing(call.getState())) {
          return call;
        }
      }
      return null;
    }

    @Override
    public DialerCall getActiveOrBackgroundCall() {
      DialerCall activeCall = getActiveCall();
      if (activeCall != null) {
        return activeCall;
      }
      return firstCallWithState(DialerCallState.ONHOLD);
    }

    @Override
    public DialerCall getPendingOutgoingCall() {
      return firstCallWithState(DialerCallState.CONNECTING);
    }

    @Override
    public DialerCall getCallById(String callId) {
      for (DialerCall call : getAllCalls()) {
        if (TextUtils.equals(call.getId(), callId)) {
          return call;
        }
      }
      return null;
    }

    @Override
    public DialerCall getCallWithStateAndNumber(int state, String number) {
      for (DialerCall call : getAllCalls()) {
        if (call.getState() == state && TextUtils.equals(call.getNumber(), number)) {
          return call;
        }
      }
      return null;
    }

    @Override
    public boolean hasLiveCall() {
      return getPendingOutgoingCall() != null
          || getOutgoingCall() != null
          || getActiveCall() != null
          || firstCallWithState(DialerCallState.INCOMING) != null
          || firstCallWithState(DialerCallState.CALL_WAITING) != null;
    }

    private DialerCall firstCallWithState(int state) {
      for (DialerCall call : getAllCalls()) {
        if (call.getState() == state) {
          return call;
        }
      }
      return null;
    }
  }

  static class NoOpRecordingProgressListener implements CallRecorder.RecordingProgressListener {
    @Override
    public void onStartRecording() {}

    @Override
    public void onStopRecording() {}

    @Override
    public void onRecordingTimeProgress(long elapsedTimeMs) {}
  }

  static final class FakeRecorder extends CallRecorder {
    private final CountDownLatch armed = new CountDownLatch(1);
    private final CountDownLatch startedLatch = new CountDownLatch(1);
    String armedCallId;
    boolean armedAutomatically;
    int armCount;
    int bindRequestCount;
    boolean started;
    String startedCallId;

    FakeRecorder() {
      super(new android.os.Handler(Looper.getMainLooper()), new DefaultCallRecorderServiceBinding());
    }

    @Override
    void bindIfNeeded() {
      bindRequestCount++;
    }

    @Override
    public void armRecording(String callId, boolean startedAutomatically) {
      armCount++;
      armedCallId = callId;
      armedAutomatically = startedAutomatically;
      armed.countDown();
    }

    @Override
    void clearArmedRecording() {
      armedCallId = null;
    }

    @Override
    void disarmRecording(String callId) {
      if (TextUtils.equals(armedCallId, callId)) {
        armedCallId = null;
      }
    }

    @Override
    void clearAutomaticArmedRecording() {
      if (armedAutomatically) {
        armedCallId = null;
      }
    }

    @Override
    public boolean startOrArmManualRecording(DialerCall call) {
      started = true;
      startedCallId = call.getId();
      startedLatch.countDown();
      return true;
    }

    boolean awaitArmed() throws InterruptedException {
      return armed.await(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    boolean awaitStarted() throws InterruptedException {
      return startedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  static final class FakeCurrentCalls implements CurrentCalls {
    private final AtomicReference<DialerCall> currentCall;
    private CallSnapshot activeCall;
    private boolean conferenceCallPresent;

    FakeCurrentCalls(CallSnapshot activeCall) {
      this(activeCall, false /* conferenceCallPresent */);
    }

    FakeCurrentCalls(CallSnapshot activeCall, boolean conferenceCallPresent) {
      this.currentCall = null;
      this.activeCall = activeCall;
      this.conferenceCallPresent = conferenceCallPresent;
    }

    FakeCurrentCalls(AtomicReference<DialerCall> currentCall) {
      this.currentCall = currentCall;
    }

    @Override
    public boolean hasOngoingCall() {
      return currentCall != null ? currentCall.get() != null : activeCall != null;
    }

    @Override
    public boolean hasActiveOrBackgroundCall() {
      return hasOngoingCall();
    }

    @Override
    public boolean requiresManualRecordingStart() {
      return conferenceCallPresent;
    }

    @Override
    public CallSnapshot getActiveCall() {
      return activeCall;
    }

    @Override
    public CallSnapshot getCallById(String callId) {
      return activeCall != null && activeCall.getId().equals(callId) ? activeCall : null;
    }

    void setActiveCall(CallSnapshot activeCall) {
      this.activeCall = activeCall;
    }

    void setConferenceCallPresent(boolean conferenceCallPresent) {
      this.conferenceCallPresent = conferenceCallPresent;
    }
  }

  static final class FakeSystem implements CallRecordingSystem {
    private final boolean hasPermissions;
    private boolean userUnlocked;
    private final Runnable lockedUserNotifier;

    FakeSystem() {
      this(true /* hasPermissions */);
    }

    FakeSystem(boolean hasPermissions) {
      this(hasPermissions, true /* userUnlocked */, () -> {});
    }

    FakeSystem(boolean hasPermissions, boolean userUnlocked, Runnable lockedUserNotifier) {
      this.hasPermissions = hasPermissions;
      this.userUnlocked = userUnlocked;
      this.lockedUserNotifier = lockedUserNotifier;
    }

    @Override
    public boolean hasAllPermissions(String[] permissions) {
      return hasPermissions;
    }

    @Override
    public boolean isUserUnlocked() {
      return userUnlocked;
    }

    @Override
    public void showLockedUserMessage() {
      lockedUserNotifier.run();
    }

    void setUserUnlocked(boolean userUnlocked) {
      this.userUnlocked = userUnlocked;
    }
  }
}
