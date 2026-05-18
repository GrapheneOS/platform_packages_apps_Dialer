package com.android.dialer.integration;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.UiAutomation;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.provider.MediaStore;
import android.service.notification.StatusBarNotification;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import androidx.test.platform.app.InstrumentationRegistry;
import com.android.dialer.callrecord.CallRecordingPreferences;
import com.android.dialer.callrecord.CallRecordingPreferenceValues;
import com.android.dialer.callrecord.CallRecordingPreferencesStore;
import com.android.dialer.callrecord.RecordingOutputFormat;
import com.android.incallui.ContactInfoCache;
import com.android.incallui.call.CallList;
import com.android.incallui.call.CallRecordingController;
import com.android.incallui.call.DialerCall;
import com.android.incallui.call.RecordingRules;
import com.android.incallui.call.TelecomAdapter;
import com.android.incallui.call.state.DialerCallState;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;

/**
 * Shared harness for automatic call recording integration tests.
 *
 * <p>The concrete test classes use a real platform {@link android.telecom.ConnectionService} and
 * assert through production incallui state. Keep scenarios narrow: broader policy combinations
 * belong in faster unit tests unless the behavior depends on Telecom's actual call transitions.
 *
 * <p>The Telecom setup follows the platform CTS integration test shape in:
 *
 * <ul>
 *   <li>cts/tests/tests/telecom/src/android/telecom/cts/BaseTelecomTestWithMockServices.java
 *   <li>cts/tests/tests/telecom/src/android/telecom/cts/IncomingCallTest.java
 *   <li>cts/tests/tests/telecom/src/android/telecom/cts/TestUtils.java
 * </ul>
 */
abstract class AutoCallRecordingIntegrationTestBase {

  private static final String ACCOUNT_ID = "dialer-integration";
  private static final String CONNECTION_SERVICE_PACKAGE =
      "com.android.dialer.integration.connection";
  private static final String MOCK_CONNECTION_SERVICE_CLASS =
      "com.android.dialer.integration.connection.MockDialerConnectionService";
  private static final String CONNECTION_RECEIVER_CLASS =
      "com.android.dialer.integration.connection.DialerIntegrationConnectionReceiver";
  private static final String ACTION_REGISTER_CONNECTION_SERVICE =
      "com.android.dialer.integration.connection.REGISTER";
  private static final String ACTION_ADD_INCOMING_CALL =
      "com.android.dialer.integration.connection.ADD_INCOMING_CALL";
  protected static final String TEST_NUMBER = "+12025550100";
  protected static final String TEST_NUMBER_FORMATTED = "+1 (202) 555-0100";
  protected static final String SECOND_TEST_NUMBER = "+12025550101";
  protected static final String SECOND_TEST_NUMBER_FORMATTED = "+1 (202) 555-0101";
  protected static final String THIRD_TEST_NUMBER = "+12025550102";
  protected static final String THIRD_TEST_NUMBER_FORMATTED = "+1 (202) 555-0102";
  private static final String[] TEST_NUMBERS = {TEST_NUMBER, SECOND_TEST_NUMBER, THIRD_TEST_NUMBER};
  protected static final long TIMEOUT_MILLIS = 10000;
  private static final long RECORDING_STABILITY_MILLIS = 1000;

  protected Instrumentation instrumentation;
  private Context testContext;
  protected Context targetContext;
  private TelecomManager telecomManager;
  private NotificationManager notificationManager;
  private PhoneAccountHandle phoneAccountHandle;
  private String previousDefaultDialer;
  private CallRecordingPreferences originalPreferences;
  private long testStartTimeMillis;
  private boolean shouldCleanupRecordings;
  private final Map<String, Boolean> originalTargetPermissionState = new HashMap<>();
  private final List<Uri> contactsCreatedByTest = new ArrayList<>();

  @Before
  public void setUp() throws Exception {
    instrumentation = InstrumentationRegistry.getInstrumentation();
    testContext = instrumentation.getContext();
    targetContext = instrumentation.getTargetContext();
    ContactInfoCache.getInstance(targetContext).clearCache();
    telecomManager = targetContext.getSystemService(TelecomManager.class);
    assumeTrue(telecomManager != null);
    notificationManager = targetContext.getSystemService(NotificationManager.class);
    assumeTrue(notificationManager != null);
    assumeTrue(hasTelecomFeature());
    wakeAndDismissKeyguardIfNeeded();
    testStartTimeMillis = System.currentTimeMillis();

    previousDefaultDialer = shell("telecom get-default-dialer").trim();
    shell("telecom set-default-dialer " + targetContext.getPackageName());
    waitUntil(
        "DialerForTesting to become the default dialer",
        () -> targetContext.getPackageName().equals(shell("telecom get-default-dialer").trim()));

    phoneAccountHandle =
        new PhoneAccountHandle(
            new ComponentName(CONNECTION_SERVICE_PACKAGE, MOCK_CONNECTION_SERVICE_CLASS),
            ACCOUNT_ID);
    unregisterTestPhoneAccount();
    registerTestPhoneAccount();
    shell(
        "telecom set-phone-account-enabled "
            + phoneAccountHandle.getComponentName().flattenToString()
            + " "
            + phoneAccountHandle.getId()
            + " "
            + userSerial(phoneAccountHandle));
    waitUntil(
        "test phone account to become enabled",
        () -> {
          PhoneAccount account = telecomManager.getPhoneAccount(phoneAccountHandle);
          return account != null && account.isEnabled();
        });
  }

  @After
  public void tearDown() throws Exception {
    if (instrumentation == null) {
      return;
    }
    if (hasTelecomFeature()) {
      stopRecordingIfNeeded();
      disconnectDialerCalls();
      waitOnTelecomHandlers();
      shell("telecom cleanup-stuck-calls");
    }
    if (shouldCleanupRecordings) {
      cleanupGeneratedRecordings();
    }
    if (telecomManager != null && phoneAccountHandle != null) {
      unregisterTestPhoneAccount();
    }
    if (previousDefaultDialer != null && !previousDefaultDialer.isEmpty()) {
      shell("telecom set-default-dialer " + previousDefaultDialer);
    }
    if (originalPreferences != null) {
      writeCallRecordingPreferences(originalPreferences);
    }
    cleanupContactsCreatedByTest();
    ContactInfoCache.getInstance(targetContext).clearCache();
    restoreTargetPermissions();
  }

  private boolean hasTelecomFeature() {
    if (targetContext == null) {
      return false;
    }
    PackageManager packageManager = targetContext.getPackageManager();
    return packageManager.hasSystemFeature(PackageManager.FEATURE_TELECOM)
        || packageManager.hasSystemFeature(PackageManager.FEATURE_CONNECTION_SERVICE);
  }

  protected void addIncomingCall(String number) throws Exception {
    shell(
        "am broadcast -a "
            + ACTION_ADD_INCOMING_CALL
            + " -n "
            + CONNECTION_SERVICE_PACKAGE
            + "/"
            + CONNECTION_RECEIVER_CLASS
            + " --es account_id "
            + ACCOUNT_ID
            + " --es number "
            + number);
    waitOnTelecomHandlers();
  }

  protected void placeOutgoingCall(String number) throws Exception {
    Bundle extras = new Bundle();
    extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle);
    telecomManager.placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), extras);
    waitOnTelecomHandlers();
  }

  protected void answerIncomingCall() throws Exception {
    DialerCall incomingCall = waitForIncomingCall();
    String callId = incomingCall.getId();
    instrumentation.runOnMainSync(() -> incomingCall.answer(VideoProfile.STATE_AUDIO_ONLY));
    waitUntil(
        "incoming call to become active",
        () -> {
          DialerCall call = callById(callId);
          return call != null && call.getState() == DialerCallState.ACTIVE;
        });
  }

  protected void showInCallScreen() throws Exception {
    telecomManager.showInCallScreen(false /* showDialpad */);
    waitOnTelecomHandlers();
  }

  protected DialerCall waitForIncomingCall() throws Exception {
    AtomicReference<DialerCall> incomingCall = new AtomicReference<>();
    waitUntil(
        "incoming call to reach CallList",
        () -> {
          instrumentation.runOnMainSync(
              () -> incomingCall.set(CallList.getInstance().getIncomingCall()));
          return incomingCall.get() != null;
        });
    return incomingCall.get();
  }

  protected DialerCall waitForIncomingCall(String number) throws Exception {
    AtomicReference<DialerCall> incomingCall = new AtomicReference<>();
    Uri handle = Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null);
    waitUntil(
        "incoming call from " + number + " to reach CallList",
        () -> {
          instrumentation.runOnMainSync(
              () -> {
                DialerCall call = CallList.getInstance().getIncomingCall();
                if (call != null && handle.equals(call.getHandle())) {
                  incomingCall.set(call);
                }
              });
          return incomingCall.get() != null;
        });
    return incomingCall.get();
  }

  protected DialerCall callById(String callId) {
    AtomicReference<DialerCall> result = new AtomicReference<>();
    instrumentation.runOnMainSync(() -> result.set(CallList.getInstance().getCallById(callId)));
    return result.get();
  }

  protected void waitForCallWithNumberAndState(String number, int state) throws Exception {
    waitUntil(
        "call " + number + " to reach state " + DialerCallState.toString(state),
        () -> callWithNumberAndState(number, state) != null);
  }

  protected void waitForCallToDisappear(String number) throws Exception {
    waitUntil("call " + number + " to leave CallList", () -> callWithNumber(number) == null);
  }

  protected DialerCall callWithNumber(String number) {
    AtomicReference<DialerCall> result = new AtomicReference<>();
    Uri handle = Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null);
    instrumentation.runOnMainSync(
        () -> {
          for (DialerCall call : CallList.getInstance().getAllCalls()) {
            if (handle.equals(call.getHandle())) {
              result.set(call);
              return;
            }
          }
        });
    return result.get();
  }

  protected DialerCall callWithNumberAndState(String number, int state) {
    AtomicReference<DialerCall> result = new AtomicReference<>();
    Uri handle = Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null);
    instrumentation.runOnMainSync(
        () -> {
          for (DialerCall call : CallList.getInstance().getAllCalls()) {
            if (call.getState() == state && handle.equals(call.getHandle())) {
              result.set(call);
              return;
            }
          }
        });
    return result.get();
  }

  private void disconnectDialerCalls() {
    instrumentation.runOnMainSync(
        () -> {
          for (DialerCall call : new ArrayList<>(CallList.getInstance().getAllCalls())) {
            call.disconnect();
          }
        });
  }

  protected void disconnectCall(String number) throws Exception {
    runOnCallWithNumber(number, DialerCall::disconnect);
    waitOnTelecomHandlers();
  }

  protected void rejectIncomingCall(String number) throws Exception {
    runOnCallWithNumber(number, call -> call.reject(false /* rejectWithMessage */, null));
    waitOnTelecomHandlers();
  }

  protected void mergeActiveCallWithHeldCall() throws Exception {
    instrumentation.runOnMainSync(
        () -> {
          DialerCall activeCall = CallList.getInstance().getActiveCall();
          assertThat(activeCall).isNotNull();
          assertThat(activeCall.can(android.telecom.Call.Details.CAPABILITY_MERGE_CONFERENCE))
              .isTrue();
          TelecomAdapter.getInstance().merge(activeCall.getId());
        });
    waitOnTelecomHandlers();
  }

  protected boolean hasConferenceCall() {
    AtomicReference<Boolean> result = new AtomicReference<>(false);
    instrumentation.runOnMainSync(
        () -> {
          for (DialerCall call : CallList.getInstance().getAllCalls()) {
            if (RecordingRules.isConferenceCall(call)) {
              result.set(true);
              return;
            }
          }
        });
    return result.get();
  }

  protected void waitForConferenceCall() throws Exception {
    waitUntil("conference call to appear", this::hasConferenceCall);
  }

  protected void switchToHeldCall(String number) throws Exception {
    // incallui swaps two ordinary calls by unholding the held call. Telecom then decides which
    // call becomes active and which call moves to hold; the test asserts those observed Dialer
    // states instead of updating them itself.
    runOnCallWithNumber(number, DialerCall::unhold);
    waitOnTelecomHandlers();
  }

  private void runOnCallWithNumber(String number, CallAction action) {
    Uri handle = Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null);
    AtomicReference<DialerCall> matchingCall = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          for (DialerCall call : CallList.getInstance().getAllCalls()) {
            if (handle.equals(call.getHandle())) {
              matchingCall.set(call);
              action.run(call);
              return;
            }
          }
        });
    assertThat(matchingCall.get()).isNotNull();
  }

  protected void stopRecordingIfNeeded() {
    instrumentation.runOnMainSync(
        () -> {
          if (CallRecordingController.getInstance().isRecording()) {
            CallRecordingController.getInstance()
                .stopRecordingFromUi(CallList.getInstance().getActiveCall());
          }
        });
  }

  protected boolean isRecording() {
    AtomicReference<Boolean> recording = new AtomicReference<>(false);
    instrumentation.runOnMainSync(
        () -> recording.set(CallRecordingController.getInstance().isRecording()));
    return recording.get();
  }

  protected void waitForRecordingToStart() throws Exception {
    waitUntil("recording to start", this::isRecording);
  }

  protected void waitForRecordingToStop() throws Exception {
    waitUntil("recording to stop", () -> !isRecording());
  }

  protected void setIncomingRecordingChoice(String callId, boolean enabled) {
    instrumentation.runOnMainSync(
        () -> CallRecordingController.getInstance()
            .setIncomingCallRecordingEnabled(callId, enabled));
  }

  protected void assertRecordingStaysOff() throws Exception {
    // Automatic decisions can complete after the visible call state changes. Hold the assertion
    // briefly so a late automatic start does not pass as an immediate false reading.
    long deadlineMillis = System.currentTimeMillis() + RECORDING_STABILITY_MILLIS;
    while (System.currentTimeMillis() < deadlineMillis) {
      assertWithMessage("Recording should stay off").that(isRecording()).isFalse();
      Thread.sleep(50);
    }
  }

  protected void assertRecordingStaysOn() throws Exception {
    long deadlineMillis = System.currentTimeMillis() + RECORDING_STABILITY_MILLIS;
    while (System.currentTimeMillis() < deadlineMillis) {
      assertWithMessage("Recording should stay on").that(isRecording()).isTrue();
      Thread.sleep(50);
    }
  }

  protected void waitForCallNotificationVerificationText(String expectedText) throws Exception {
    waitUntil(
        "call notification to show text: " + expectedText,
        () -> hasCallNotificationVerificationText(expectedText));
  }

  protected void assertRecentCallNotificationsDoNotShowText(String text) throws Exception {
    long deadlineMillis = System.currentTimeMillis() + RECORDING_STABILITY_MILLIS;
    while (System.currentTimeMillis() < deadlineMillis) {
      assertThat(callNotificationTexts()).doesNotContain(text);
      Thread.sleep(50);
    }
  }

  protected void waitForCallNotification() throws Exception {
    waitUntil("recent call notification to appear", this::hasRecentCallNotification);
  }

  protected void sendIncomingCallAnswerNotificationAction() throws Exception {
    AtomicReference<Notification.Action> actionRef = new AtomicReference<>();
    waitUntil(
        "incoming call notification answer action to appear",
        () -> {
          actionRef.set(findIncomingCallAnswerNotificationAction());
          return actionRef.get() != null;
        });
    try {
      actionRef.get().actionIntent.send();
    } catch (PendingIntent.CanceledException e) {
      throw new AssertionError("Call notification action was canceled", e);
    }
    waitOnTelecomHandlers();
  }

  private boolean hasCallNotificationVerificationText(String expectedText) {
    StatusBarNotification[] activeNotifications = notificationManager.getActiveNotifications();
    for (StatusBarNotification statusBarNotification : activeNotifications) {
      if (!isRecentCallNotification(statusBarNotification)) {
        continue;
      }
      CharSequence verificationText =
          statusBarNotification
              .getNotification()
              .extras
              .getCharSequence(Notification.EXTRA_VERIFICATION_TEXT);
      if (verificationText != null && expectedText.contentEquals(verificationText)) {
        return true;
      }
    }
    return false;
  }

  private List<String> callNotificationTexts() {
    List<String> texts = new ArrayList<>();
    StatusBarNotification[] activeNotifications = notificationManager.getActiveNotifications();
    for (StatusBarNotification statusBarNotification : activeNotifications) {
      if (!isRecentCallNotification(statusBarNotification)) {
        continue;
      }
      Notification notification = statusBarNotification.getNotification();
      Bundle extras = notification.extras;
      if (extras == null) {
        continue;
      }
      addText(texts, extras.getCharSequence(Notification.EXTRA_VERIFICATION_TEXT));
      addText(texts, extras.getCharSequence(Notification.EXTRA_TEXT));
      addText(texts, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
      addText(texts, extras.getCharSequence(Notification.EXTRA_TITLE));
    }
    return texts;
  }

  private void addText(List<String> texts, CharSequence text) {
    if (text != null) {
      texts.add(text.toString());
    }
  }

  private boolean hasRecentCallNotification() {
    StatusBarNotification[] activeNotifications = notificationManager.getActiveNotifications();
    for (StatusBarNotification statusBarNotification : activeNotifications) {
      if (isRecentCallNotification(statusBarNotification)) {
        return true;
      }
    }
    return false;
  }

  private boolean isRecentCallNotification(StatusBarNotification statusBarNotification) {
    Notification notification = statusBarNotification.getNotification();
    return targetContext.getPackageName().equals(statusBarNotification.getPackageName())
        && statusBarNotification.getPostTime() >= testStartTimeMillis
        && Notification.CATEGORY_CALL.equals(notification.category);
  }

  private Notification.Action findIncomingCallAnswerNotificationAction() {
    StatusBarNotification[] activeNotifications = notificationManager.getActiveNotifications();
    for (StatusBarNotification statusBarNotification : activeNotifications) {
      if (!isRecentCallNotification(statusBarNotification)) {
        continue;
      }
      Notification.Action[] actions = statusBarNotification.getNotification().actions;
      if (actions == null || actions.length < 2) {
        continue;
      }
      // Notification.CallStyle synthesizes incoming call actions as decline first and answer last.
      // The synthesized actions do not carry distinct semantic action constants.
      return actions[actions.length - 1];
    }
    return null;
  }

  protected void waitOnTelecomHandlers() throws Exception {
    shell("telecom wait-on-handlers");
  }

  protected void scheduleJob(int jobId, Class<?> jobServiceClass) {
    JobScheduler jobScheduler = targetContext.getSystemService(JobScheduler.class);
    assertThat(jobScheduler).isNotNull();
    int result =
        jobScheduler.schedule(
            new JobInfo.Builder(jobId, new ComponentName(targetContext, jobServiceClass)).build());
    assertWithMessage("scheduling job %s", jobId)
        .that(result)
        .isEqualTo(JobScheduler.RESULT_SUCCESS);
  }

  protected void runScheduledJob(int jobId) throws Exception {
    JobScheduler jobScheduler = targetContext.getSystemService(JobScheduler.class);
    assertThat(jobScheduler).isNotNull();
    assertWithMessage("pending job %s", jobId).that(jobScheduler.getPendingJob(jobId)).isNotNull();
    String command =
        "cmd jobscheduler run -f " + targetContext.getPackageName() + " " + jobId + " 2>&1";
    shell(command);
  }

  private long userSerial(PhoneAccountHandle handle) {
    UserManager userManager = testContext.getSystemService(UserManager.class);
    return userManager.getSerialNumberForUser(handle.getUserHandle());
  }

  protected boolean isUserUnlocked() {
    UserManager userManager = targetContext.getSystemService(UserManager.class);
    return userManager == null || userManager.isUserUnlocked();
  }

  // These tests assert incallui state and Dialer notification contents. A skipped UI test can leave
  // the device on the keyguard, so clear that state before each call scenario. If a secure keyguard
  // cannot be dismissed, skip instead of reporting a product failure.
  private void wakeAndDismissKeyguardIfNeeded() throws Exception {
    shell("input keyevent KEYCODE_WAKEUP");
    if (!isKeyguardLocked()) {
      return;
    }
    shell("wm dismiss-keyguard");

    long deadlineMillis = System.currentTimeMillis() + TIMEOUT_MILLIS;
    while (System.currentTimeMillis() < deadlineMillis && isKeyguardLocked()) {
      Thread.sleep(50);
    }
    assumeTrue("device keyguard can be dismissed", !isKeyguardLocked());
  }

  private boolean isKeyguardLocked() {
    KeyguardManager keyguardManager = targetContext.getSystemService(KeyguardManager.class);
    return keyguardManager != null && keyguardManager.isKeyguardLocked();
  }

  protected void seedAutomaticRecordingPreferences() throws Exception {
    if (originalPreferences == null) {
      originalPreferences = loadCallRecordingPreferences();
    }
    writeCallRecordingPreferences(
        CallRecordingPreferences.newBuilder()
            .setSharedPreferencesMigrated(true)
            .setUseCallRecordingV2(true)
            .setCallRecordingOutputFormatV2(RecordingOutputFormat.LPCM_WAV)
            .setRecordingWarningPresented(true)
            .setAutoRecordingSetAtLeastOnce(true)
            .setAutoRecordNonContacts(true)
            .build());
  }

  protected void seedRecordingSwitchPreferencesWithoutAutomaticRules() throws Exception {
    if (originalPreferences == null) {
      originalPreferences = loadCallRecordingPreferences();
    }
    writeCallRecordingPreferences(
        CallRecordingPreferences.newBuilder()
            .setSharedPreferencesMigrated(true)
            .setUseCallRecordingV2(true)
            .setCallRecordingOutputFormatV2(RecordingOutputFormat.LPCM_WAV)
            .setRecordingWarningPresented(true)
            .build());
  }

  protected void seedSelectedNumberRecordingPreferences(String... selectedNumbers)
      throws Exception {
    if (originalPreferences == null) {
      originalPreferences = loadCallRecordingPreferences();
    }
    CallRecordingPreferences.Builder builder =
        CallRecordingPreferences.newBuilder()
            .setSharedPreferencesMigrated(true)
            .setUseCallRecordingV2(true)
            .setCallRecordingOutputFormatV2(RecordingOutputFormat.LPCM_WAV)
            .setRecordingWarningPresented(true)
            .setAutoRecordingSetAtLeastOnce(true)
            .setAutoRecordSelectedNumbersEnabled(true);
    CallRecordingPreferenceValues.setSelectedNumbers(
        builder, new HashSet<>(Arrays.asList(selectedNumbers)));
    writeCallRecordingPreferences(builder.build());
  }

  protected void seedAutomaticRecordingPreferencesWithoutWarning() throws Exception {
    if (originalPreferences == null) {
      originalPreferences = loadCallRecordingPreferences();
    }
    writeCallRecordingPreferences(
        CallRecordingPreferences.newBuilder()
            .setSharedPreferencesMigrated(true)
            .setUseCallRecordingV2(true)
            .setCallRecordingOutputFormatV2(RecordingOutputFormat.LPCM_WAV)
            .setAutoRecordingSetAtLeastOnce(true)
            .setAutoRecordNonContacts(true)
            .build());
  }

  protected void cleanupRecordingsCreatedByTest() {
    shouldCleanupRecordings = true;
  }

  private CallRecordingPreferences loadCallRecordingPreferences() throws Exception {
    return CallRecordingPreferencesStore.loadAsync(targetContext).get(10, TimeUnit.SECONDS);
  }

  private void writeCallRecordingPreferences(CallRecordingPreferences preferences)
      throws Exception {
    CallRecordingPreferences updated =
        CallRecordingPreferencesStore.updateBlocking(
            targetContext,
            builder -> {
              builder.clear();
              builder.mergeFrom(preferences);
            });
    assertThat(updated).isEqualTo(preferences);
  }

  protected void grantTargetPermission(String permission) {
    rememberOriginalPermissionState(permission);
    if (targetContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
      return;
    }
    UiAutomation uiAutomation = instrumentation.getUiAutomation();
    uiAutomation.grantRuntimePermissionAsUser(
        targetContext.getPackageName(), permission, UserHandle.CURRENT);
    assertThat(targetContext.checkSelfPermission(permission))
        .isEqualTo(PackageManager.PERMISSION_GRANTED);
  }

  protected boolean revokeTargetPermission(String permission) {
    rememberOriginalPermissionState(permission);
    if (targetContext.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED) {
      return true;
    }
    UiAutomation uiAutomation = instrumentation.getUiAutomation();
    uiAutomation.revokeRuntimePermissionAsUser(
        targetContext.getPackageName(), permission, UserHandle.CURRENT);
    return targetContext.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED;
  }

  private void rememberOriginalPermissionState(String permission) {
    if (!originalTargetPermissionState.containsKey(permission)) {
      originalTargetPermissionState.put(
          permission,
          targetContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED);
    }
  }

  private void restoreTargetPermissions() {
    UiAutomation uiAutomation = instrumentation.getUiAutomation();
    for (Map.Entry<String, Boolean> entry : originalTargetPermissionState.entrySet()) {
      if (entry.getValue()) {
        uiAutomation.grantRuntimePermissionAsUser(
            targetContext.getPackageName(), entry.getKey(), UserHandle.CURRENT);
      } else {
        uiAutomation.revokeRuntimePermissionAsUser(
            targetContext.getPackageName(), entry.getKey(), UserHandle.CURRENT);
      }
    }
    originalTargetPermissionState.clear();
  }

  protected void insertLocalContact(String displayName, String phoneNumber) throws Exception {
    ContentResolver resolver = targetContext.getContentResolver();
    Uri rawContactUri = resolver.insert(RawContacts.CONTENT_URI, new ContentValues());
    assertThat(rawContactUri).isNotNull();
    contactsCreatedByTest.add(rawContactUri);
    long rawContactId = ContentUris.parseId(rawContactUri);

    ContentValues nameValues = new ContentValues();
    nameValues.put(Data.RAW_CONTACT_ID, rawContactId);
    nameValues.put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE);
    nameValues.put(StructuredName.DISPLAY_NAME, displayName);
    assertThat(resolver.insert(Data.CONTENT_URI, nameValues)).isNotNull();

    ContentValues phoneValues = new ContentValues();
    phoneValues.put(Data.RAW_CONTACT_ID, rawContactId);
    phoneValues.put(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE);
    phoneValues.put(Phone.NUMBER, phoneNumber);
    phoneValues.put(Phone.TYPE, Phone.TYPE_MOBILE);
    assertThat(resolver.insert(Data.CONTENT_URI, phoneValues)).isNotNull();

    waitForContactNumber(phoneNumber);
  }

  protected void waitForContactNumber(String number) throws Exception {
    waitUntil("contact " + number + " to become visible", () -> !numberIsNotInContacts(number));
  }

  private void cleanupContactsCreatedByTest() {
    ContentResolver resolver = targetContext.getContentResolver();
    for (Uri rawContactUri : contactsCreatedByTest) {
      resolver.delete(rawContactUri, null, null);
    }
    contactsCreatedByTest.clear();
  }

  protected boolean numberIsNotInContacts(String number) {
    Uri uri =
        Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
    try (Cursor cursor =
        targetContext
            .getContentResolver()
            .query(uri, new String[] {ContactsContract.PhoneLookup._ID}, null, null, null)) {
      return cursor == null || !cursor.moveToFirst();
    }
  }

  protected int generatedRecordingCount(String number) {
    ContentResolver resolver = targetContext.getContentResolver();
    String selection =
        MediaStore.Audio.Media.DISPLAY_NAME + " LIKE ? AND "
            + MediaStore.Audio.Media.DATE_ADDED + " >= ?";
    String[] args =
        new String[] {
          "CallRecord_%_" + number + ".%",
          Long.toString(TimeUnit.MILLISECONDS.toSeconds(testStartTimeMillis) - 5)
        };
    try (Cursor cursor =
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            new String[] {MediaStore.Audio.Media._ID},
            selection,
            args,
            null)) {
      return cursor == null ? 0 : cursor.getCount();
    }
  }

  private void cleanupGeneratedRecordings() {
    for (String number : TEST_NUMBERS) {
      cleanupGeneratedRecordings(number);
    }
  }

  private void cleanupGeneratedRecordings(String number) {
    ContentResolver resolver = targetContext.getContentResolver();
    String selection =
        MediaStore.Audio.Media.DISPLAY_NAME + " LIKE ? AND "
            + MediaStore.Audio.Media.DATE_ADDED + " >= ?";
    String[] args =
        new String[] {
          "CallRecord_%_" + number + ".%",
          Long.toString(TimeUnit.MILLISECONDS.toSeconds(testStartTimeMillis) - 5)
        };
    try (Cursor cursor =
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            new String[] {MediaStore.Audio.Media._ID},
            selection,
            args,
            null)) {
      if (cursor == null) {
        return;
      }
      while (cursor.moveToNext()) {
        Uri uri =
            ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0));
        resolver.delete(uri, null, null);
      }
    }
  }

  protected static void waitUntil(String description, BooleanSupplier condition) throws Exception {
    long deadlineMillis = System.currentTimeMillis() + TIMEOUT_MILLIS;
    while (System.currentTimeMillis() < deadlineMillis) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(50);
    }
    assertWithMessage("Timed out waiting for %s", description)
        .that(condition.getAsBoolean())
        .isTrue();
  }

  private void registerTestPhoneAccount() throws Exception {
    shell(
        "am broadcast -a "
            + ACTION_REGISTER_CONNECTION_SERVICE
            + " -n "
            + CONNECTION_SERVICE_PACKAGE
            + "/"
            + CONNECTION_RECEIVER_CLASS
            + " --es account_id "
            + ACCOUNT_ID);
  }

  private void unregisterTestPhoneAccount() throws Exception {
    shell(
        "telecom unregister-phone-account "
            + phoneAccountHandle.getComponentName().flattenToString()
            + " "
            + phoneAccountHandle.getId()
            + " "
            + userSerial(phoneAccountHandle));
  }

  private String shell(String command) throws Exception {
    ParcelFileDescriptor fd =
        instrumentation.getUiAutomation().executeShellCommand(command);
    try (InputStream in = new FileInputStream(fd.getFileDescriptor());
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      StringBuilder output = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line);
      }
      return output.toString();
    } finally {
      fd.close();
    }
  }

  protected interface BooleanSupplier {
    boolean getAsBoolean() throws Exception;
  }

  private interface CallAction {
    void run(DialerCall call);
  }
}
