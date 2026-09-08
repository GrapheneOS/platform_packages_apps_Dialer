package com.android.dialer.callrecord.impl;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;

/**
 * Writes audio data, usually from android.media.MediaCodec, to storage
 */
public abstract class AudioWriter implements AutoCloseable {

  public abstract void init(MediaFormat mediaFormat) throws IOException;

  public abstract void write(ByteBuffer buffer, MediaCodec.BufferInfo bufferInfo)
          throws IOException;

  public static class OutputStreamAudioWriter extends AudioWriter {
    protected final BufferedOutputStream mOutputStream;
    protected final WritableByteChannel mChannel;

    public OutputStreamAudioWriter(FileDescriptor fd) {
      mOutputStream = new BufferedOutputStream(new FileOutputStream(fd));
      mChannel = Channels.newChannel(mOutputStream);
    }

    @Override
    public void init(MediaFormat mediaFormat) throws IOException {
    }

    @Override
    public void write(ByteBuffer buffer, MediaCodec.BufferInfo bufferInfo) throws IOException {
      mChannel.write(buffer);
    }

    @Override
    public void close() throws Exception {
      mChannel.close();
    }
  }

  public static class MediaMuxerWrapper extends AudioWriter {
    private final MediaMuxer mMediaMuxer;
    private int mTrackIndex;
    private boolean isInit;

    public MediaMuxerWrapper(FileDescriptor fd, int format) throws IOException {
      mMediaMuxer = new MediaMuxer(fd, format);
    }

    @Override
    public void init(MediaFormat mediaFormat) {
      mTrackIndex = mMediaMuxer.addTrack(mediaFormat);
      mMediaMuxer.start();
      isInit = true;
    }

    @Override
    public void write(ByteBuffer buffer, MediaCodec.BufferInfo bufferInfo) {
      if (!isInit) {
        throw new IllegalStateException("init not called");
      }
      mMediaMuxer.writeSampleData(mTrackIndex, buffer, bufferInfo);
    }

    @Override
    public void close() throws Exception {
      try {
        mMediaMuxer.stop();
      } catch (IllegalStateException ignored) {
      }
      mMediaMuxer.release();
    }
  }
}
