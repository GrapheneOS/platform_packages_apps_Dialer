package com.android.incallui.call;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

import com.android.dialer.callrecord.ICallRecorderService;

/**
 * Android service binding for the recorder service.
 *
 * Keeping this behind CallRecorderServiceBinding lets lifecycle tests verify binding transitions
 * without binding a real service.
 */
final class DefaultCallRecorderServiceBinding implements CallRecorderServiceBinding {
  private static final String TAG = "CallRecorderServiceBinding";

  @Nullable private ServiceConnection connection;
  @Nullable private ICallRecorderService service;
  private boolean bound;

  @Override
  public boolean isBound() {
    return bound;
  }

  @Override
  @Nullable
  public ICallRecorderService getService() {
    return service;
  }

  @Override
  public boolean bind(Context context, Intent serviceIntent, Listener listener) {
    if (bound) {
      return true;
    }
    connection = createConnection(listener);
    bound = context.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
    if (!bound) {
      service = null;
      connection = null;
    }
    return bound;
  }

  @Override
  public void unbind(Context context) {
    if (bound && connection != null) {
      try {
        context.unbindService(connection);
      } catch (IllegalArgumentException e) {
        Log.w(TAG, "Failed to unbind call recorder service", e);
      }
    }
    bound = false;
    service = null;
    connection = null;
  }

  private ServiceConnection createConnection(Listener listener) {
    return new ServiceConnection() {
      @Override
      public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ICallRecorderService.Stub.asInterface(binder);
        listener.onServiceConnected();
      }

      @Override
      public void onServiceDisconnected(ComponentName name) {
        service = null;
        listener.onServiceDisconnected();
      }

      @Override
      public void onBindingDied(ComponentName name) {
        service = null;
        listener.onBindingDied();
      }
    };
  }
}
