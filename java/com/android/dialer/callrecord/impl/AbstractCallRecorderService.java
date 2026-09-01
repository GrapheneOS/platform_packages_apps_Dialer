package com.android.dialer.callrecord.impl;

import android.app.Service;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.util.Log;
import com.android.dialer.callrecord.CallRecording;
import com.android.dialer.callrecord.ICallRecorderService;
import com.android.dialer.callrecord.ICallRecorderServiceCallback;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Shared asynchronous command and callback plumbing for recorder service implementations.
 *
 * <p>Start, stop, and discard use one executor so cleanup cannot overtake startup.
 * TODO: Remove when V1 CallRecorderService gets removed
 */
abstract class AbstractCallRecorderService extends Service {
  protected static final long NO_REQUEST_ID = 0L;

  @Nullable private volatile ICallRecorderServiceCallback callback;
  @Nullable private Executor recorderCommandExecutor;
  @Nullable private ExecutorService ownedRecorderCommandExecutor;

  private final ICallRecorderService.Stub recorderServiceBinder =
      new ICallRecorderService.Stub() {
        @Override
        public void setCallback(ICallRecorderServiceCallback callback) {
          AbstractCallRecorderService.this.callback = callback;
        }

        @Override
        public void startRecording(long requestId, String phoneNumber, long creationTime) {
          if (!executeRecorderCommand(
              () -> startRecordingAndNotify(requestId, phoneNumber, creationTime))) {
            notifyRecordingStartFailed(requestId);
          }
        }

        @Override
        public void stopRecording(long requestId) {
          executeStopCommand(requestId, true /* completeRecording */);
        }

        @Override
        public void discardRecording(long requestId) {
          executeStopCommand(requestId, false /* completeRecording */);
        }
      };

  protected abstract String getLogTag();

  protected abstract boolean startRecordingInternal(
      long requestId, String phoneNumber, long creationTime);

  protected abstract void stopRecordingAsync(long requestId, boolean completeRecording);

  protected final IBinder getRecorderServiceBinder() {
    return recorderServiceBinder;
  }

  @VisibleForTesting
  final synchronized void setRecorderCommandExecutorForTesting(Executor executor) {
    if (recorderCommandExecutor != null) {
      throw new IllegalStateException("recorder command executor is already in use");
    }
    recorderCommandExecutor = executor;
  }

  protected final void finishRecorderCommands(Runnable finalCommand) {
    executeRecorderCommand(finalCommand);
    synchronized (this) {
      if (ownedRecorderCommandExecutor != null) {
        ownedRecorderCommandExecutor.shutdown();
      }
    }
  }

  private void startRecordingAndNotify(long requestId, String phoneNumber, long creationTime) {
    try {
      if (startRecordingInternal(requestId, phoneNumber, creationTime)) {
        notifyRecordingStarted(requestId);
      } else {
        notifyRecordingStartFailed(requestId);
      }
    } catch (RuntimeException e) {
      Log.w(getLogTag(), "Recorder rejected recording start", e);
      notifyRecordingStartFailed(requestId);
      try {
        stopRecordingAsync(requestId, false /* completeRecording */);
      } catch (RuntimeException cleanupFailure) {
        Log.w(getLogTag(), "Failed to clean up rejected recording start", cleanupFailure);
        notifyRecordingError(requestId);
      }
    }
  }

  private void executeStopCommand(long requestId, boolean completeRecording) {
    if (!executeRecorderCommand(
        () -> {
          try {
            stopRecordingAsync(requestId, completeRecording);
          } catch (RuntimeException e) {
            Log.w(getLogTag(), "Failed to request recording stop", e);
            notifyRecordingError(requestId);
          }
        })) {
      notifyRecordingError(requestId);
    }
  }

  private boolean executeRecorderCommand(Runnable command) {
    try {
      getRecorderCommandExecutor().execute(command);
      return true;
    } catch (RejectedExecutionException e) {
      Log.w(getLogTag(), "Recorder command rejected", e);
      return false;
    }
  }

  private synchronized Executor getRecorderCommandExecutor() {
    if (recorderCommandExecutor == null) {
      ownedRecorderCommandExecutor = Executors.newSingleThreadExecutor();
      recorderCommandExecutor = ownedRecorderCommandExecutor;
    }
    return recorderCommandExecutor;
  }

  protected final void notifyRecordingStarted(long requestId) {
    ICallRecorderServiceCallback currentCallback = callback;
    if (currentCallback == null) {
      return;
    }
    try {
      currentCallback.onRecordingStarted(requestId);
    } catch (RemoteException e) {
      Log.w(getLogTag(), "Failed to notify recording start", e);
    }
  }

  protected final void notifyRecordingStartFailed(long requestId) {
    ICallRecorderServiceCallback currentCallback = callback;
    if (currentCallback == null) {
      return;
    }
    try {
      currentCallback.onRecordingStartFailed(requestId);
    } catch (RemoteException e) {
      Log.w(getLogTag(), "Failed to notify recording start failure", e);
    }
  }

  protected final void notifyRecordingError(long requestId) {
    ICallRecorderServiceCallback currentCallback = callback;
    if (currentCallback == null) {
      return;
    }
    try {
      currentCallback.onRecordingError(requestId);
    } catch (RemoteException e) {
      Log.w(getLogTag(), "Failed to notify recording error", e);
    }
  }

  protected final void notifyRecordingStopped(
      long requestId, @Nullable CallRecording recording) {
    ICallRecorderServiceCallback currentCallback = callback;
    if (currentCallback == null) {
      return;
    }
    try {
      currentCallback.onRecordingStopped(requestId, recording);
    } catch (RemoteException e) {
      Log.w(getLogTag(), "Failed to notify recording stop", e);
    }
  }
}
