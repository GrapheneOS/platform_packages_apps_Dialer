package com.android.dialer.callrecord.impl;

import static com.google.common.truth.Truth.assertThat;

import android.media.MediaRecorder;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CallRecorderServiceLifecycleTest {

  /** The legacy service can be destroyed without ever starting a recording. */
  @Test
  public void legacyOnDestroyWithoutRecorderLeavesRecorderNull() {
    CallRecorderService service = new CallRecorderService();

    service.onDestroy();

    assertThat(service.getMediaRecorderForTesting()).isNull();
  }

  /**
   * Keep the destruction path tolerant of partial recorder state even though start/stop are
   * synchronized and should not normally expose this interleaving.
   */
  @Test
  public void legacyOnDestroyStopsPartialRecorderWithoutRecordingMetadata() {
    CallRecorderService service = new CallRecorderService();
    MediaRecorder recorder = new MediaRecorder();
    service.setMediaRecorderForTesting(recorder);
    service.setCurrentRecordingForTesting(null);

    try {
      service.onDestroy();
      assertThat(service.getMediaRecorderForTesting()).isNull();
    } finally {
      MediaRecorder remainingRecorder = service.getMediaRecorderForTesting();
      if (remainingRecorder != null) {
        remainingRecorder.release();
      }
    }
  }
}
