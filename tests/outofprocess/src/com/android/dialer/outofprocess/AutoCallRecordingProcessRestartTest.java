package com.android.dialer.outofprocess;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
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
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Out of process coverage for behavior that must survive Dialer process death.
 *
 * <p>The test runner is not instrumenting Dialer, so it can kill Dialer's incallui process while
 * Telecom keeps the call alive through the helper connection service. The target receivers used
 * here are compiled only into DialerForTesting.
 */
@RunWith(Parameterized.class)
public final class AutoCallRecordingProcessRestartTest {
  private static final String DIALER_PACKAGE = "com.android.dialer";
  private static final String CONNECTION_SERVICE_PACKAGE =
      "com.android.dialer.integration.connection";
  private static final String MOCK_CONNECTION_SERVICE_CLASS =
      "com.android.dialer.integration.connection.MockDialerConnectionService";
  private static final String CONNECTION_RECEIVER_CLASS =
      "com.android.dialer.integration.connection.DialerIntegrationConnectionReceiver";
  private static final String OUT_OF_PROCESS_COMMAND_RECEIVER_CLASS =
      "com.android.dialer.outofprocess.target.DialerOutOfProcessCommandReceiver";
  private static final String OUT_OF_PROCESS_DIALER_CRASH_RECEIVER_CLASS =
      "com.android.dialer.outofprocess.target.DialerOutOfProcessDialerCrashReceiver";
  private static final String OUT_OF_PROCESS_INCALLUI_CRASH_RECEIVER_CLASS =
      "com.android.dialer.outofprocess.target.DialerOutOfProcessIncalluiCrashReceiver";
  private static final String INCALLUI_PROCESS = "com.android.incallui";
  private static final String ACTION_REGISTER_CONNECTION_SERVICE =
      "com.android.dialer.integration.connection.REGISTER";
  private static final String ACTION_ADD_INCOMING_CALL =
      "com.android.dialer.integration.connection.ADD_INCOMING_CALL";
  private static final String ACTION_CRASH_DIALER_FOR_TESTING =
      "com.android.dialer.outofprocess.CRASH_DIALER_FOR_TESTING";
  private static final String ACTION_CRASH_INCALLUI_FOR_TESTING =
      "com.android.dialer.outofprocess.CRASH_INCALLUI_FOR_TESTING";
  private static final String EXTRA_PRESENTATION = "presentation";
  private static final String ACTION_SAVE_CALL_RECORDING_PREFERENCES =
      "com.android.dialer.outofprocess.SAVE_CALL_RECORDING_PREFERENCES";
  private static final String ACTION_RESTORE_CALL_RECORDING_PREFERENCES =
      "com.android.dialer.outofprocess.RESTORE_CALL_RECORDING_PREFERENCES";
  private static final String ACTION_SEED_AUTO_RECORD_NON_CONTACTS =
      "com.android.dialer.outofprocess.SEED_AUTO_RECORD_NON_CONTACTS";
  private static final String ACTION_SEED_RECORDING_SWITCH_WITHOUT_AUTOMATIC_RULES =
      "com.android.dialer.outofprocess.SEED_RECORDING_SWITCH_WITHOUT_AUTOMATIC_RULES";
  private static final String ACTION_MERGE_ACTIVE_CALL_FOR_TESTING =
      "com.android.dialer.outofprocess.MERGE_ACTIVE_CALL_FOR_TESTING";
  private static final String ACCOUNT_ID = "dialer-outofprocess";
  private static final String TEST_NUMBER = "+12025550100";
  private static final String SECOND_TEST_NUMBER = "+12025550102";
  private static final String CONTACT_NUMBER = "+12025550101";
  private static final String TEST_CONTACT_NAME = "Dialer Out of Process Test Contact";
  private static final String END_CALL_DESCRIPTION = "incall_content_description_end_call";
  private static final String RECORD_BUTTON_DESCRIPTION = "onscreenCallRecordText";
  private static final String STOP_RECORD_BUTTON_DESCRIPTION = "onscreenStopCallRecordText";
  private static final long TIMEOUT_MILLIS = 10000;
  private static final long RECORDING_STABILITY_MILLIS = 3000;
  private static final long ERROR_DIALOG_WAIT_MILLIS = 3000;

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> cases() {
    // Keep this as a targeted behavior matrix, not a full cross product. These rows cover the
    // restart semantics call recording policy sees: package stop, com.android.dialer process crash,
    // incallui process crash, and active automatic recording recovery.
    return Arrays.asList(
        new Object[][] {
          {
            new CaseSpec(
                CallKind.INCOMING_NON_CONTACT,
                AutoRecordPolicy.NON_CONTACTS,
                DialerStopMode.PROCESS_KILL)
          },
          {
            new CaseSpec(
                CallKind.OUTGOING_NON_CONTACT,
                AutoRecordPolicy.NON_CONTACTS,
                DialerStopMode.PROCESS_KILL)
          },
          {
            new CaseSpec(
                CallKind.OUTGOING_NON_CONTACT,
                AutoRecordPolicy.NON_CONTACTS,
                DialerStopMode.FORCE_STOP)
          },
          {
            new CaseSpec(
                CallKind.OUTGOING_NON_CONTACT,
                AutoRecordPolicy.NON_CONTACTS,
                DialerStopMode.DIALER_CRASH)
          },
          {
            new CaseSpec(
                CallKind.OUTGOING_NON_CONTACT,
                AutoRecordPolicy.NON_CONTACTS,
                DialerStopMode.INCALLUI_CRASH)
          },
          {
            new CaseSpec(
                CallKind.OUTGOING_NON_CONTACT,
                AutoRecordPolicy.NON_CONTACTS,
                DialerStopMode.PROCESS_KILL,
                RestartExpectation.ACTIVE_AUTOMATIC_RECORDING_RESTARTS)
          },
          {
            new CaseSpec(
                CallKind.OUTGOING_NON_CONTACT,
                AutoRecordPolicy.NON_CONTACTS,
                DialerStopMode.FORCE_STOP,
                RestartExpectation.ACTIVE_AUTOMATIC_RECORDING_RESTARTS)
          },
          {
            new CaseSpec(
                CallKind.OUTGOING_NON_CONTACT,
                AutoRecordPolicy.NON_CONTACTS,
                DialerStopMode.DIALER_CRASH,
                RestartExpectation.ACTIVE_AUTOMATIC_RECORDING_RESTARTS)
          },
          {
            new CaseSpec(
                CallKind.OUTGOING_NON_CONTACT,
                AutoRecordPolicy.NON_CONTACTS,
                DialerStopMode.INCALLUI_CRASH,
                RestartExpectation.ACTIVE_AUTOMATIC_RECORDING_RESTARTS)
          },
          {
            new CaseSpec(
                CallKind.PRIVATE_INCOMING,
                AutoRecordPolicy.NON_CONTACTS,
                DialerStopMode.FORCE_STOP)
          },
          {
            new CaseSpec(
                CallKind.PRIVATE_INCOMING,
                AutoRecordPolicy.NON_CONTACTS,
                DialerStopMode.INCALLUI_CRASH)
          },
          {
            new CaseSpec(
                CallKind.PRIVATE_INCOMING,
                AutoRecordPolicy.NON_CONTACTS,
                DialerStopMode.FORCE_STOP,
                RestartExpectation.ACTIVE_AUTOMATIC_RECORDING_RESTARTS)
          },
          {
            new CaseSpec(
                CallKind.PRIVATE_INCOMING,
                AutoRecordPolicy.NON_CONTACTS,
                DialerStopMode.INCALLUI_CRASH,
                RestartExpectation.ACTIVE_AUTOMATIC_RECORDING_RESTARTS)
          },
          {
            new CaseSpec(
                CallKind.CONFERENCE_NON_CONTACTS,
                AutoRecordPolicy.NON_CONTACTS,
                DialerStopMode.INCALLUI_CRASH)
          },
          {
            new CaseSpec(
                CallKind.OUTGOING_CONTACT,
                AutoRecordPolicy.NON_CONTACTS,
                DialerStopMode.DIALER_CRASH)
          },
          {
            new CaseSpec(
                CallKind.OUTGOING_NON_CONTACT,
                AutoRecordPolicy.DISABLED,
                DialerStopMode.FORCE_STOP)
          },
          {
            new CaseSpec(
                CallKind.OUTGOING_NON_CONTACT,
                AutoRecordPolicy.DISABLED,
                DialerStopMode.DIALER_CRASH)
          },
          {
            new CaseSpec(
                CallKind.PRIVATE_INCOMING,
                AutoRecordPolicy.DISABLED,
                DialerStopMode.FORCE_STOP)
          },
          {
            new CaseSpec(
                CallKind.PRIVATE_INCOMING,
                AutoRecordPolicy.DISABLED,
                DialerStopMode.DIALER_CRASH)
          }
        });
  }

  private Instrumentation instrumentation;
  private Context testContext;
  private Context dialerContext;
  private TelecomManager telecomManager;
  private UiDevice device;
  private PhoneAccountHandle phoneAccountHandle;
  private String previousDefaultDialer;
  private boolean savedPreferences;
  private final CaseSpec caseSpec;
  private final List<Uri> contactsCreatedByTest = new ArrayList<>();
  private final Map<String, Boolean> originalDialerPermissionState = new HashMap<>();
  private final Map<String, Boolean> originalTestPermissionState = new HashMap<>();

  public AutoCallRecordingProcessRestartTest(CaseSpec caseSpec) {
    this.caseSpec = caseSpec;
  }

  @Before
  public void setUp() throws Exception {
    instrumentation = InstrumentationRegistry.getInstrumentation();
    testContext = instrumentation.getContext();
    device = UiDevice.getInstance(instrumentation);
    device.wakeUp();
    assumeTrue(isUserUnlocked());
    KeyguardManager keyguardManager = testContext.getSystemService(KeyguardManager.class);
    assumeTrue(keyguardManager == null || !keyguardManager.isKeyguardLocked());

    dialerContext = testContext.createPackageContext(DIALER_PACKAGE, 0);
    telecomManager = testContext.getSystemService(TelecomManager.class);
    assumeTrue(telecomManager != null);
    assumeTrue(hasTelecomFeature());

    previousDefaultDialer = shell("telecom get-default-dialer").trim();
    shell("telecom set-default-dialer " + DIALER_PACKAGE);
    waitUntil(
        "DialerForTesting to become the default dialer",
        () -> DIALER_PACKAGE.equals(shell("telecom get-default-dialer").trim()));

    grantTestPermission(Manifest.permission.ANSWER_PHONE_CALLS);
    grantTestPermission(Manifest.permission.CALL_PHONE);
    grantTestPermission(Manifest.permission.READ_CONTACTS);
    grantTestPermission(Manifest.permission.READ_PHONE_STATE);
    grantTestPermission(Manifest.permission.WRITE_CONTACTS);
    grantDialerPermission(Manifest.permission.RECORD_AUDIO);
    grantDialerPermission(Manifest.permission.READ_CONTACTS);
    assumeTrue(numberIsNotInContacts(TEST_NUMBER));
    if (caseSpec.callKind == CallKind.CONFERENCE_NON_CONTACTS) {
      assumeTrue(numberIsNotInContacts(SECOND_TEST_NUMBER));
    }
    if (caseSpec.callKind == CallKind.OUTGOING_CONTACT) {
      insertLocalContact(TEST_CONTACT_NAME, CONTACT_NUMBER);
    }

    saveCallRecordingPreferences();
    seedCallRecordingPreferences(caseSpec.autoRecordPolicy);

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
    waitOnTelecomHandlers();
  }

  @After
  public void tearDown() throws Exception {
    if (instrumentation == null) {
      return;
    }
    if (hasTelecomFeature()) {
      shell("telecom cleanup-stuck-calls");
      waitOnTelecomHandlers();
    }
    if (phoneAccountHandle != null) {
      unregisterTestPhoneAccount();
    }
    if (savedPreferences) {
      restoreCallRecordingPreferences();
    }
    cleanupContactsCreatedByTest();
    if (previousDefaultDialer != null && !previousDefaultDialer.isEmpty()) {
      shell("telecom set-default-dialer " + previousDefaultDialer);
    }
    restoreDialerPermissions();
    restoreTestPermissions();
    shell("am force-stop " + DIALER_PACKAGE);
  }

  @Test
  public void automaticRecordingAfterDialerRestartMatchesPolicyAndUserChoice() throws Exception {
    startCall(caseSpec.callKind);
    assertAutomaticRecordingAfterDialerRestartMatchesPolicyAndUserChoice();
  }

  private void assertAutomaticRecordingAfterDialerRestartMatchesPolicyAndUserChoice()
      throws Exception {
    showInCallScreen();
    waitForInCallScreen();
    if (caseSpec.expectsAutomaticRecording()) {
      waitForRecordButton(STOP_RECORD_BUTTON_DESCRIPTION);
      if (caseSpec.restartExpectation == RestartExpectation.STOPPED_RECORDING_STAYS_OFF) {
        clickRecordButton(STOP_RECORD_BUTTON_DESCRIPTION);
        waitForRecordButton(RECORD_BUTTON_DESCRIPTION);
        assertRecordButtonStaysAbsent(STOP_RECORD_BUTTON_DESCRIPTION);
      }
    } else {
      assertRecordButtonStaysAbsent(STOP_RECORD_BUTTON_DESCRIPTION);
    }

    stopDialer(caseSpec.stopMode);
    waitOnTelecomHandlers();
    showInCallScreen();
    waitForInCallScreen();

    if (caseSpec.restartExpectation == RestartExpectation.ACTIVE_AUTOMATIC_RECORDING_RESTARTS) {
      waitForRecordButton(STOP_RECORD_BUTTON_DESCRIPTION);
      return;
    }
    if (caseSpec.shouldWaitForRecordButtonAfterRestart()) {
      // Wait until the call recording control has been rebuilt after restart; otherwise absence of
      // the Stop button could be an uninitialized UI rather than the expected stopped state.
      waitForAnyRecordButton();
    }
    assertRecordButtonStaysAbsent(STOP_RECORD_BUTTON_DESCRIPTION);
  }

  @SuppressWarnings("deprecation")
  private void answerIncomingCall() throws Exception {
    telecomManager.acceptRingingCall();
    waitOnTelecomHandlers();
  }

  private void showInCallScreen() throws Exception {
    telecomManager.showInCallScreen(false /* showDialpad */);
    waitOnTelecomHandlers();
  }

  private void startCall(CallKind callKind) throws Exception {
    switch (callKind) {
      case INCOMING_NON_CONTACT:
        addIncomingCall(TEST_NUMBER);
        answerIncomingCall();
        return;
      case PRIVATE_INCOMING:
        addPrivateIncomingCall();
        answerIncomingCall();
        return;
      case OUTGOING_NON_CONTACT:
        placeOutgoingCall(TEST_NUMBER);
        return;
      case OUTGOING_CONTACT:
        placeOutgoingCall(CONTACT_NUMBER);
        return;
      case CONFERENCE_NON_CONTACTS:
        startConferenceCall();
        return;
    }
    throw new AssertionError(callKind);
  }

  private void startConferenceCall() throws Exception {
    addIncomingCall(TEST_NUMBER);
    answerIncomingCall();
    showInCallScreen();
    waitForRecordButton(STOP_RECORD_BUTTON_DESCRIPTION);

    addIncomingCall(SECOND_TEST_NUMBER);
    answerIncomingCall();
    mergeActiveCallForTesting();

    waitForRecordButton(RECORD_BUTTON_DESCRIPTION);
    assertRecordButtonStaysAbsent(STOP_RECORD_BUTTON_DESCRIPTION);
  }

  private void stopDialer(DialerStopMode stopMode) throws Exception {
    switch (stopMode) {
      case PROCESS_KILL:
        killDialerProcesses();
        return;
      case FORCE_STOP:
        forceStopDialerPackage();
        return;
      case DIALER_CRASH:
        crashDialerProcess();
        return;
      case INCALLUI_CRASH:
        crashIncalluiProcess();
        return;
    }
    throw new AssertionError(stopMode);
  }

  private void killDialerProcesses() throws Exception {
    String pidsBefore = dialerProcessIds();
    assertWithMessage("Dialer process should be running before killing it")
        .that(pidsBefore)
        .isNotEmpty();
    shell(
        "for p in "
            + INCALLUI_PROCESS
            + " "
            + DIALER_PACKAGE
            + "; do pid=\"$(pidof $p 2>/dev/null)\"; "
            + "if [ -n \"$pid\" ]; then kill -9 $pid; fi; done");
    Thread.sleep(500);
  }

  private void forceStopDialerPackage() throws Exception {
    String pidsBefore = dialerProcessIds();
    assertWithMessage("Dialer process should be running before force stop")
        .that(pidsBefore)
        .isNotEmpty();
    shell("am force-stop " + DIALER_PACKAGE);
    Thread.sleep(500);
  }

  private void crashDialerProcess() throws Exception {
    String dialerPidBefore = processIds(DIALER_PACKAGE);
    assertWithMessage("com.android.dialer process should be running before crash")
        .that(dialerPidBefore)
        .isNotEmpty();
    String incalluiPidBefore = processIds(INCALLUI_PROCESS);
    shell(
        targetBroadcastCommand(
            ACTION_CRASH_DIALER_FOR_TESTING, OUT_OF_PROCESS_DIALER_CRASH_RECEIVER_CLASS));
    waitForProcessIdToChangeAfterCrash(
        "com.android.dialer process to crash", DIALER_PACKAGE, dialerPidBefore);
    if (!incalluiPidBefore.isEmpty()) {
      assertWithMessage("incallui process should stay running during com.android.dialer crash")
          .that(processIds(INCALLUI_PROCESS))
          .isEqualTo(incalluiPidBefore);
    }
    dismissAppErrorDialogIfPresent();
  }

  private void crashIncalluiProcess() throws Exception {
    String incalluiPidBefore = processIds(INCALLUI_PROCESS);
    assertWithMessage("incallui process should be running before crash")
        .that(incalluiPidBefore)
        .isNotEmpty();
    String dialerPidBefore = processIds(DIALER_PACKAGE);
    assertWithMessage("com.android.dialer process should be running before incallui crash")
        .that(dialerPidBefore)
        .isNotEmpty();
    shell(
        targetBroadcastCommand(
            ACTION_CRASH_INCALLUI_FOR_TESTING, OUT_OF_PROCESS_INCALLUI_CRASH_RECEIVER_CLASS));
    waitForProcessIdToChangeAfterCrash(
        "incallui process to crash", INCALLUI_PROCESS, incalluiPidBefore);
    assertWithMessage("com.android.dialer process should stay running during incallui crash")
        .that(processIds(DIALER_PACKAGE))
        .isEqualTo(dialerPidBefore);
    dismissAppErrorDialogIfPresent();
  }

  private String dialerProcessIds() throws Exception {
    return shell("pidof " + INCALLUI_PROCESS + "; pidof " + DIALER_PACKAGE).trim();
  }

  private String processIds(String processName) throws Exception {
    return shell("pidof " + processName).trim();
  }

  private void waitForProcessIdToChangeAfterCrash(
      String description, String processName, String pidBefore) throws Exception {
    long deadlineMillis = System.currentTimeMillis() + TIMEOUT_MILLIS;
    while (System.currentTimeMillis() < deadlineMillis) {
      if (!pidBefore.equals(processIds(processName))) {
        return;
      }
      dismissAppErrorDialogIfPresent(100);
      Thread.sleep(50);
    }
    dismissAppErrorDialogIfPresent(1000);
    assertWithMessage("Timed out waiting for %s", description)
        .that(pidBefore.equals(processIds(processName)))
        .isFalse();
  }

  private void dismissAppErrorDialogIfPresent() {
    dismissAppErrorDialogIfPresent(ERROR_DIALOG_WAIT_MILLIS);
  }

  private void dismissAppErrorDialogIfPresent(long timeoutMillis) {
    UiObject2 closeButton =
        device.wait(Until.findObject(By.res("android:id/aerr_close")), timeoutMillis);
    if (closeButton != null) {
      closeButton.click();
      device.waitForIdle();
    }
  }

  private void clickRecordButton(String descriptionResourceName) {
    waitForRecordButton(descriptionResourceName).click();
    device.waitForIdle();
  }

  private UiObject2 waitForRecordButton(String descriptionResourceName) {
    return waitForUiObject(recordButtonSelector(descriptionResourceName), descriptionResourceName);
  }

  private UiObject2 waitForAnyRecordButton() {
    String recordDescription = dialerString(RECORD_BUTTON_DESCRIPTION);
    String stopDescription = dialerString(STOP_RECORD_BUTTON_DESCRIPTION);
    return waitForUiObject(
        By.pkg(DIALER_PACKAGE)
            .desc(
                Pattern.compile(
                    Pattern.quote(recordDescription) + "|" + Pattern.quote(stopDescription))),
        "call recording button");
  }

  private UiObject2 waitForInCallScreen() {
    return waitForUiObject(
        By.pkg(DIALER_PACKAGE).desc(dialerString(END_CALL_DESCRIPTION)), "incall screen");
  }

  private void assertRecordButtonStaysAbsent(String descriptionResourceName) throws Exception {
    BySelector selector = recordButtonSelector(descriptionResourceName);
    long deadlineMillis = System.currentTimeMillis() + RECORDING_STABILITY_MILLIS;
    while (System.currentTimeMillis() < deadlineMillis) {
      assertWithMessage("%s should stay absent", descriptionResourceName)
          .that(device.findObject(selector))
          .isNull();
      Thread.sleep(50);
    }
  }

  private BySelector recordButtonSelector(String descriptionResourceName) {
    return By.pkg(DIALER_PACKAGE).desc(dialerString(descriptionResourceName));
  }

  private UiObject2 waitForUiObject(BySelector selector, String description) {
    UiObject2 object = device.wait(Until.findObject(selector), TIMEOUT_MILLIS);
    assertWithMessage("UI object should appear: %s", description).that(object).isNotNull();
    return object;
  }

  private String dialerString(String name) {
    int id = dialerContext.getResources().getIdentifier(name, "string", DIALER_PACKAGE);
    assertWithMessage("Dialer string resource should exist: %s", name).that(id).isNotEqualTo(0);
    return dialerContext.getString(id);
  }

  private void addIncomingCall(String number) throws Exception {
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

  private void addPrivateIncomingCall() throws Exception {
    shell(
        "am broadcast -a "
            + ACTION_ADD_INCOMING_CALL
            + " -n "
            + CONNECTION_SERVICE_PACKAGE
            + "/"
            + CONNECTION_RECEIVER_CLASS
            + " --es account_id "
            + ACCOUNT_ID
            + " --ei "
            + EXTRA_PRESENTATION
            + " "
            + TelecomManager.PRESENTATION_RESTRICTED);
    waitOnTelecomHandlers();
  }

  private void placeOutgoingCall(String number) throws Exception {
    Bundle extras = new Bundle();
    extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle);
    telecomManager.placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), extras);
    waitOnTelecomHandlers();
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
    if (phoneAccountHandle == null) {
      return;
    }
    shell(
        "telecom unregister-phone-account "
            + phoneAccountHandle.getComponentName().flattenToString()
            + " "
            + phoneAccountHandle.getId()
            + " "
            + userSerial(phoneAccountHandle));
  }

  private void saveCallRecordingPreferences() throws Exception {
    shell(targetBroadcastCommand(ACTION_SAVE_CALL_RECORDING_PREFERENCES));
    savedPreferences = true;
  }

  private void restoreCallRecordingPreferences() throws Exception {
    shell(targetBroadcastCommand(ACTION_RESTORE_CALL_RECORDING_PREFERENCES));
    savedPreferences = false;
  }

  private void seedAutoRecordNonContacts() throws Exception {
    shell(targetBroadcastCommand(ACTION_SEED_AUTO_RECORD_NON_CONTACTS));
  }

  private void seedRecordingSwitchWithoutAutomaticRules() throws Exception {
    shell(targetBroadcastCommand(ACTION_SEED_RECORDING_SWITCH_WITHOUT_AUTOMATIC_RULES));
  }

  private void mergeActiveCallForTesting() throws Exception {
    shell(targetBroadcastCommand(ACTION_MERGE_ACTIVE_CALL_FOR_TESTING));
    waitOnTelecomHandlers();
  }

  private void seedCallRecordingPreferences(AutoRecordPolicy autoRecordPolicy) throws Exception {
    switch (autoRecordPolicy) {
      case NON_CONTACTS:
        seedAutoRecordNonContacts();
        return;
      case DISABLED:
        seedRecordingSwitchWithoutAutomaticRules();
        return;
    }
    throw new AssertionError(autoRecordPolicy);
  }

  private String targetBroadcastCommand(String action) {
    return targetBroadcastCommand(action, OUT_OF_PROCESS_COMMAND_RECEIVER_CLASS);
  }

  private String targetBroadcastCommand(String action, String receiverClass) {
    return "am broadcast --include-stopped-packages -a "
        + action
        + " -n "
        + DIALER_PACKAGE
        + "/"
        + receiverClass;
  }

  private boolean hasTelecomFeature() {
    PackageManager packageManager = testContext.getPackageManager();
    return packageManager.hasSystemFeature(PackageManager.FEATURE_TELECOM)
        || packageManager.hasSystemFeature(PackageManager.FEATURE_CONNECTION_SERVICE);
  }

  private boolean isUserUnlocked() {
    UserManager userManager = testContext.getSystemService(UserManager.class);
    return userManager == null || userManager.isUserUnlocked();
  }

  private boolean numberIsNotInContacts(String number) {
    Uri uri =
        Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
    try (Cursor cursor =
        testContext
            .getContentResolver()
            .query(uri, new String[] {ContactsContract.PhoneLookup._ID}, null, null, null)) {
      return cursor == null || !cursor.moveToFirst();
    }
  }

  private void insertLocalContact(String displayName, String phoneNumber) throws Exception {
    ContentResolver resolver = testContext.getContentResolver();
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

    waitUntil(
        "contact " + phoneNumber + " to become visible", () -> !numberIsNotInContacts(phoneNumber));
  }

  private void cleanupContactsCreatedByTest() {
    if (testContext == null) {
      return;
    }
    ContentResolver resolver = testContext.getContentResolver();
    for (Uri rawContactUri : contactsCreatedByTest) {
      resolver.delete(rawContactUri, null, null);
    }
    contactsCreatedByTest.clear();
  }

  private long userSerial(PhoneAccountHandle handle) {
    UserManager userManager = testContext.getSystemService(UserManager.class);
    return userManager.getSerialNumberForUser(handle.getUserHandle());
  }

  private void waitOnTelecomHandlers() throws Exception {
    shell("telecom wait-on-handlers");
  }

  private void grantDialerPermission(String permission) {
    if (!originalDialerPermissionState.containsKey(permission)) {
      originalDialerPermissionState.put(permission, isDialerPermissionGranted(permission));
    }
    if (isDialerPermissionGranted(permission)) {
      return;
    }
    instrumentation
        .getUiAutomation()
        .grantRuntimePermissionAsUser(DIALER_PACKAGE, permission, UserHandle.CURRENT);
    assertThat(isDialerPermissionGranted(permission)).isTrue();
  }

  private boolean isDialerPermissionGranted(String permission) {
    return testContext.getPackageManager().checkPermission(permission, DIALER_PACKAGE)
        == PackageManager.PERMISSION_GRANTED;
  }

  private void restoreDialerPermissions() {
    UiAutomation uiAutomation = instrumentation.getUiAutomation();
    for (Map.Entry<String, Boolean> entry : originalDialerPermissionState.entrySet()) {
      if (entry.getValue()) {
        uiAutomation.grantRuntimePermissionAsUser(
            DIALER_PACKAGE, entry.getKey(), UserHandle.CURRENT);
      } else {
        uiAutomation.revokeRuntimePermissionAsUser(
            DIALER_PACKAGE, entry.getKey(), UserHandle.CURRENT);
      }
    }
    originalDialerPermissionState.clear();
  }

  private void grantTestPermission(String permission) {
    if (!originalTestPermissionState.containsKey(permission)) {
      originalTestPermissionState.put(
          permission,
          testContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED);
    }
    if (testContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
      return;
    }
    instrumentation
        .getUiAutomation()
        .grantRuntimePermissionAsUser(testContext.getPackageName(), permission, UserHandle.CURRENT);
    assertThat(testContext.checkSelfPermission(permission))
        .isEqualTo(PackageManager.PERMISSION_GRANTED);
  }

  private void restoreTestPermissions() {
    UiAutomation uiAutomation = instrumentation.getUiAutomation();
    for (Map.Entry<String, Boolean> entry : originalTestPermissionState.entrySet()) {
      if (entry.getValue()) {
        uiAutomation.grantRuntimePermissionAsUser(
            testContext.getPackageName(), entry.getKey(), UserHandle.CURRENT);
      } else {
        uiAutomation.revokeRuntimePermissionAsUser(
            testContext.getPackageName(), entry.getKey(), UserHandle.CURRENT);
      }
    }
    originalTestPermissionState.clear();
  }

  private static void waitUntil(String description, BooleanSupplier condition) throws Exception {
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

  private String shell(String command) throws Exception {
    ParcelFileDescriptor fd = instrumentation.getUiAutomation().executeShellCommand(command);
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

  private interface BooleanSupplier {
    boolean getAsBoolean() throws Exception;
  }

  private enum DialerStopMode {
    PROCESS_KILL("process kill"),
    FORCE_STOP("force stop"),
    DIALER_CRASH("com.android.dialer crash"),
    INCALLUI_CRASH("incallui crash");

    final String description;

    DialerStopMode(String description) {
      this.description = description;
    }
  }

  private enum CallKind {
    INCOMING_NON_CONTACT("incoming non-contact call"),
    PRIVATE_INCOMING("private incoming call"),
    OUTGOING_NON_CONTACT("outgoing non-contact call"),
    OUTGOING_CONTACT("outgoing contact call"),
    CONFERENCE_NON_CONTACTS("conference call with non-contact numbers");

    final String description;

    CallKind(String description) {
      this.description = description;
    }
  }

  private enum AutoRecordPolicy {
    NON_CONTACTS("non-contacts automatic recording"),
    DISABLED("automatic recording disabled");

    final String description;

    AutoRecordPolicy(String description) {
      this.description = description;
    }
  }

  private enum RestartExpectation {
    STOPPED_RECORDING_STAYS_OFF,
    AUTOMATIC_RECORDING_STAYS_OFF,
    ACTIVE_AUTOMATIC_RECORDING_RESTARTS
  }

  private static final class CaseSpec {
    final CallKind callKind;
    final AutoRecordPolicy autoRecordPolicy;
    final DialerStopMode stopMode;
    final RestartExpectation restartExpectation;

    CaseSpec(
        CallKind callKind,
        AutoRecordPolicy autoRecordPolicy,
        DialerStopMode stopMode) {
      this(
          callKind,
          autoRecordPolicy,
          stopMode,
          defaultRestartExpectation(callKind, autoRecordPolicy));
    }

    CaseSpec(
        CallKind callKind,
        AutoRecordPolicy autoRecordPolicy,
        DialerStopMode stopMode,
        RestartExpectation restartExpectation) {
      this.callKind = callKind;
      this.autoRecordPolicy = autoRecordPolicy;
      this.stopMode = stopMode;
      this.restartExpectation = restartExpectation;
    }

    boolean expectsAutomaticRecording() {
      return expectsAutomaticRecording(callKind, autoRecordPolicy);
    }

    private static RestartExpectation defaultRestartExpectation(
        CallKind callKind, AutoRecordPolicy autoRecordPolicy) {
      return expectsAutomaticRecording(callKind, autoRecordPolicy)
          ? RestartExpectation.STOPPED_RECORDING_STAYS_OFF
          : RestartExpectation.AUTOMATIC_RECORDING_STAYS_OFF;
    }

    private static boolean expectsAutomaticRecording(
        CallKind callKind, AutoRecordPolicy autoRecordPolicy) {
      return autoRecordPolicy == AutoRecordPolicy.NON_CONTACTS
          && callKind != CallKind.OUTGOING_CONTACT
          && callKind != CallKind.CONFERENCE_NON_CONTACTS;
    }

    boolean shouldWaitForRecordButtonAfterRestart() {
      // Conference calls never auto start, but the manual record button still proves call controls
      // returned before asserting the Stop button is absent.
      return restartExpectation == RestartExpectation.STOPPED_RECORDING_STAYS_OFF
          || callKind == CallKind.CONFERENCE_NON_CONTACTS;
    }

    @Override
    public String toString() {
      switch (restartExpectation) {
        case STOPPED_RECORDING_STAYS_OFF:
          return "user stopped automatic recording for "
              + callKind.description
              + " stays stopped after "
              + stopMode.description;
        case AUTOMATIC_RECORDING_STAYS_OFF:
          return autoRecordPolicy.description
              + " for "
              + callKind.description
              + " stays off after "
              + stopMode.description;
        case ACTIVE_AUTOMATIC_RECORDING_RESTARTS:
          return "active automatic recording for "
              + callKind.description
              + " restarts after "
              + stopMode.description;
      }
      throw new AssertionError(restartExpectation);
    }
  }
}
