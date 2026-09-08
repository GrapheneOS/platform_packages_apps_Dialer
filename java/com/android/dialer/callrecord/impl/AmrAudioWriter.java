package com.android.dialer.callrecord.impl;

import android.media.MediaFormat;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * MediaMuxer does not support AMR output despite an AMR writer being present in
 * frameworks/av/media/libstagefright/AMRWriter.cpp. The AMR format is simple enough to write it
 * here.
 */
public class AmrAudioWriter extends AudioWriter.OutputStreamAudioWriter {

  private boolean mIsInit;

  public AmrAudioWriter(FileDescriptor fd) {
    super(fd);
  }

  @Override
  public synchronized void init(MediaFormat mediaFormat) throws IOException {
    if (mIsInit) {
      return;
    }
    final int channelCount = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, -1);
    final String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
    final boolean isAmrWb = MediaFormat.MIMETYPE_AUDIO_AMR_WB.equals(mime);

    final int sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0);
    // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/av/media/libstagefright/data/
    // lists expected values. AMRWriter also has these checks hardcoded:
    // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/av/media/libstagefright/AMRWriter.cpp;l=84
    if (isAmrWb && sampleRate != 16000) {
      throw new IOException("invalid sampleRate for AMR-WB: " + sampleRate);
    } else if (!isAmrWb && sampleRate != 8000) {
      throw new IOException("invalid sampleRate for AMR: " + sampleRate);
    }

    // https://datatracker.ietf.org/doc/html/rfc4867#page-35
    // "An AMR or AMR-WB file has the following structure:"
    //
    //   +------------------+
    //   | Header           |
    //   +------------------+
    //   | Speech frame 1   |
    //   +------------------+
    //   : ...              :
    //   +------------------+
    //   | Speech frame n   |
    //   +------------------+
    writeAmrHeader(isAmrWb, channelCount);
    mIsInit = true;
  }

  public void writeAmrHeader(boolean isAmrWb, int channelCount) throws IOException {
    final String header;
    if (channelCount == 1) {
      header = isAmrWb ? "#!AMR-WB\n" : "#!AMR\n";
    } else {
      // https://developer.android.com/media/platform/supported-formats
      throw new IOException("invalid channel count " + channelCount);
    }
    mOutputStream.write(header.getBytes(StandardCharsets.US_ASCII));
  }
}
