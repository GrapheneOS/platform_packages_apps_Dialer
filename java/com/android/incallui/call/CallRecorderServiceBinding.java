package com.android.incallui.call;

import android.content.Context;
import android.content.Intent;
import android.support.annotation.Nullable;

import com.android.dialer.callrecord.ICallRecorderService;

/**
 * Bind/unbind boundary for the recorder service. CallRecorder is still singleton-created, so tests
 * inject this instead of mutating service fields directly.
 */
interface CallRecorderServiceBinding {
  interface Listener {
    void onServiceConnected();

    void onServiceDisconnected();

    void onBindingDied();
  }

  boolean isBound();

  @Nullable
  ICallRecorderService getService();

  boolean bind(Context context, Intent serviceIntent, Listener listener);

  void unbind(Context context);
}
