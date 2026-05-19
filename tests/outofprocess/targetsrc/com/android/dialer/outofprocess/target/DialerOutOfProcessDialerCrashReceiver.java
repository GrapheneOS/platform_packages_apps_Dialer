package com.android.dialer.outofprocess.target;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Test-only receiver for crashing DialerForTesting's com.android.dialer process. */
public final class DialerOutOfProcessDialerCrashReceiver extends BroadcastReceiver {
  public static final String ACTION_CRASH_DIALER_FOR_TESTING =
      "com.android.dialer.outofprocess.CRASH_DIALER_FOR_TESTING";
  private static final String DIALER_PROCESS = "com.android.dialer";

  @Override
  public void onReceive(Context context, Intent intent) {
    DialerOutOfProcessCrashSupport.crash(
        intent, ACTION_CRASH_DIALER_FOR_TESTING, DIALER_PROCESS, "Dialer");
  }
}
