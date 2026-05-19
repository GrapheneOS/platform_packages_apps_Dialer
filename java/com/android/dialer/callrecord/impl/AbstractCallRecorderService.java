package com.android.dialer.callrecord.impl;

import android.app.Service;
import android.os.RemoteException;
import android.support.annotation.Nullable;
import android.util.Log;
import com.android.dialer.callrecord.ICallRecorderService;
import com.android.dialer.callrecord.ICallRecorderServiceCallback;

/**
 * Shared callback plumbing for recorder service implementations.
 * TODO: Remove when V1 CallRecorderService gets removed
 */
abstract class AbstractCallRecorderService extends Service {
  @Nullable private volatile ICallRecorderServiceCallback callback;

  protected abstract class RecorderServiceBinder extends ICallRecorderService.Stub {
    @Override
    public final void setCallback(ICallRecorderServiceCallback callback) {
      AbstractCallRecorderService.this.callback = callback;
    }
  }

  protected final void notifyRecordingError(String tag) {
    ICallRecorderServiceCallback currentCallback = callback;
    if (currentCallback == null) {
      return;
    }
    try {
      currentCallback.onRecordingError();
    } catch (RemoteException e) {
      Log.w(tag, "Failed to notify recording error", e);
    }
  }
}
