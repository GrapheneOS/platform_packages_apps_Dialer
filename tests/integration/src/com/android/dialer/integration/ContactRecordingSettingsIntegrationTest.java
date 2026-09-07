package com.android.dialer.integration;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.WindowManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import com.android.dialer.app.R;
import com.android.dialer.app.settings.AutoCallRecordingSelectedNumbersActivity;
import com.android.dialer.callrecord.CallRecordingPreferences;
import com.android.dialer.callrecord.CallRecordingPreferencesStore;
import com.android.dialer.callrecord.ContactRecordingMode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ContactRecordingSettingsIntegrationTest {
  private static final String NUMBER = "+15551230001";
  private static final long TIMEOUT_MILLIS = 5000;
  private Context context;
  private UiDevice device;
  private CallRecordingPreferences originalPreferences;

  @Before
  public void setUp() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    device.wakeUp();
    device.executeShellCommand("wm dismiss-keyguard");
    KeyguardManager keyguardManager = context.getSystemService(KeyguardManager.class);
    long deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS;
    while (keyguardManager.isKeyguardLocked() && SystemClock.uptimeMillis() < deadline) {
      SystemClock.sleep(50);
    }
    assertWithMessage("Unlock the device before running settings UI tests")
        .that(keyguardManager.isKeyguardLocked())
        .isFalse();
    assertThat(device.isScreenOn()).isTrue();
    originalPreferences = CallRecordingPreferencesStore.readBlocking(context);
  }

  @After
  public void tearDown() {
    if (originalPreferences != null) {
      CallRecordingPreferencesStore.updateBlocking(
          context, builder -> builder.clear().mergeFrom(originalPreferences));
    }
  }

  @Test
  public void cancelPreservesSelectedNumbersAndMode() {
    seed(ContactRecordingMode.SELECTED_NUMBERS, true);
    try (ActivityScenario<AutoCallRecordingSelectedNumbersActivity> scenario = launch()) {
      chooseMode(R.string.call_recording_contact_mode_all);
      waitForText(R.string.call_recording_change_mode_title);
      clickDialogButton(android.R.id.button2);

      assertThat(preferences().getContactRecordingMode())
          .isEqualTo(ContactRecordingMode.SELECTED_NUMBERS);
      assertThat(preferences().getAutoRecordSelectedNumbersList()).containsExactly(NUMBER);
    }
  }

  @Test
  public void switchingModesDiscardsSavedNumbersOnlyAfterConfirmation() {
    for (ContactRecordingMode from :
        new ContactRecordingMode[] {
          ContactRecordingMode.SELECTED_NUMBERS, ContactRecordingMode.ALL_EXCEPT_SELECTED_NUMBERS
        }) {
      seed(from, true);
      boolean switchToAll = from == ContactRecordingMode.SELECTED_NUMBERS;
      try (ActivityScenario<AutoCallRecordingSelectedNumbersActivity> scenario = launch()) {
        chooseMode(
            switchToAll
                ? R.string.call_recording_contact_mode_all
                : R.string.call_recording_contact_mode_selected);
        waitForText(R.string.call_recording_change_mode_title);
        assertThat(preferences().getContactRecordingMode()).isEqualTo(from);
        assertThat(preferences().getAutoRecordSelectedNumbersList()).containsExactly(NUMBER);
        clickDialogButton(android.R.id.button1);
        waitForText(
            switchToAll
                ? R.string.call_recording_all_contacts_empty
                : R.string.call_recording_auto_record_selected_numbers_empty);

        assertThat(preferences().getAutoRecordSelectedNumbersList()).isEmpty();
        assertThat(preferences().getContactRecordingMode())
            .isEqualTo(
                switchToAll
                    ? ContactRecordingMode.ALL_EXCEPT_SELECTED_NUMBERS
                    : ContactRecordingMode.SELECTED_NUMBERS);
        assertThat(preferences().getAutoRecordContactsEnabled()).isFalse();
      }
    }
  }

  @Test
  public void emptyListSwitchesWithoutDiscardDialog() {
    seed(ContactRecordingMode.SELECTED_NUMBERS, false);
    try (ActivityScenario<AutoCallRecordingSelectedNumbersActivity> scenario = launch()) {
      chooseMode(R.string.call_recording_contact_mode_all);
      waitForText(R.string.call_recording_all_contacts_empty);

      assertThat(
              device.hasObject(
                  By.text(context.getString(R.string.call_recording_change_mode_title))))
          .isFalse();
      assertThat(preferences().getContactRecordingMode())
          .isEqualTo(ContactRecordingMode.ALL_EXCEPT_SELECTED_NUMBERS);
    }
  }

  @Test
  public void backDismissesConfirmationWithoutChangingExceptions() {
    seed(ContactRecordingMode.ALL_EXCEPT_SELECTED_NUMBERS, true);
    try (ActivityScenario<AutoCallRecordingSelectedNumbersActivity> scenario = launch()) {
      chooseMode(R.string.call_recording_contact_mode_selected);
      waitForText(R.string.call_recording_change_mode_title);
      device.pressBack();
      device.waitForIdle();

      assertThat(preferences().getContactRecordingMode())
          .isEqualTo(ContactRecordingMode.ALL_EXCEPT_SELECTED_NUMBERS);
      assertThat(preferences().getAutoRecordSelectedNumbersList()).containsExactly(NUMBER);
    }
  }

  private void seed(ContactRecordingMode mode, boolean withNumber) {
    CallRecordingPreferencesStore.updateBlocking(
        context,
        builder -> {
          builder
              .setAutoRecordContactsEnabled(false)
              .setContactRecordingMode(mode)
              .clearAutoRecordSelectedNumbers();
          if (withNumber) {
            builder.addAutoRecordSelectedNumbers(NUMBER);
          }
        });
  }

  private ActivityScenario<AutoCallRecordingSelectedNumbersActivity> launch() {
    ActivityScenario<AutoCallRecordingSelectedNumbersActivity> scenario =
        ActivityScenario.launch(
            new Intent(context, AutoCallRecordingSelectedNumbersActivity.class));
    scenario.onActivity(
        activity -> activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
    return scenario;
  }

  private CallRecordingPreferences preferences() {
    return CallRecordingPreferencesStore.readBlocking(context);
  }

  private void chooseMode(int label) {
    click(R.string.call_recording_contact_mode_title);
    click(label);
  }

  private void click(int text) {
    waitForText(text).click();
    device.waitForIdle();
  }

  private UiObject2 waitForText(int text) {
    UiObject2 view =
        device.wait(Until.findObject(By.text(context.getString(text))), TIMEOUT_MILLIS);
    assertThat(view).isNotNull();
    return view;
  }

  private void clickDialogButton(int buttonId) {
    UiObject2 button =
        device.wait(
            Until.findObject(
                By.res(context.getResources().getResourceName(buttonId))
                    .pkg(context.getPackageName())),
            TIMEOUT_MILLIS);
    assertThat(button).isNotNull();
    button.click();
    device.waitForIdle();
  }
}
