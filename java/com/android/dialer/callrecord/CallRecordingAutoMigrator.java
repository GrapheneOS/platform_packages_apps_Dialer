/*
 * Copyright (C) 2016 The Android Open Source Project
 * Copyright (C) 2020 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dialer.callrecord;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.SparseArray;

import com.android.dialer.common.Assert;
import com.android.dialer.common.LogUtil;
import com.android.dialer.common.concurrent.DialerExecutor.Worker;
import com.android.dialer.common.concurrent.DialerExecutorFactory;
import com.android.voicemail.impl.mail.utils.LogUtils;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;

public class CallRecordingAutoMigrator {
  private static final String TAG = "CallRecordingAutoMigrator";

  @NonNull
  private final Context appContext;
  @NonNull private final DialerExecutorFactory dialerExecutorFactory;

  public CallRecordingAutoMigrator(
      @NonNull Context appContext,
      @NonNull DialerExecutorFactory dialerExecutorFactory) {
    this.appContext = Assert.isNotNull(appContext);
    this.dialerExecutorFactory = Assert.isNotNull(dialerExecutorFactory);
  }

  public void asyncAutoMigrate() {
    dialerExecutorFactory
        .createNonUiTaskBuilder(new ShouldAttemptAutoMigrate(appContext))
        .onSuccess(this::autoMigrate)
        .build()
        .executeParallel(null);
  }

  private void autoMigrate(boolean shouldAttemptAutoMigrate) {
    if (!shouldAttemptAutoMigrate) {
      return;
    }

    final ContentResolver cr = appContext.getContentResolver();
    final File dir = Environment.getExternalStoragePublicDirectory("CallRecordings");
    for (File recording : dir.listFiles()) {
      OutputStream os = null;
      try {
        long creationTime = System.currentTimeMillis();

        // create media store entry for recording
        Uri uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            CallRecording.generateMediaInsertValues(recording.getName(), creationTime));
        os = cr.openOutputStream(uri);

        // copy file contents to media store stream
        Files.copy(recording.toPath(), os);

        // mark recording as complete
        cr.update(uri, CallRecording.generateCompletedValues(), null, null);

        // delete file
        recording.delete();
      } catch (Exception e) {
        LogUtils.e(TAG, "Failed migrating call recording " + recording, e);
      } finally {
        if (os != null) {
          IOUtils.closeQuietly(os);
        }
      }
    }

    if (dir.listFiles().length == 0) {
      dir.delete();
    }

  }

  private static class ShouldAttemptAutoMigrate implements Worker<Void, Boolean> {
    private final Context appContext;

    ShouldAttemptAutoMigrate(Context appContext) {
      this.appContext = appContext;
    }

    @Nullable
    @Override
    public Boolean doInBackground(@Nullable Void input) {
      if (appContext.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
          != PackageManager.PERMISSION_GRANTED) {
        LogUtil.i(TAG, "not attempting auto-migrate: no storage permission");
        return false;
      }

      final File dir = Environment.getExternalStoragePublicDirectory("CallRecordings");
      if (!dir.exists()) {
        LogUtil.i(TAG, "not attempting auto-migrate: no recordings present");
        return false;
      }

      return true;
    }
  }
}