package com.android.incallui;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.support.v4.app.Fragment;
import android.telecom.TelecomManager;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.dialer.callrecord.CallRecordingPreferencesStore;
import com.android.dialer.inject.HasRootComponent;
import com.android.dialer.logging.ContactLookupResult;
import com.android.incallui.ContactInfoCache.ContactCacheEntry;
import com.android.incallui.answer.protocol.AnswerScreen;
import com.android.incallui.call.DialerCall;
import com.android.incallui.call.state.DialerCallState;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class AnswerScreenPresenterTest {
  private PermissionContext context;

  @Before
  public void setUp() {
    context =
        new PermissionContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(),
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS);
    // ContactInfoCache is a process singleton. A cached final result can race the explicit test
    // callback and move the presenter out of the waiting state before the assertion path runs.
    ContactInfoCache.getInstance(context).clearCache();
    CallRecordingPreferencesStore.resetForTesting(context, true /* sharedPreferencesMigrated */);
  }

  @After
  public void tearDown() {
    // Keep the singleton cache from leaking the final contact result into the next test.
    ContactInfoCache.getInstance(context).clearCache();
    CallRecordingPreferencesStore.resetForTesting(context, true /* sharedPreferencesMigrated */);
    InCallPresenter.setInstanceForTesting(null);
  }

  @Test
  public void videoUpgradeAnswerUiHidesRecordingSwitch() {
    showRecordingWarning();
    FakeAnswerScreen answerScreen = new FakeAnswerScreen();
    answerScreen.videoUpgradeRequest = true;

    createPresenter(answerScreen, incomingAudioCall());

    assertThat(answerScreen.callRecordingSwitchVisible).isFalse();
    assertThat(answerScreen.callRecordingSwitchEnabled).isFalse();
    assertThat(answerScreen.callRecordingPermissionMessage).isNull();
  }

  @Test
  public void incomingAnswerUiEnablesRecordingForSelectedContact() {
    showRecordingWarning();
    recordSelectedContact("+15551234567");
    FakeAnswerScreen answerScreen = new FakeAnswerScreen();
    RecordingChoiceUpdate recordingChoiceUpdate = new RecordingChoiceUpdate();
    AnswerScreenPresenter presenter =
        createPresenter(answerScreen, incomingAudioCall(), recordingChoiceUpdate);

    runOnMain(() -> presenter.onContactInfoComplete("call-1", localContact("+15551234567")));

    assertThat(answerScreen.callRecordingSwitchChecked).isTrue();
    assertThat(recordingChoiceUpdate.callId).isEqualTo("call-1");
    assertThat(recordingChoiceUpdate.enabled).isTrue();
  }

  @Test
  public void incomingAnswerUiDisablesRecordingWhenFinalContactIsNotSelected() {
    showRecordingWarning();
    recordSelectedContact("+15551234567");
    FakeAnswerScreen answerScreen = new FakeAnswerScreen();
    RecordingChoiceUpdate recordingChoiceUpdate = new RecordingChoiceUpdate();
    AnswerScreenPresenter presenter =
        createPresenter(answerScreen, incomingAudioCall(), recordingChoiceUpdate);

    runOnMain(() -> presenter.onContactInfoComplete("call-1", localContact("+15557654321")));

    assertThat(answerScreen.callRecordingSwitchChecked).isFalse();
    assertThat(recordingChoiceUpdate.callId).isEqualTo("call-1");
    assertThat(recordingChoiceUpdate.enabled).isFalse();
    assertThat(recordingChoiceUpdate.updateCount).isEqualTo(1);
  }

  @Test
  public void incomingAnswerUiWaitsForFinalContactLookupBeforeChoosingRecording() {
    showRecordingWarning();
    FakeAnswerScreen answerScreen = new FakeAnswerScreen();
    RecordingChoiceUpdate recordingChoiceUpdate = new RecordingChoiceUpdate();
    AnswerScreenPresenter presenter =
        createPresenter(answerScreen, incomingAudioCall(), recordingChoiceUpdate);

    recordUnknownCallers();
    runOnMain(() -> presenter.onContactInfoComplete("call-1", pendingContactLookup()));

    assertThat(answerScreen.callRecordingSwitchChecked).isFalse();
    assertThat(recordingChoiceUpdate.updateCount).isEqualTo(0);
  }

  @Test
  public void incomingAnswerUiIgnoresContactLookupAfterScreenIsDestroyed() {
    showRecordingWarning();
    FakeAnswerScreen answerScreen = new FakeAnswerScreen();
    RecordingChoiceUpdate recordingChoiceUpdate = new RecordingChoiceUpdate();
    AnswerScreenPresenter presenter =
        createPresenter(answerScreen, incomingAudioCall(), recordingChoiceUpdate);

    recordSelectedContact("+15551234567");
    runOnMain(
        () -> {
          presenter.onAnswerScreenUnready();
          presenter.onContactInfoComplete("call-1", localContact("+15551234567"));
        });

    assertThat(answerScreen.callRecordingSwitchChecked).isFalse();
    assertThat(recordingChoiceUpdate.updateCount).isEqualTo(0);
  }

  @Test
  public void incomingAnswerUiDoesNotOverrideUserRecordingChoice() {
    showRecordingWarning();
    FakeAnswerScreen answerScreen = new FakeAnswerScreen();
    RecordingChoiceUpdate recordingChoiceUpdate = new RecordingChoiceUpdate();
    AnswerScreenPresenter presenter =
        createPresenter(answerScreen, incomingAudioCall(), recordingChoiceUpdate);

    recordSelectedContact("+15551234567");
    runOnMain(
        () -> {
          presenter.onCallRecordingSwitchChanged(false /* enabled */);
          presenter.onContactInfoComplete("call-1", localContact("+15551234567"));
        });

    assertThat(answerScreen.callRecordingSwitchChecked).isFalse();
    assertThat(recordingChoiceUpdate.callId).isEqualTo("call-1");
    assertThat(recordingChoiceUpdate.enabled).isFalse();
  }

  private AnswerScreenPresenter createPresenter(
      FakeAnswerScreen answerScreen, DialerCall call) {
    return createPresenter(
        answerScreen,
        call,
        (callId, enabled) -> {});
  }

  private AnswerScreenPresenter createPresenter(
      FakeAnswerScreen answerScreen,
      DialerCall call,
      AnswerScreenPresenter.IncomingCallRecordingChoiceUpdater recordingChoiceUpdater) {
    AtomicReference<AnswerScreenPresenter> presenter = new AtomicReference<>();
    // AnswerScreenPresenter reaches InCallPresenter, which creates framework listeners tied to the
    // current looper, so construct and drive it on the instrumentation main thread.
    runOnMain(
        () ->
            presenter.set(
                new AnswerScreenPresenter(context, answerScreen, call, recordingChoiceUpdater)));
    return presenter.get();
  }

  private static void runOnMain(Runnable runnable) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable);
  }

  private void showRecordingWarning() {
    CallRecordingPreferencesStore.updateBlocking(
        context, builder -> builder.setRecordingWarningPresented(true));
  }

  private void recordSelectedContact(String normalizedNumber) {
    CallRecordingPreferencesStore.updateBlocking(
        context,
        builder -> {
          builder.setAutoRecordSelectedNumbersEnabled(true);
          builder.addAutoRecordSelectedNumbers(normalizedNumber);
        });
  }

  private void recordUnknownCallers() {
    CallRecordingPreferencesStore.updateBlocking(
        context, builder -> builder.setAutoRecordNonContacts(true));
  }

  private static DialerCall incomingAudioCall() {
    DialerCall call = mock(DialerCall.class);
    when(call.getId()).thenReturn("call-1");
    // ContactInfoCache immediately emits an initial result. Use a normal presented number so that
    // result is marked as pending instead of final; the tests below then provide the final lookup.
    when(call.getNumber()).thenReturn("+15551234567");
    when(call.getNumberPresentation()).thenReturn(TelecomManager.PRESENTATION_ALLOWED);
    when(call.getState()).thenReturn(DialerCallState.CALL_WAITING);
    when(call.isVideoCall()).thenReturn(false);
    when(call.can(anyInt())).thenReturn(false);
    return call;
  }

  private static ContactCacheEntry localContact(String normalizedNumber) {
    ContactCacheEntry entry = new ContactCacheEntry();
    entry.contactLookupResult = ContactLookupResult.Type.LOCAL_CONTACT;
    entry.normalizedNumber = normalizedNumber;
    return entry;
  }

  private static ContactCacheEntry pendingContactLookup() {
    ContactCacheEntry entry = new ContactCacheEntry();
    entry.hasPendingContactLookup = true;
    return entry;
  }

  private static final class FakeAnswerScreen implements AnswerScreen {
    private final Fragment fragment = new Fragment();
    private boolean videoUpgradeRequest;
    private boolean callRecordingSwitchVisible;
    private boolean callRecordingSwitchEnabled;
    private boolean callRecordingSwitchChecked;
    private CharSequence callRecordingPermissionMessage;

    @Override
    public String getCallId() {
      return "call-1";
    }

    @Override
    public boolean isRttCall() {
      return false;
    }

    @Override
    public boolean isVideoCall() {
      return false;
    }

    @Override
    public boolean isVideoUpgradeRequest() {
      return videoUpgradeRequest;
    }

    @Override
    public boolean allowAnswerAndRelease() {
      return false;
    }

    @Override
    public boolean allowSpeakEasy() {
      return false;
    }

    @Override
    public boolean isActionTimeout() {
      return false;
    }

    @Override
    public void setTextResponses(List<String> textResponses) {}

    @Override
    public void setCallRecordingSwitchVisible(boolean visible) {
      callRecordingSwitchVisible = visible;
    }

    @Override
    public void setCallRecordingSwitchEnabled(boolean enabled) {
      callRecordingSwitchEnabled = enabled;
    }

    @Override
    public void setCallRecordingSwitchChecked(boolean checked) {
      callRecordingSwitchChecked = checked;
    }

    @Override
    public void setCallRecordingPermissionMessage(CharSequence message) {
      callRecordingPermissionMessage = message;
    }

    @Override
    public boolean hasPendingDialogs() {
      return false;
    }

    @Override
    public void dismissPendingDialogs() {}

    @Override
    public Fragment getAnswerScreenFragment() {
      return fragment;
    }
  }

  private static final class RecordingChoiceUpdate
      implements AnswerScreenPresenter.IncomingCallRecordingChoiceUpdater {
    private String callId;
    private boolean enabled;
    private int updateCount;

    @Override
    public void setIncomingCallRecordingEnabled(String callId, boolean enabled) {
      this.callId = callId;
      this.enabled = enabled;
      updateCount++;
    }
  }

  private static final class PermissionContext extends ContextWrapper implements HasRootComponent {
    private final Context baseContext;
    private final List<String> grantedPermissions;

    PermissionContext(Context baseContext, String... grantedPermissions) {
      super(baseContext);
      this.baseContext = baseContext;
      this.grantedPermissions = Arrays.asList(grantedPermissions);
    }

    @Override
    public Context getApplicationContext() {
      return this;
    }

    @Override
    public Object component() {
      return ((HasRootComponent) baseContext.getApplicationContext()).component();
    }

    @Override
    public int checkPermission(String permission, int pid, int uid) {
      return grantedPermissions.contains(permission)
          ? PackageManager.PERMISSION_GRANTED
          : PackageManager.PERMISSION_DENIED;
    }

    @Override
    public int checkSelfPermission(String permission) {
      return checkPermission(permission, 0 /* pid */, 0 /* uid */);
    }
  }
}
