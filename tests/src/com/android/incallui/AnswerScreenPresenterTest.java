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
import com.android.incallui.ContactInfoCache.ContactInfoCacheCallback;
import com.android.incallui.answer.protocol.AnswerScreen;
import com.android.incallui.call.DialerCall;
import com.android.incallui.call.state.DialerCallState;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class AnswerScreenPresenterTest {
  private PermissionContext context;
  private FakeContactLookup contactLookup;

  @Before
  public void setUp() {
    context =
        new PermissionContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(),
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS);
    contactLookup = new FakeContactLookup();
    CallRecordingPreferencesStore.resetForTesting(context, true /* sharedPreferencesMigrated */);
  }

  @After
  public void tearDown() {
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
  public void selectedContactAutomaticRecordingShowsCheckedIncomingSwitch() throws Exception {
    showRecordingWarning();
    recordSelectedContact("+15551234567");
    FakeAnswerScreen answerScreen = new FakeAnswerScreen();
    RecordingChoiceUpdate recordingChoiceUpdate = new RecordingChoiceUpdate();
    createPresenter(answerScreen, incomingAudioCall(), recordingChoiceUpdate);

    assertThat(answerScreen.callRecordingSwitchVisible).isFalse();
    waitForContactDecision(
        contactLookup,
        localContact("+15551234567"),
        () -> answerScreen.callRecordingSwitchVisible);

    assertThat(answerScreen.callRecordingSwitchEnabled).isTrue();
    assertThat(answerScreen.callRecordingSwitchChecked).isTrue();
    assertThat(recordingChoiceUpdate.callId).isEqualTo("call-1");
    assertThat(recordingChoiceUpdate.enabled).isTrue();
  }

  @Test
  public void selectedContactAutomaticRecordingHidesSwitchForDifferentContact() throws Exception {
    showRecordingWarning();
    recordSelectedContact("+15551234567");
    FakeAnswerScreen answerScreen = new FakeAnswerScreen();
    RecordingChoiceUpdate recordingChoiceUpdate = new RecordingChoiceUpdate();
    createPresenter(answerScreen, incomingAudioCall(), recordingChoiceUpdate);

    waitForHiddenContactDecision(
        contactLookup, localContact("+15557654321"), answerScreen);

    assertThat(answerScreen.callRecordingSwitchVisible).isFalse();
    assertThat(answerScreen.callRecordingSwitchEnabled).isFalse();
    assertThat(answerScreen.callRecordingSwitchChecked).isFalse();
    assertThat(recordingChoiceUpdate.updateCount).isEqualTo(0);
  }

  @Test
  public void nonContactAutomaticRecordingShowsCheckedIncomingSwitch() throws Exception {
    showRecordingWarning();
    recordNonContacts();
    FakeAnswerScreen answerScreen = new FakeAnswerScreen();
    RecordingChoiceUpdate recordingChoiceUpdate = new RecordingChoiceUpdate();
    createPresenter(answerScreen, incomingAudioCall(), recordingChoiceUpdate);

    waitForContactDecision(
        contactLookup,
        nonContact(),
        () -> answerScreen.callRecordingSwitchVisible);

    assertThat(answerScreen.callRecordingSwitchEnabled).isTrue();
    assertThat(answerScreen.callRecordingSwitchChecked).isTrue();
    assertThat(recordingChoiceUpdate.enabled).isTrue();
  }

  @Test
  public void incomingAnswerUiKeepsSwitchHiddenWhileContactLookupIsPending() throws Exception {
    showRecordingWarning();
    recordNonContacts();
    FakeAnswerScreen answerScreen = new FakeAnswerScreen();
    RecordingChoiceUpdate recordingChoiceUpdate = new RecordingChoiceUpdate();
    createPresenter(answerScreen, incomingAudioCall(), recordingChoiceUpdate);

    waitForContactLookupStarted(contactLookup);
    runOnMain(() -> contactLookup.complete(pendingContactLookup()));

    assertThat(answerScreen.callRecordingSwitchVisible).isFalse();
    assertThat(answerScreen.callRecordingSwitchEnabled).isFalse();
    assertThat(answerScreen.callRecordingSwitchChecked).isFalse();
    assertThat(recordingChoiceUpdate.updateCount).isEqualTo(0);
  }

  @Test
  public void incomingAnswerUiIgnoresContactLookupAfterScreenIsDestroyed() throws Exception {
    showRecordingWarning();
    recordSelectedContact("+15551234567");
    FakeAnswerScreen answerScreen = new FakeAnswerScreen();
    RecordingChoiceUpdate recordingChoiceUpdate = new RecordingChoiceUpdate();
    AnswerScreenPresenter presenter =
        createPresenter(answerScreen, incomingAudioCall(), recordingChoiceUpdate);

    waitForContactLookupStarted(contactLookup);
    runOnMain(
        () -> {
          presenter.onAnswerScreenUnready();
          contactLookup.complete(localContact("+15551234567"));
        });

    assertThat(answerScreen.callRecordingSwitchChecked).isFalse();
    assertThat(recordingChoiceUpdate.updateCount).isEqualTo(0);
  }

  @Test
  public void incomingAnswerUiDoesNotOverrideUserRecordingChoice() throws Exception {
    showRecordingWarning();
    recordSelectedContact("+15551234567");
    FakeAnswerScreen answerScreen = new FakeAnswerScreen();
    RecordingChoiceUpdate recordingChoiceUpdate = new RecordingChoiceUpdate();
    AnswerScreenPresenter presenter =
        createPresenter(answerScreen, incomingAudioCall(), recordingChoiceUpdate);

    waitForContactDecision(
        contactLookup,
        localContact("+15551234567"),
        () -> answerScreen.callRecordingSwitchVisible);
    // The real Switch updates its checked state before notifying the presenter.
    answerScreen.callRecordingSwitchChecked = false;
    runOnMain(() -> presenter.onCallRecordingSwitchChanged(false /* enabled */));
    runOnMain(() -> contactLookup.complete(localContact("+15551234567")));

    assertThat(answerScreen.callRecordingSwitchChecked).isFalse();
    assertThat(recordingChoiceUpdate.callId).isEqualTo("call-1");
    assertThat(recordingChoiceUpdate.enabled).isFalse();
    assertThat(recordingChoiceUpdate.updateCount).isEqualTo(2);
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
                new AnswerScreenPresenter(
                    context, answerScreen, call, recordingChoiceUpdater, contactLookup)));
    return presenter.get();
  }

  private static void runOnMain(Runnable runnable) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable);
  }

  private static void waitUntil(BooleanSupplier condition) throws Exception {
    long deadlineMillis = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < deadlineMillis) {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(25);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  private static void waitForContactLookupStarted(FakeContactLookup contactLookup)
      throws Exception {
    waitUntil(contactLookup::hasCallback);
  }

  private static void waitForContactDecision(
      FakeContactLookup contactLookup, ContactCacheEntry entry, BooleanSupplier condition)
      throws Exception {
    waitUntil(
        () -> {
          if (!contactLookup.hasCallback()) {
            return false;
          }
          runOnMain(() -> contactLookup.complete(entry));
          return condition.getAsBoolean();
        });
  }

  private static void waitForHiddenContactDecision(
      FakeContactLookup contactLookup, ContactCacheEntry entry, FakeAnswerScreen answerScreen)
      throws Exception {
    // Hidden final decisions intentionally do not write a recording choice. Wait for the screen
    // update so the assertion observes the final contact callback, not only the initial hidden
    // state.
    int visibilityUpdates = answerScreen.callRecordingSwitchVisibilityUpdates;
    waitUntil(
        () -> {
          if (!contactLookup.hasCallback()) {
            return false;
          }
          runOnMain(() -> contactLookup.complete(entry));
          return answerScreen.callRecordingSwitchVisibilityUpdates > visibilityUpdates;
        });
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

  private void recordNonContacts() {
    CallRecordingPreferencesStore.updateBlocking(
        context, builder -> builder.setAutoRecordNonContacts(true));
  }

  private static DialerCall incomingAudioCall() {
    DialerCall call = mock(DialerCall.class);
    when(call.getId()).thenReturn("call-1");
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

  private static ContactCacheEntry nonContact() {
    ContactCacheEntry entry = new ContactCacheEntry();
    entry.contactLookupResult = ContactLookupResult.Type.NOT_FOUND;
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
    private int callRecordingSwitchVisibilityUpdates;
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
      callRecordingSwitchVisibilityUpdates++;
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

  // AnswerScreenPresenter policy depends on delivered contact results, not ContactInfoCache timing.
  // Use a fake lookup source so pending and final results are delivered deterministically.
  private static final class FakeContactLookup implements AnswerScreenPresenter.ContactLookupStarter {
    private ContactInfoCacheCallback callback;

    @Override
    public void findInfo(
        Context context,
        DialerCall call,
        boolean isIncoming,
        ContactInfoCacheCallback callback) {
      assertThat(call.getId()).isEqualTo("call-1");
      assertThat(isIncoming).isTrue();
      this.callback = callback;
    }

    boolean hasCallback() {
      return callback != null;
    }

    void complete(ContactCacheEntry entry) {
      assertThat(callback).isNotNull();
      callback.onContactInfoComplete("call-1", entry);
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
