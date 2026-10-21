package com.android.dialer.outofprocess.target;

import android.app.Application;
import android.content.Intent;
import android.os.Process;

final class DialerOutOfProcessCrashSupport {
  private DialerOutOfProcessCrashSupport() {}

  static void crash(
      Intent intent, String expectedAction, String expectedProcess, String processLabel) {
    if (!expectedAction.equals(intent.getAction())) {
      throw new IllegalArgumentException(
          "Unknown Dialer out of process crash command: " + intent.getAction());
    }
    String processName = Application.getProcessName();
    if (!expectedProcess.equals(processName)) {
      throw new IllegalStateException(
          "Expected " + expectedProcess + " process, was " + processName);
    }
    throw new RuntimeException(
        "Forced "
            + processLabel
            + " crash for out of process test in "
            + processName
            + " pid="
            + Process.myPid());
  }
}
