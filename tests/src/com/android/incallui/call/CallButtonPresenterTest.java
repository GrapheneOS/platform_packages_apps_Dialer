package com.android.incallui.call;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.incallui.CallButtonPresenter;
import com.android.incallui.InCallCameraManager;
import com.android.incallui.InCallPresenter;
import com.android.incallui.InCallPresenter.InCallState;
import com.android.incallui.ThemeColorManager;
import com.android.incallui.call.state.DialerCallState;
import com.android.incallui.incall.protocol.InCallButtonIds;
import com.android.incallui.incall.protocol.InCallButtonUi;
import com.android.incallui.videotech.VideoTech;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CallButtonPresenterTest {

  @After
  public void tearDown() {
    InCallPresenter.setInstanceForTesting(null);
    CallRecorder.resetInstanceForTesting();
    CallList.setCallListInstance(null);
  }

  @Test
  public void connectingOutgoingCallShowsRecordingButton() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    CallButtonPresenter presenter = new CallButtonPresenter(context);
    InCallButtonUi buttonUi = mock(InCallButtonUi.class);
    DialerCall call = audioCall("call-1", DialerCallState.CONNECTING);
    CallList callList = callList(call);
    InCallPresenter inCallPresenter = mock(InCallPresenter.class);
    InCallCameraManager cameraManager = mock(InCallCameraManager.class);
    ThemeColorManager themeColorManager = mock(ThemeColorManager.class);
    when(inCallPresenter.getInCallCameraManager()).thenReturn(cameraManager);
    when(inCallPresenter.getInCallState()).thenReturn(InCallState.NO_CALLS);
    when(inCallPresenter.getCallList()).thenReturn(callList);
    when(inCallPresenter.getThemeColorManager()).thenReturn(themeColorManager);
    InCallPresenter.setInstanceForTesting(inCallPresenter);
    CallList.setCallListInstance(callList);

    runOnMain(
        () -> {
          presenter.onInCallButtonUiReady(buttonUi);
          try {
            presenter.onStateChange(InCallState.NO_CALLS, InCallState.PENDING_OUTGOING, callList);
          } finally {
            presenter.onInCallButtonUiUnready();
          }
        });

    verify(buttonUi).setEnabled(true);
    verify(buttonUi).showButton(InCallButtonIds.BUTTON_RECORD_CALL, true);
  }

  private static void runOnMain(Runnable runnable) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable);
  }

  private static DialerCall audioCall(String callId, int state) {
    DialerCall call = mock(DialerCall.class);
    VideoTech videoTech = mock(VideoTech.class);
    when(call.getId()).thenReturn(callId);
    when(call.getState()).thenReturn(state);
    when(call.isVideoCall()).thenReturn(false);
    when(call.can(anyInt())).thenReturn(false);
    when(call.canUpgradeToRttCall()).thenReturn(false);
    when(call.getVideoTech()).thenReturn(videoTech);
    return call;
  }

  private static CallList callList(DialerCall... calls) {
    AtomicReference<CallList> callList = new AtomicReference<>();
    runOnMain(() -> callList.set(new TestCallList(calls)));
    return callList.get();
  }

  private static final class TestCallList extends CallList {
    private final Collection<DialerCall> calls;

    TestCallList(DialerCall... calls) {
      this.calls = Arrays.asList(calls);
    }

    @Override
    public Collection<DialerCall> getAllCalls() {
      return calls;
    }

    @Override
    public DialerCall getPendingOutgoingCall() {
      return firstCallWithState(DialerCallState.CONNECTING);
    }

    @Override
    public boolean hasLiveCall() {
      return !calls.isEmpty();
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
}
