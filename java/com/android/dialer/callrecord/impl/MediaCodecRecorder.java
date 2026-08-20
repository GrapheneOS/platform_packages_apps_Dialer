package com.android.dialer.callrecord.impl;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.IOException;
import java.nio.ByteBuffer;


/**
 * Encodes call audio with MediaCodec, then writing to container is done using either MediaMuxer
 * or written directly (AMR)
 * <p>
 * android.media.AudioRecord
 *  -> PCM data
 *  -> android.media.MediaCodec
 *  -> android.media.MediaMuxer or raw OutputStream to write to file
 * <p>
 * For simplicity, everything will be handled in one thread. Using asynchronous MediaCodec requires
 * a Handler, and the MediaCodec code is in a separate thread from the AudioThread code anyway.
 */
public class MediaCodecRecorder extends BaseCallRecorder {
  private static final String TAG = "MediaCodecRecorder";

  private AudioWriter mAudioWriter;
  protected final MediaCodec mMediaCodec;
  private final MediaFormat mMediaFormat;

  /**
   *
   * @throws IllegalArgumentException
   * @throws IllegalStateException
   */
  public MediaCodecRecorder(Context context, int audioSource, Uri uri, OutputFormat outputFormat) {
    super(context, audioSource, uri, outputFormat);

    switch (outputFormat) {
      case AAC_MPEG_4:
        mMediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                mAudioFormat.getSampleRate(), mAudioFormat.getChannelCount());
        break;
      case AMR_WB:
        mMediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AMR_WB,
                mAudioFormat.getSampleRate(), mAudioFormat.getChannelCount());
        break;
      default:
        throw new IllegalArgumentException("unexpected output format " + outputFormat);
    }
    mMediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, outputFormat.bitRate);

    final String encoderForFormat = new MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .findEncoderForFormat(mMediaFormat);
    if (encoderForFormat == null) {
      throw new IllegalArgumentException("invalid format " + outputFormat);
    }
    Log.d(TAG, "found encoder " + encoderForFormat + " for format " + outputFormat);
    try {
      mMediaCodec = MediaCodec.createByCodecName(encoderForFormat);
    } catch (IOException | IllegalArgumentException e) {
      String msg = "failed to create codec for " + encoderForFormat;
      Log.e(TAG, msg, e);
      throw new IllegalArgumentException(msg, e);
    }
  }

  @Override
  protected void onRecordingStart(ParcelFileDescriptor pfd) throws IOException {
    switch (mOutputFormat) {
      case AAC_MPEG_4:
        mAudioWriter = new AudioWriter.MediaMuxerWrapper(pfd.getFileDescriptor(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        break;
      case AMR_WB:
        mAudioWriter = new AmrAudioWriter(pfd.getFileDescriptor());
        break;
      default:
        throw new IllegalStateException("unknown format " + mOutputFormat);
    }
    mMediaCodec.configure(mMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    mMediaCodec.start();
  }

  @Override
  protected void onPcmBufferRead(ByteBuffer pcmBuffer) throws IOException {
    int bytesEnqueued = 0;
    final int totalReadBefore = mBytesRead.get() - pcmBuffer.limit();
    pcmBuffer.position(0);
    while (pcmBuffer.hasRemaining()) {
      // From queueInputBuffer documentation:
      // "The presentation timestamp in microseconds for this buffer. This is normally the media time
      // at which this buffer should be presented (rendered)."
      long presentationTimeUs = computePresentationTimeUs(totalReadBefore + bytesEnqueued);
      int inputBufferIndex = mMediaCodec.dequeueInputBuffer(0);
      if (inputBufferIndex >= 0) {
        ByteBuffer inBuf = mMediaCodec.getInputBuffer(inputBufferIndex);
        if (inBuf != null) {
          inBuf.clear();
          // Fill as much as we can into inBuf
          final int bytesToQueue = Math.min(inBuf.capacity(), pcmBuffer.remaining());
          // Copy only bytesToQueue from pcmBuffer. ByteBuffer doesnt allow us to put in a certain
          // number of bytes from a source buffer
          final int oldLimit = pcmBuffer.limit();
          pcmBuffer.limit(pcmBuffer.position() + bytesToQueue);
          inBuf.put(pcmBuffer);
          pcmBuffer.limit(oldLimit);
          inBuf.flip();

          bytesEnqueued += bytesToQueue;

          int offset = 0;
          int flags = 0;
          mMediaCodec.queueInputBuffer(inputBufferIndex, offset, bytesToQueue, presentationTimeUs, flags);
        }
      } else {
        Log.d(TAG, " dequeueInputBuffer index is < 0 : " + inputBufferIndex
                + ", bytesEnqueued " + bytesEnqueued);
      }

      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      if (drainEncoder(info, false)) {
        Log.d(TAG, "drainEncoder eos reached, bytesEnqueued " + bytesEnqueued);
        break;
      }
    }
  }

  @Override
  protected void onRecordingStop() throws IOException {
    Log.d(TAG, "signalling end of stream");
    int inputBufferIndex = mMediaCodec.dequeueInputBuffer(10000);
    if (inputBufferIndex >= 0) {
      mMediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
    }
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    drainEncoder(info, true);
  }

  private boolean drainEncoder(MediaCodec.BufferInfo info, boolean shouldLoopUntilEos)
          throws IOException {
    while (!Thread.currentThread().isInterrupted()) {
      int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(info, 0);
      if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        mAudioWriter.init(mMediaCodec.getOutputFormat());
      } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
        if (!shouldLoopUntilEos) {
          return false;
        }
        // Keep draining until end of stream
      } else if (outputBufferIndex >= 0) {
        ByteBuffer outBuf = mMediaCodec.getOutputBuffer(outputBufferIndex);
        try {
          if (outBuf == null) {
            Log.w(TAG, "drainEncoder: unexpected null output buffer");
            return false;
          }

          if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            outBuf.position(info.offset);
            outBuf.limit(info.offset + info.size);
            mAudioWriter.write(outBuf, info);
          } else {
            Log.d(TAG, "drainEncoder: skipping codec config");
          }

          final boolean isEos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;

          if (shouldLoopUntilEos) {
            if (isEos) {
              return true;
            }
            // Loop
          } else {
            // Don't loop. Since input and output buffers are handled in same thread, go back to
            // exit drainEncoder loop and go back to processing input buffer
            return isEos;
          }
        } finally {
          mMediaCodec.releaseOutputBuffer(outputBufferIndex, false);
        }
      }
    }
    return true;
  }

  private void closeAudioWriter() {
    final AudioWriter writer = mAudioWriter;
    if (writer != null) {
      try {
        writer.close();
      } catch (Exception ignored) {
      }
      mAudioWriter = null;
    }
  }

  @Override
  protected void reset() {
    try {
      mMediaCodec.flush();
    } catch (IllegalStateException e) {
      Log.w(TAG, "reset: flush failed", e);
    }
    try {
      mMediaCodec.stop();
    } catch (IllegalStateException e) {
      Log.w(TAG, "reset: stop failed", e);
    }
    // Documentation says need to configure mMediaCodec again after stop
    closeAudioWriter();
  }

  @Override
  protected void onClose() {
    closeAudioWriter();

    try {
      mMediaCodec.stop();
    } catch (IllegalStateException ignored) {
    }
    mMediaCodec.release();
  }
}
