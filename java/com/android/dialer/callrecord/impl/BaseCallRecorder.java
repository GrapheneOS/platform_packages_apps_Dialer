package com.android.dialer.callrecord.impl;

import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.concurrent.GuardedBy;

/**
 * Records audio using {@link android.media.AudioRecord} which is the same framework API that Google
 * Dialer uses for call recording / call notes. Subclasses decide what to do with the PCM data
 * from AudioRecord.
 * <p>
 * Recording is done with two threads in a producer-consumer pattern. One thread calls
 * {@link AudioRecord#read(ByteBuffer, int)} and pushes the data to a pool of pre-allocated
 * {@link ByteBuffer}s. The other thread consumes these buffers from the pool and processes them via
 * {@link #onPcmBufferRead(ByteBuffer)} which is implemented by subclasses.
 * <p>
 * To signal recording stop, the pool is closed. The consumer thread continues consuming filled
 * buffers until there are no more, and the producer thread exits.
 */
public abstract class BaseCallRecorder implements RecordingBackend {

  private static final String TAG = "BaseCallRecorder";

  private static final int DURATION_TO_READ_MS = 1000;
  private static final int BUFFER_POOL_NUM_BUFFERS = 15;
  private static final long RECORD_JOB_LOOP_PERIOD_MS = 10;
  private static final long JOB_JOIN_TIMEOUT_SECONDS = 5;

  private volatile boolean mIsRecording;
  private volatile boolean mIsClosed;
  @Nullable private volatile Throwable mRecordingFailure;
  @Nullable private volatile Runnable mFailureListener;

  protected final OutputFormat mOutputFormat;
  protected final AudioFormat mAudioFormat;
  private final AudioRecord mAudioRecord;
  protected final ContentResolver mContentResolver;
  private ParcelFileDescriptor mWritePfd;

  /**
   * The URI pointing to the call recording file that we are writing to.
   */
  protected final Uri mUri;
  private final ScheduledExecutorService mAudioBufferProducerExecutor;
  private final ExecutorService mAudioBufferConsumerExecutor = Executors.newSingleThreadExecutor();
  /**
   * Pre-allocated ByteBuffers for use by consumer and producer tasks. Closing this pool will get
   * the producer and consumer threads to stop (consumer will consume remaining buffers until empty).
   */
  private ByteBufferPool mAudioBufferPool;

  @GuardedBy("this")
  private Future<?> mWritingTask;
  private final Object mRecordLoopLock = new Object();
  @GuardedBy("mRecordLoopLock")
  private ScheduledFuture<?> mRecordLoopJob;

  private final int mPcmBufferSize;

  protected BaseCallRecorder(Context context, int audioSource, Uri uri, OutputFormat outputFormat) {
    this(
        context,
        uri,
        outputFormat,
        // Google Dialer uses the sample rate for the AudioRecord buffer size.
        new AudioRecord(
            audioSource,
            outputFormat.sampleRate,
            outputFormat.channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
            outputFormat.sampleRate),
        newAudioBufferProducerExecutor());
  }

  BaseCallRecorder(
      Context context,
      Uri uri,
      OutputFormat outputFormat,
      AudioRecord audioRecord,
      ScheduledExecutorService audioBufferProducerExecutor) {
    mOutputFormat = outputFormat;

    this.mContentResolver = context.getApplicationContext().getContentResolver();
    this.mAudioFormat = new AudioFormat.Builder()
            .setChannelMask(outputFormat.channelMask)
            .setSampleRate(outputFormat.sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build();
    mAudioRecord = audioRecord;
    mAudioBufferProducerExecutor = audioBufferProducerExecutor;
    mUri = uri;

    final int framesInDurationMs = mAudioRecord.getSampleRate() * DURATION_TO_READ_MS / 1000;
    mPcmBufferSize = Math.max(
            mAudioFormat.getFrameSizeInBytes() * framesInDurationMs,
            AudioRecord.getMinBufferSize(
                    mAudioRecord.getSampleRate(), mAudioRecord.getChannelConfiguration(),
                    mAudioRecord.getAudioFormat()));
    Log.d(TAG, "mPcmBufferSize " + mPcmBufferSize);
    mAudioBufferPool = new ByteBufferPool(BUFFER_POOL_NUM_BUFFERS, mPcmBufferSize);
  }

  private static ScheduledExecutorService newAudioBufferProducerExecutor() {
    return Executors.newSingleThreadScheduledExecutor(
        r -> {
          final Thread thread = new Thread(r, "AudioRec");
          thread.setPriority(Thread.NORM_PRIORITY);
          return thread;
        });
  }

  protected final long computePresentationTimeUs(int bytesRead) {
    int framesRead = bytesRead / mAudioFormat.getFrameSizeInBytes();
    return (framesRead * 1_000_000L) / mAudioFormat.getSampleRate();
  }

  public final boolean isRecording() {
    return mIsRecording && mWritingTask != null;
  }

  @Override
  public final boolean hasFailed() {
    return mRecordingFailure != null;
  }

  @Override
  @Nullable
  public final Throwable getRecordingFailure() {
    return mRecordingFailure;
  }

  @Override
  public final void setFailureListener(@Nullable Runnable listener) {
    mFailureListener = listener;
  }

  @Override
  public final synchronized void startRecording() {
    if (mWritingTask != null) {
      Log.d(TAG, "existing recording task running");
      return;
    }
    if (mIsClosed
        || mAudioBufferConsumerExecutor.isShutdown()
        || mAudioBufferProducerExecutor.isShutdown()) {
      Log.e(TAG, "cannot start recording after close() called");
      return;
    }

    mRecordingFailure = null;
    mBytesRead.set(0);
    if (mAudioBufferPool.isClosed()) {
      mAudioBufferPool = new ByteBufferPool(BUFFER_POOL_NUM_BUFFERS, mPcmBufferSize);
    }

    try {
      startAudioRecord();
    } catch (IOException e) {
      throw new IllegalStateException("failed to start recording", e);
    }

    mWritingTask = mAudioBufferConsumerExecutor.submit(() -> {
      try {
        runConsumerJob();
      } catch (Throwable t) {
        Log.e(TAG, "error when running consumer job", t);
        markFailed(t);
        throw t;
      }
      return null;
    });
    mIsRecording = true;

    final AudioRecordPeriodicProducerJob recordLoopJob =
        new AudioRecordPeriodicProducerJob(
            SystemClock.elapsedRealtime(), RECORD_JOB_LOOP_PERIOD_MS);
    synchronized (mRecordLoopLock) {
      mRecordLoopJob =
          mAudioBufferProducerExecutor.schedule(recordLoopJob, 0, TimeUnit.MILLISECONDS);
    }
  }

  private void startAudioRecord() throws IOException {
    try {
      mWritePfd = mContentResolver.openFileDescriptor(mUri, "w");
      if (mWritePfd == null) {
        throw new IOException("pfd for " + mUri + " failed to open");
      }

      onRecordingStart(mWritePfd);
      Log.d(TAG, "start recording");
      mAudioRecord.startRecording();
      if (mAudioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
        throw new IllegalStateException("AudioRecord failed to start");
      }
    } catch (Throwable t) {
      closeWritePfd();
      throw t;
    }
  }

  private void markFailed(Throwable t) {
    mRecordingFailure = t;
    mIsRecording = false;
    mAudioBufferPool.close();
    Runnable failureListener = mFailureListener;
    if (failureListener != null) {
      failureListener.run();
    }
  }

  /**
   * Reads from AudioRecord using aligned scheduled tasks, matching Google Dialer's timing model.
   * Scheduling and cancellation share a lock so stop cannot miss a successor task.
   */
  class AudioRecordPeriodicProducerJob implements Callable<Object> {
    private final long mStartTimeMs;
    private final long mPeriodMs;
    // This might be left here if the job is cancelled, but that's fine since the pool this
    // buffer belongs to gets closed if this is cancelled anyway.
    private ByteBufferPool.Buffer mBuffer;

    AudioRecordPeriodicProducerJob(long startTimeMs, long periodMs) {
      mStartTimeMs = startTimeMs;
      mPeriodMs = periodMs;
    }

    /**
     * Computes an aligned delay based on starting at mStartTime modulo the period. The purpose is
     * to ensure that the loop is executed only at the times `startTime + k*period`
     */
    private long computeAlignedDelayMs() {
      if (mPeriodMs <= 0) return 0L;
      long now = SystemClock.elapsedRealtime();
      if (now < mStartTimeMs) {
        return (mStartTimeMs + mPeriodMs) - now;
      }

      // align at next slot at startTime + k*period
      long elapsedSinceStart = now - mStartTimeMs;
      long remainder = elapsedSinceStart % mPeriodMs;
      return remainder == 0 ? mPeriodMs : mPeriodMs - remainder;
    }

    private boolean shouldContinue() {
      return mIsRecording && mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING;
    }

    private void closeQuietly() {
      mIsRecording = false;
      if (mBuffer != null) {
        // not needed since we're going to close the pool and only one producer
        mAudioBufferPool.release(mBuffer);
        mBuffer = null;
      }
      stopAudioRecordResourcesAndClosePool();
    }

    @Override
    public Object call() throws Exception {
      boolean shouldContinue = false;
      try {
        shouldContinue = readAudioRecordAndProduceAudioData();
        if (shouldContinue) {
          // AudioRecord.READ_NON_BLOCKING may return 0 before data is available, so retry at the
          // next aligned interval.
          final long delay = computeAlignedDelayMs();
          synchronized (mRecordLoopLock) {
            shouldContinue = mIsRecording;
            if (shouldContinue) {
              try {
                mRecordLoopJob =
                    mAudioBufferProducerExecutor.schedule(this, delay, TimeUnit.MILLISECONDS);
              } catch (RuntimeException e) {
                shouldContinue = false;
                throw e;
              }
            }
          }
        }
      } catch (Exception | Error e) {
        Log.e(TAG, "error when running recording job", e);
        markFailed(e);
        throw e;
      } finally {
        if (!shouldContinue) {
          Log.d(TAG, "AudioRecord loop finished");
          closeQuietly();
        }
      }
      return null;
    }

    // Returns whether we should continue periodically
    public boolean readAudioRecordAndProduceAudioData() throws Exception {
      if (!shouldContinue()) {
        if (mIsRecording) {
          markFailed(new IllegalStateException("AudioRecord stopped unexpectedly"));
        }
        return false;
      }

      if (mBuffer == null) {
        mBuffer = mAudioBufferPool.acquire();
        if (mBuffer == null) {
          // pool is closed
          return false;
        } else {
          mBuffer.data.clear();
        }
      }

      // AudioRecord does not change the ByteBuffer position
      int read = mAudioRecord.read(mBuffer.data, mBuffer.data.capacity(),
              AudioRecord.READ_NON_BLOCKING);
      if (read < 0) {
        Log.e(TAG, "error on AudioRecord read: " + read);
        markFailed(new IllegalStateException("AudioRecord read failed: " + read));
        return false;
      } else if (read > 0) {
        // This will set buffer's limit and reset position
        if (!mAudioBufferPool.produce(mBuffer, read)) {
          // pool is closed
          return false;
        }
        mBuffer = null;
      } else {
        // If read == 0, keep mBuffer for the next iteration so that we don't need to acquire from
        // pool again.
      }

      return shouldContinue();
    }
  }

  private void stopAudioRecordResourcesAndClosePool() {
    try {
      mAudioRecord.stop();
    } catch (IllegalStateException ignored) {
    }
    // After the pool is closed, the consumer thread will continue until no more buffers left to
    // process. The producer thread will stop repeating.
    mAudioBufferPool.close();
  }

  @Nullable
  private RuntimeException waitForTask(Future<?> task, String description) {
    final long deadlineNanos =
        System.nanoTime() + TimeUnit.SECONDS.toNanos(JOB_JOIN_TIMEOUT_SECONDS);
    boolean interrupted = false;
    try {
      while (true) {
        try {
          task.get(
              Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
          return null;
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
    } catch (ExecutionException | TimeoutException e) {
      Log.w(TAG, "failed to wait for " + description + " task to finish", e);
      Throwable failure =
          e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
      markFailed(failure);
      return new IllegalStateException("Failed to finish recording", failure);
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public final synchronized void stopRecordingBlocking() {
    mIsRecording = false;
    // This will signal to the consumer and producer threads to stop processing.
    mAudioBufferPool.close();
    final Future<?> producerBarrier;
    synchronized (mRecordLoopLock) {
      if (mRecordLoopJob == null) {
        producerBarrier = null;
      } else {
        // cancel(false) does not stop a running task. The barrier runs after it on the same
        // single-thread executor, and the lock prevents that task from scheduling a successor.
        mRecordLoopJob.cancel(false);
        mRecordLoopJob = null;
        producerBarrier = mAudioBufferProducerExecutor.submit(() -> {});
      }
    }
    if (mWritingTask == null && producerBarrier == null) {
      stopAudioRecordResourcesAndClosePool();
      return;
    }

    RuntimeException stopFailure =
        producerBarrier == null ? null : waitForTask(producerBarrier, "recording");
    stopAudioRecordResourcesAndClosePool();

    if (mWritingTask != null) {
      // Allow the writer task to finish processing all buffers that were pushed to the pool.
      RuntimeException writingFailure = waitForTask(mWritingTask, "writing");
      if (stopFailure == null) {
        stopFailure = writingFailure;
      }
    }
    mWritingTask = null;

    if (stopFailure == null && mRecordingFailure != null) {
      stopFailure = new IllegalStateException("Failed to finish recording", mRecordingFailure);
    }

    Log.d(TAG, "stopRecordingBlocking finished waiting for tasks");
    // Avoid exposing an interrupt to MediaCodec finalization after an uninterruptible join.
    final boolean interrupted = Thread.interrupted();
    try {
      // Once either worker fails, the file is partial. Skip container/header finalization so the
      // service reports one recording error instead of trying to save a damaged row.
      if (stopFailure == null) {
        onRecordingStop();
      }
    } catch (IOException e) {
      Log.e(TAG, "error in onRecordingStop", e);
      markFailed(e);
      if (stopFailure == null) {
        stopFailure = new IllegalStateException("Failed to finish recording", e);
      }
    } finally {
      try {
        closeWritePfd();
        reset();
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
    if (stopFailure != null) {
      throw stopFailure;
    }
  }

  protected final AtomicInteger mBytesRead = new AtomicInteger(0);

  private void runConsumerJob() throws IOException, InterruptedException {
    while (true) {
      try (ByteBufferPool.Buffer newPcmAudio = mAudioBufferPool.consume()) {
        if (newPcmAudio == null) {
          // The pool is closed AND there are no more buffers to consume anymore.
          Log.d(TAG, "consumer job exit");
          break;
        }

        mBytesRead.addAndGet(newPcmAudio.data.limit());
        onPcmBufferRead(newPcmAudio.data);
      }
    }
  }

  /**
   * Called before the buffer loop to prepare for recording. After this, the buffer reading loop is
   * entered and {@link #onPcmBufferRead(ByteBuffer)} will be called repeatedly.
   *
   * @param pfd A file descriptor for the call recording file in storage to write to. This is open
   *            in "w" mode, so seeking isn't possible. Do not close this.
   * @throws IOException if an error occurs when preparing for recording
   */
  protected abstract void onRecordingStart(ParcelFileDescriptor pfd) throws IOException;

  /**
   * Repeatedly called after bytes from {@link android.media.AudioRecord#read} are read in a loop.
   * This will be called in a different thread from the thread that's reading from AudioRecord.
   *
   * @param pcmBuffer The buffer containing PCM data from call audio. The position set to 0 and
   *                  limit is set to the number of bytes read from the
   *                  {@link android.media.AudioRecord#read} call.
   * @throws IOException
   */
  protected abstract void onPcmBufferRead(ByteBuffer pcmBuffer) throws IOException;

  /**
   * Called after recording is done and the file should be completed. The file descriptor from
   * {@link #onRecordingStart} is still active here and will be closed after this method returns, so
   * any resources using that file descriptor can still use it.
   * @throws IOException
   */
  protected abstract void onRecordingStop() throws IOException;

  /**
   * Resets the call recorder so that {@link #startRecording()} can be called again. In practice,
   * {@link #startRecording()} is never called again, and a new BaseCallRecorder is remade.
   */
  protected abstract void reset();

  /**
   * Called when subclasses should clean up resources.
   */
  protected abstract void onClose();

  private void closeWritePfd() {
    final ParcelFileDescriptor pfd = mWritePfd;
    if (pfd != null) {
      try {
        pfd.close();
      } catch (IOException ignored) {
      }
      mWritePfd = null;
    }
  }

  @Override
  public final void close() {
    if (mIsClosed) {
      return;
    }
    mIsClosed = true;
    mIsRecording = false;
    onClose();
    mAudioBufferPool.close();
    mAudioRecord.release();
    mAudioBufferProducerExecutor.shutdownNow();
    mAudioBufferConsumerExecutor.shutdownNow();
    closeWritePfd();
  }
}
