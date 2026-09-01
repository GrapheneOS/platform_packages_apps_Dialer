package com.android.dialer.callrecord.impl;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class BaseCallRecorderLifecycleTest {
  private static final long TIMEOUT_SECONDS = 5;

  private AudioRecord audioRecord;
  private NotifyingScheduledExecutor producerExecutor;
  private ExecutorService callerExecutor;
  private TestRecorder recorder;
  private File recordingFile;
  private CountDownLatch readRelease;
  private CountDownLatch queuedTaskRelease;

  @Before
  public void setUp() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    recordingFile = File.createTempFile("recording", ".wav", context.getCacheDir());
    Uri recordingUri = Uri.fromFile(recordingFile);
    audioRecord = mock(AudioRecord.class);
    producerExecutor = new NotifyingScheduledExecutor();
    callerExecutor = Executors.newSingleThreadExecutor();

    when(audioRecord.getSampleRate()).thenReturn(OutputFormat.LPCM_WAV.sampleRate);
    when(audioRecord.getChannelConfiguration()).thenReturn(OutputFormat.LPCM_WAV.channelMask);
    when(audioRecord.getAudioFormat()).thenReturn(AudioFormat.ENCODING_PCM_16BIT);
    when(audioRecord.getRecordingState()).thenReturn(AudioRecord.RECORDSTATE_RECORDING);
    when(audioRecord.read(any(ByteBuffer.class), anyInt(), eq(AudioRecord.READ_NON_BLOCKING)))
        .thenReturn(0);

    recorder = new TestRecorder(context, recordingUri, audioRecord, producerExecutor);
  }

  @After
  public void tearDown() {
    if (readRelease != null) {
      readRelease.countDown();
    }
    if (queuedTaskRelease != null) {
      queuedTaskRelease.countDown();
    }
    if (recorder != null) {
      try {
        recorder.stopRecordingBlocking();
      } catch (RuntimeException ignored) {
      }
      recorder.close();
    } else if (producerExecutor != null) {
      producerExecutor.shutdownNow();
    }
    if (callerExecutor != null) {
      callerExecutor.shutdownNow();
    }
    if (recordingFile != null) {
      recordingFile.delete();
    }
  }

  @Test
  public void startRecordingReportsAudioStartFailureBeforeReturning() {
    IllegalStateException failure = new IllegalStateException("start failed");
    doThrow(failure).when(audioRecord).startRecording();

    IllegalStateException thrown = null;
    try {
      recorder.startRecording();
    } catch (IllegalStateException e) {
      thrown = e;
    }

    assertThat(thrown).isSameInstanceAs(failure);
    assertThat(recorder.events()).containsExactly("prepared");
    verify(audioRecord).startRecording();
    assertThat(recorder.isRecording()).isFalse();
  }

  @Test
  public void stopRecordingWaitsForRunningReadBeforeFinalizing() throws Exception {
    CountDownLatch readStarted = new CountDownLatch(1);
    readRelease = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              recorder.addEvent("read started");
              readStarted.countDown();
              await(readRelease, "recording read release");
              recorder.addEvent("read finished");
              return 0;
            })
        .when(audioRecord)
        .read(any(ByteBuffer.class), anyInt(), eq(AudioRecord.READ_NON_BLOCKING));
    recorder.startRecording();
    assertThat(readStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

    Future<?> stop = callerExecutor.submit(recorder::stopRecordingBlocking);
    assertThat(producerExecutor.awaitBarrier()).isTrue();
    assertThat(recorder.events()).containsExactly("prepared", "read started").inOrder();

    readRelease.countDown();
    stop.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    assertThat(recorder.events())
        .containsExactly("prepared", "read started", "read finished", "finalized")
        .inOrder();
    assertThat(recorder.stopCount.get()).isEqualTo(1);
  }

  @Test
  public void stopRecordingCancelsQueuedReadBeforeFinalizing() throws Exception {
    CountDownLatch queuedTaskStarted = new CountDownLatch(1);
    queuedTaskRelease = new CountDownLatch(1);
    AtomicInteger readCount = new AtomicInteger();
    doAnswer(
            invocation -> {
              readCount.incrementAndGet();
              producerExecutor.execute(
                  () -> {
                    queuedTaskStarted.countDown();
                    await(queuedTaskRelease, "queued task release");
                  });
              return 0;
            })
        .when(audioRecord)
        .read(any(ByteBuffer.class), anyInt(), eq(AudioRecord.READ_NON_BLOCKING));
    recorder.startRecording();
    assertThat(queuedTaskStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

    Future<?> stop = callerExecutor.submit(recorder::stopRecordingBlocking);
    assertThat(producerExecutor.awaitBarrier()).isTrue();
    queuedTaskRelease.countDown();
    stop.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    assertThat(readCount.get()).isEqualTo(1);
    assertThat(recorder.events()).containsExactly("prepared", "finalized").inOrder();
    assertThat(recorder.stopCount.get()).isEqualTo(1);
  }

  private static void await(CountDownLatch latch, String description) {
    try {
      if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for " + description);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static final class NotifyingScheduledExecutor extends ScheduledThreadPoolExecutor {
    private final CountDownLatch barrierSubmitted = new CountDownLatch(1);

    NotifyingScheduledExecutor() {
      super(1);
    }

    @Override
    public Future<?> submit(Runnable task) {
      Future<?> future = super.submit(task);
      barrierSubmitted.countDown();
      return future;
    }

    boolean awaitBarrier() throws InterruptedException {
      return barrierSubmitted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }

  private static final class TestRecorder extends BaseCallRecorder {
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger stopCount = new AtomicInteger();

    TestRecorder(
        Context context,
        Uri recordingUri,
        AudioRecord audioRecord,
        NotifyingScheduledExecutor producerExecutor) {
      super(context, recordingUri, OutputFormat.LPCM_WAV, audioRecord, producerExecutor);
    }

    void addEvent(String event) {
      events.add(event);
    }

    List<String> events() {
      synchronized (events) {
        return new ArrayList<>(events);
      }
    }

    @Override
    protected void onRecordingStart(ParcelFileDescriptor pfd) {
      addEvent("prepared");
    }

    @Override
    protected void onPcmBufferRead(ByteBuffer pcmBuffer) {}

    @Override
    protected void onRecordingStop() {
      addEvent("finalized");
      stopCount.incrementAndGet();
    }

    @Override
    protected void reset() {}

    @Override
    protected void onClose() {}
  }
}
