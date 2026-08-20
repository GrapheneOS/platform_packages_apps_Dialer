package com.android.dialer.callrecord.impl;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;

/**
 * Writes call audio as uncompressed linear PCM WAV files, i.e. it writes the bytes from AudioRecord
 * directly with no compression.
 * <p>
 * android.media.AudioRecord -> OutputStream
 * <p>
 * Note: Google Dialer records using one AudioRecord instance for
 * {@link android.media.MediaRecorder.AudioSource#VOICE_UPLINK} and another for
 * {@link android.media.MediaRecorder.AudioSource#VOICE_DOWNLINK}, both in recorded 8000 Hz. Then
 * both are resampled to 16000 Hz, then two tracks are combined into a 2-channel .WAV file for
 * export. It's speculated that this design is for AI purposes, e.g. call transcripts being able to
 * distinguish between caller and callee, plus models being commonly trained on 16000 Hz data.
 */
public class WavLPCMRecorder extends BaseCallRecorder {
  private static final String TAG = "WavLPCMRecorder";
  private BufferedOutputStream mOutputStream;
  private WritableByteChannel mOutputStreamChannel;
  private final boolean mShouldUseExtensibleHeader;
  private final int mWavHeaderSize;
  private final int bytesPerSample;

  public WavLPCMRecorder(Context context, int audioSource, Uri uri, OutputFormat outputFormat) {
    super(context, audioSource, uri, outputFormat);
    if (outputFormat != OutputFormat.LPCM_WAV) {
      throw new IllegalArgumentException("invalid output format " + outputFormat);
    }
    if (mAudioFormat.getChannelCount() == 0) {
      throw new IllegalArgumentException("zero channel count");
    }
    bytesPerSample = mAudioFormat.getFrameSizeInBytes() / mAudioFormat.getChannelCount();
    // As long as we use ENCODING_PCM_16BIT and CHANNEL_IN_MONO, this will be false.
    mShouldUseExtensibleHeader = bytesPerSample > 2 || mAudioFormat.getChannelCount() > 2;
    mWavHeaderSize = mShouldUseExtensibleHeader ? 68 : 44;
  }

  @Override
  protected void onRecordingStart(ParcelFileDescriptor pfd) throws IOException {
    mOutputStream = new BufferedOutputStream(new FileOutputStream(pfd.getFileDescriptor()));
    mOutputStreamChannel = Channels.newChannel(mOutputStream);
    // Allocate space for the WAV header. This will be written after recording is done, as the
    // header requires the data size.
    byte[] empty = new byte[mWavHeaderSize];
    mOutputStream.write(empty);
  }

  @Override
  protected void onPcmBufferRead(ByteBuffer pcmBuffer) throws IOException {
    mOutputStreamChannel.write(pcmBuffer);
  }

  @Override
  protected void onRecordingStop() throws IOException {
    // WAV readers expect samples to be interleaved fully per frame.
    // Trailing partial frames are invalid, so trim to a whole number of frames.
    final int bytesWritten = mBytesRead.get();
    final int roundedBytes = bytesWritten - (bytesWritten % mAudioFormat.getFrameSizeInBytes());
    Log.d(TAG, "mBytesWritten " + bytesWritten + ", roundedBytes " + roundedBytes);
    if (roundedBytes % 2 == 1) {
      Log.d(TAG, "adding empty byte for padding");
      mOutputStream.write(0);
    }
    mOutputStream.flush();
    // Close the output stream. We will reopen it in writeWavHeader with a different mode to write
    // the header to the beginning of the file.
    onClose();

    writeWavHeader(roundedBytes);
  }

  private void writeWavHeader(final int roundedWrittenBytes) throws IOException {
    // Seekable pfd
    ParcelFileDescriptor pfd = mContentResolver.openFileDescriptor(mUri, "rw");
    if (pfd == null) {
      throw new IOException("missing pfd");
    }
    try (FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor())) {
      try {
        // FileChannel doesn't appear to work; seek manually
        if (Os.lseek(pfd.getFileDescriptor(), 0, OsConstants.SEEK_SET) != 0) {
          throw new IOException("lseek did not move offset to 0");
        }
      } catch (ErrnoException e) {
        throw new IOException("cannot seek pfd", e);
      }
      fos.write(getHeaderBytes(roundedWrittenBytes));
      Log.d(TAG, "wrote WAV header");
    } finally {
      pfd.close();
    }
  }

  private static final String WAV_HEADER_TAG_RIFF = "RIFF";
  private static final String WAV_HEADER_TAG_WAVE = "WAVE";
  private static final String WAV_HEADER_TAG_FORMAT_CHUNK = "fmt ";
  private static final String WAV_HEADER_TAG_DATA_CHUNK = "data";
  /** Size of WAVEFORMATEX */
  private static final int WAV_HEADER_FMT_CHUNK_SIZE = 16;
  /** Size of extra format information from WAVEFORMATEXTENSIBLE */
  private static final int WAV_HEADER_EXTRA_FMT_INFO_SIZE = 24;

  private byte[] getHeaderBytes(final int roundedWrittenBytes) {
    final byte[] header = new byte[mWavHeaderSize];
    final ByteBuffer headerBuffer = ByteBuffer.wrap(header);
    headerBuffer.order(ByteOrder.LITTLE_ENDIAN);

    final int fmtChunkSize = mShouldUseExtensibleHeader
            ? WAV_HEADER_FMT_CHUNK_SIZE + WAV_HEADER_EXTRA_FMT_INFO_SIZE // 40
            : WAV_HEADER_FMT_CHUNK_SIZE;

    // "RIFF"
    // [+4 bytes] [total: 4]
    headerBuffer.put(WAV_HEADER_TAG_RIFF.getBytes(StandardCharsets.US_ASCII));
    // size (fileSize - 8)
    // = fmtChunkSize + 20 ("WAVE"(4) + "fmt "(8) + "data"(8))
    //     + roundedWrittenBytes + (padding: roundedWrittenBytes % 2)
    // [+4 bytes] [total: 8]
    headerBuffer.putInt(fmtChunkSize + 20 + roundedWrittenBytes + (roundedWrittenBytes % 2));

    // "WAVE"
    // [+4 bytes] [total: 12]
    headerBuffer.put(WAV_HEADER_TAG_WAVE.getBytes(StandardCharsets.US_ASCII));

    // "fmt " chunk header and size
    // [+8 bytes] [total: 20]
    headerBuffer.put(WAV_HEADER_TAG_FORMAT_CHUNK.getBytes(StandardCharsets.US_ASCII));
    headerBuffer.putInt(fmtChunkSize);

    // https://learn.microsoft.com/en-us/windows/win32/api/mmeapi/ns-mmeapi-waveformatex

    // wFormatTag: WAVE_FORMAT_PCM = 1, WAVE_FORMAT_EXTENSIBLE = 0xFFFE
    // [+2 bytes] [total: 22]
    headerBuffer.putShort(mShouldUseExtensibleHeader ? (short) 0xFFFE : (short) 0x0001);

    // nChannels
    // [+2 bytes] [total: 24]
    headerBuffer.putShort((short) mAudioFormat.getChannelCount());

    // nSamplesPerSec
    // [+4 bytes] [total: 28]
    headerBuffer.putInt(mAudioFormat.getSampleRate());

    // nAvgBytesPerSec = sampleRate * channels * bytesPerSample = sampleRate * frameSizeInBytes
    // [+4 bytes] [total: 32]
    headerBuffer.putInt(mAudioFormat.getSampleRate() * mAudioFormat.getFrameSizeInBytes());

    // nBlockAlign = channels * bytesPerSample = frameSizeInBytes
    // [+2 bytes] [total: 34]
    headerBuffer.putShort((short) (mAudioFormat.getFrameSizeInBytes()));

    // wBitsPerSample
    // [+2 bytes] [total: 36]
    headerBuffer.putShort((short) (8 * bytesPerSample));

    if (mShouldUseExtensibleHeader) {
      // https://learn.microsoft.com/en-us/windows/win32/api/mmreg/ns-mmreg-waveformatextensible

      // cbSize = 22 (from WAVEFORMATEX)
      // https://learn.microsoft.com/en-us/previous-versions/dd757713(v=vs.85)#members
      // "Size, in bytes, of extra format information appended to the end of the WAVEFORMATEX
      // structure."
      // "For WAVE_FORMAT_PCM formats (and only WAVE_FORMAT_PCM formats), this member is ignored.
      // When this structure is included in a WAVEFORMATEXTENSIBLE structure, this value must be
      // at least 22."
      // [+2 bytes] [extra fmt info total: 2]
      headerBuffer.putShort((short) 22);
      // cbSize is not part of extra format information, so it will be 24 - 2 = 22 here

      // wValidBitsPerSample
      // [+2 bytes] [extra fmt info total: 4]
      headerBuffer.putShort((short) (8 * bytesPerSample));

      // dwChannelMask (speaker positions). 0 = unspecified.
      // [+4 bytes] [extra fmt info total: 8]
      headerBuffer.putInt(0);

      // SubFormat = KSDATAFORMAT_SUBTYPE_PCM
      // GUID (text): 00000001-0000-0010-8000-00AA00389B71
      //
      // Windows GUID binary layout LE for Data1/Data2/Data3; Data4 as-is:
      // (https://learn.microsoft.com/en-us/windows/win32/api/guiddef/ns-guiddef-guid)
      // (https://en.wikipedia.org/wiki/Universally_unique_identifier#Endianness)
      // [+16 bytes] [extra fmt info total: 24]
      byte[] KSDATAFORMAT_SUBTYPE_PCM_LE = new byte[] {
              0x01, 0x00, 0x00, 0x00,       // Data1 = 0x00000001 (LE)
              0x00, 0x00,                   // Data2 = 0x0000 (LE)
              0x10, 0x00,                   // Data3 = 0x0010 (LE)
              (byte) 0x80, 0x00,            // Data4[0..1]
              0x00, (byte) 0xAA,            // Data4[2..3]
              0x00, 0x38, (byte) 0x9B, 0x71 // Data4[4..7]
      };
      headerBuffer.put(KSDATAFORMAT_SUBTYPE_PCM_LE);

        /*
        Note: Google Dialer does this equivalent behavior:
            writeIntegerAsLittleEndianShortToOutputStream(outputStream, 1)
            byte[] a = {0, 0, 0, 0, 16, 0, Byte.MIN_VALUE, 0, 0, -86, 0, 56, -101, 113};
            outputStream.write(a);
        */
    }
    // If mShouldUseExtensibleHeader is true, [+24 bytes from WAV_HEADER_EXTRA_FMT_INFO_SIZE]

    // "data" chunk header [+4 bytes] [total: 40]
    headerBuffer.put(WAV_HEADER_TAG_DATA_CHUNK.getBytes(StandardCharsets.US_ASCII));
    // data chunk size (payload only; pad byte is not counted here)
    // [+4 bytes] [total: 44] [mShouldUseExtensibleHeader total: 44+24 = 68]
    headerBuffer.putInt(roundedWrittenBytes);
    return header;
  }

  @Override
  protected void reset() {
    onClose();
  }

  @Override
  protected void onClose() {
    final BufferedOutputStream outputStream = mOutputStream;
    if (outputStream != null) {
      try {
        outputStream.close();
      } catch (IOException ignored) {
      }
    }
    mOutputStream = null;
  }
}
