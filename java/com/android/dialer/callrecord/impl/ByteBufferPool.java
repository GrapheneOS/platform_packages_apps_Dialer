package com.android.dialer.callrecord.impl;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.concurrent.GuardedBy;

/**
 * Fixed-size pool of reusable ByteBuffers
 */
public final class ByteBufferPool {
  public final class Buffer implements AutoCloseable {
    public final ByteBuffer data;
    private boolean released;

    private Buffer(ByteBuffer data) {
      this.data = data;
      this.released = true;
    }

    @Override
    public void close() {
      if (released) {
        return;
      }
      released = true;
      ByteBufferPool.this.release(this);
    }
  }

  @GuardedBy("mLock")
  private final ArrayDeque<Buffer> mFree;
  @GuardedBy("mLock")
  private final ArrayDeque<Buffer> mFilled;
  private final ReentrantLock mLock = new ReentrantLock();
  private final Condition mNotEmpty = mLock.newCondition();
  private final Condition mHasFree = mLock.newCondition();
  @GuardedBy("mLock")
  private volatile boolean mClosed;

  public ByteBufferPool(int poolSize, int bufferSize) {
    mFree = new ArrayDeque<>(poolSize);
    mFilled = new ArrayDeque<>(poolSize);
    for (int i = 0; i < poolSize; i++) {
      mFree.addLast(new Buffer(ByteBuffer.allocateDirect(bufferSize)));
    }
  }

  /** Producer: get a free buffer to fill. If null is returned, buffer is closed */
  public Buffer acquire() throws InterruptedException {
    mLock.lock();
    try {
      while (mFree.isEmpty() && !mClosed) {
        mHasFree.await();
      }
      // If it is closed, don't allow producer to get a buffer
      if (mClosed) {
        return null;
      }
      Buffer buffer = mFree.removeFirst();
      buffer.released = false;
      return buffer;
    } finally {
      mLock.unlock();
    }
  }

  /** Producer: hand off a filled buffer. */
  public boolean produce(Buffer buffer, int length) {
    if (length <= 0) {
      return release(buffer);
    }

    mLock.lock();
    try {
      if (mClosed) {
        return false;
      }
      buffer.data.limit(length);
      buffer.data.position(0);
      mFilled.addLast(buffer);
      mNotEmpty.signal();
      return true;
    } finally {
      mLock.unlock();
    }
  }

  /**
   * Consumer: take a filled buffer. The position is set to 0 and limit is set appropriately.
   * Returns null if closed and empty. If the pool is closed, there may still be buffers left to
   * consume.
   */
  public Buffer consume() throws InterruptedException {
    mLock.lock();
    try {
      while (mFilled.isEmpty() && !mClosed) {
        mNotEmpty.await();
      }
      // If it is closed, don't check for mClosed so that we drain the filled buffers first
      if (mFilled.isEmpty()) {
        return null;
      }
      Buffer buffer = mFilled.removeFirst();
      buffer.released = false;
      return buffer;
    } finally {
      mLock.unlock();
    }
  }

  /** Consumer: return a buffer to the free pool. */
  public boolean release(Buffer buffer) {
    mLock.lock();
    try {
      if (mClosed) {
        return false;
      }
      buffer.data.clear();
      mFree.addLast(buffer);
      mHasFree.signal();
      return true;
    } finally {
      mLock.unlock();
    }
  }

  public void close() {
    mLock.lock();
    try {
      mClosed = true;
      mNotEmpty.signalAll();
      mHasFree.signalAll();
    } finally {
      mLock.unlock();
    }
  }

  public boolean isClosed() {
    return mClosed;
  }
}
