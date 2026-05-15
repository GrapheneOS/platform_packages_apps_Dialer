package com.android.dialer.phonenumberutil;

import android.content.Context;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;
import com.android.dialer.DialerPhoneNumber;
import com.android.dialer.location.GeoUtil;
import com.android.dialer.phonenumberproto.DialerPhoneNumberUtil;

/** Worker thread phone number canonicalization for stored number matching. */
public final class PhoneNumberCanonicalizer {

  private PhoneNumberCanonicalizer() {}

  @WorkerThread
  public static String canonicalize(Context context, @Nullable String rawNumber) {
    DialerPhoneNumberUtil util = new DialerPhoneNumberUtil();
    DialerPhoneNumber parsed = util.parse(rawNumber, GeoUtil.getCurrentCountryIso(context));
    return parsed.getNormalizedNumber();
  }
}
