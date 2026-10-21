package com.android.dialer.callrecord.impl;

import android.media.AudioFormat;

import com.android.dialer.callrecord.RecordingOutputFormat;

/**
 * Permitted values for bitrate and sample rate can be found at
 * https://cs.android.com/android/platform/superproject/main/+/main:frameworks/av/media/libstagefright/data/media_codecs_google_c2_audio.xml
 * and other .xmls in the directory.
 * <p>
 * Note: As of Jan 2026, Google Dialer records with LPCM_WAV with a sample rate of 8000 and then
 * appears to resample it to 16000. Resampling is done in native code via a "QResampler" (Q31?),
 * though it converts the PCM data from bytes to floats in Java first (mapping each byte to
 * the closed interval [-1.0f, 1.0f]). Google Dialer does have MPEG_4 and FLAC implemented via
 * MediaCodec and MediaMuxer, but it doesn't appear to be used right now.
 */
public enum OutputFormat {
  AAC_MPEG_4(AudioFormat.CHANNEL_IN_MONO, 16000, 16000, ".m4a"),
  /**
   * Note: A sample rate of 16000 Hz is required for AMR-WB encoders on Android
   */
  AMR_WB(AudioFormat.CHANNEL_IN_MONO, 16000, 16000, ".amr"),
  LPCM_WAV(AudioFormat.CHANNEL_IN_MONO, 16000, 16000, ".wav");

  public final int channelMask;
  public final int bitRate;
  public final int sampleRate;
  public final String extension;

  OutputFormat(int channelMask, int bitRate, int sampleRate, String extension) {
    this.channelMask = channelMask;
    this.bitRate = bitRate;
    this.sampleRate = sampleRate;
    this.extension = extension;
  }

  public static OutputFormat fromRecordingOutputFormat(RecordingOutputFormat outputFormat) {
    switch (outputFormat) {
      case AAC_MPEG_4:
        return AAC_MPEG_4;
      case AMR_WB:
        return AMR_WB;
      case LPCM_WAV:
        return LPCM_WAV;
      default:
        throw new IllegalArgumentException("unexpected output format " + outputFormat);
    }
  }
}
