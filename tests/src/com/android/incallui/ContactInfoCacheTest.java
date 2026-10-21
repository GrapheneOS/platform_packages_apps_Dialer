package com.android.incallui;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.dialer.logging.ContactLookupResult;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ContactInfoCacheTest {

  @Test
  public void localContactFallbackPopulatesMissingNormalizedNumber() {
    CallerInfo callerInfo = new CallerInfo();
    callerInfo.phoneNumber = "+1 (650) 253-0000";
    ContactInfoCache.ContactCacheEntry cacheEntry = new ContactInfoCache.ContactCacheEntry();
    cacheEntry.contactLookupResult = ContactLookupResult.Type.LOCAL_CONTACT;

    ContactInfoCache.populateNormalizedNumber(
        InstrumentationRegistry.getInstrumentation().getTargetContext(), callerInfo, cacheEntry);

    assertThat(cacheEntry.normalizedNumber).isEqualTo("+16502530000");
  }
}
