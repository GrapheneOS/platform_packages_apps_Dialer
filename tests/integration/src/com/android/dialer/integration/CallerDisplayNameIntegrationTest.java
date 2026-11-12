package com.android.dialer.integration;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.KeyguardManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import com.android.incallui.ContactInfoCache;
import com.android.incallui.ContactInfoCache.ContactCacheEntry;
import com.android.incallui.ContactInfoCache.ContactInfoCacheCallback;
import com.android.incallui.call.DialerCall;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Integration coverage for network-provided caller display names.
 *
 * <p>Telecom exposes the carrier's caller name as the call's caller display name. Dialer must keep
 * that value when the asynchronous contacts lookup replaces its initial caller information.
 */
@RunWith(AndroidJUnit4.class)
public final class CallerDisplayNameIntegrationTest extends AutoCallRecordingIntegrationTestBase {

  private static final String CARRIER_DISPLAY_NAME = "Spam likely";
  private static final String LOCAL_CONTACT_NAME = "Local contact";
  private static final long DISPLAY_NAME_STABILITY_MILLIS = 1000;

  private UiDevice device;

  @Before
  public void setUpUiDevice() throws Exception {
    assumeTrue(isUserUnlocked());
    device = UiDevice.getInstance(instrumentation);
    device.wakeUp();
    KeyguardManager keyguardManager = targetContext.getSystemService(KeyguardManager.class);
    assumeTrue(keyguardManager == null || !keyguardManager.isKeyguardLocked());
  }

  @Test
  public void carrierDisplayNameSurvivesContactLookup() throws Exception {
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    assumeTrue(numberIsNotInContacts(TEST_NUMBER));

    addIncomingCall(TEST_NUMBER, CARRIER_DISPLAY_NAME);
    DialerCall call = waitForIncomingCall(TEST_NUMBER);
    waitForCarrierDisplayName(call);

    ContactCacheEntry entry = waitForContactLookup(call);
    assertThat(entry.namePrimary).isEqualTo(CARRIER_DISPLAY_NAME);

    waitForCallNotificationText(CARRIER_DISPLAY_NAME);
    showInCallScreen();
    waitForContactName(CARRIER_DISPLAY_NAME);
    assertDisplayNameStays(CARRIER_DISPLAY_NAME);
  }

  @Test
  public void localContactNameTakesPrecedenceOverCarrierDisplayName() throws Exception {
    grantTargetPermission(Manifest.permission.READ_CONTACTS);
    grantTargetPermission(Manifest.permission.WRITE_CONTACTS);
    insertLocalContact(LOCAL_CONTACT_NAME, TEST_NUMBER);

    addIncomingCall(TEST_NUMBER, CARRIER_DISPLAY_NAME);
    DialerCall call = waitForIncomingCall(TEST_NUMBER);
    waitForCarrierDisplayName(call);

    ContactCacheEntry entry = waitForContactLookup(call);
    assertThat(entry.namePrimary).isEqualTo(LOCAL_CONTACT_NAME);
  }

  private void waitForCarrierDisplayName(DialerCall call) throws Exception {
    waitUntil(
        "carrier display name to reach Dialer",
        () -> CARRIER_DISPLAY_NAME.equals(call.getCnapName()));
  }

  private ContactCacheEntry waitForContactLookup(DialerCall call) throws Exception {
    AtomicReference<ContactCacheEntry> result = new AtomicReference<>();
    instrumentation.runOnMainSync(() ->
        ContactInfoCache.getInstance(targetContext)
            .findInfo(
                call,
                true /* isIncoming */,
                new ContactInfoCacheCallback() {
                  @Override
                  public void onContactInfoComplete(String callId, ContactCacheEntry entry) {
                    if (!entry.hasPendingContactLookup()) {
                      result.set(entry);
                    }
                  }

                  @Override
                  public void onImageLoadComplete(String callId, ContactCacheEntry entry) {}
                }));
    waitUntil("contact lookup to complete", () -> result.get() != null);
    return result.get();
  }

  private void waitForContactName(String expectedName) {
    UiObject2 contactName =
        device.wait(Until.findObject(contactNameSelector(expectedName)), TIMEOUT_MILLIS);
    assertThat(contactName).isNotNull();
  }

  private void assertDisplayNameStays(String expectedName) throws Exception {
    long deadlineMillis = System.currentTimeMillis() + DISPLAY_NAME_STABILITY_MILLIS;
    while (System.currentTimeMillis() < deadlineMillis) {
      assertWithMessage("Call notification should keep the carrier display name")
          .that(callNotificationTexts())
          .contains(expectedName);
      UiObject2 contactName = device.findObject(contactNameSelector(expectedName));
      assertWithMessage("In-call screen should keep the carrier display name")
          .that(contactName)
          .isNotNull();
      Thread.sleep(50);
    }
  }

  private BySelector contactNameSelector(String expectedName) {
    return By.pkg(targetContext.getPackageName())
        .res(targetContext.getPackageName(), "contactgrid_contact_name")
        .text(expectedName);
  }
}
