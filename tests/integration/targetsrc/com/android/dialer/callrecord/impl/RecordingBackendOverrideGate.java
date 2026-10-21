package com.android.dialer.callrecord.impl;

// DialerForTesting replaces the production disabled gate with this enabled test target source.
final class RecordingBackendOverrideGate {
  static final boolean ALLOWED = true;

  private RecordingBackendOverrideGate() {}
}
