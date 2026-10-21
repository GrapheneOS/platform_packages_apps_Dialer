package com.android.dialer.outofprocess.target;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Test-only receiver for crashing DialerForTesting's incallui process. */
public final class DialerOutOfProcessIncalluiCrashReceiver extends BroadcastReceiver {
  public static final String ACTION_CRASH_INCALLUI_FOR_TESTING =
      "com.android.dialer.outofprocess.CRASH_INCALLUI_FOR_TESTING";
  private static final String INCALLUI_PROCESS = "com.android.incallui";

  @Override
  public void onReceive(Context context, Intent intent) {
    DialerOutOfProcessCrashSupport.crash(
        intent, ACTION_CRASH_INCALLUI_FOR_TESTING, INCALLUI_PROCESS, "incallui");
  }
}
