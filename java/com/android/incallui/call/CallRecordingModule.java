package com.android.incallui.call;

import android.content.Context;
import com.android.dialer.inject.ApplicationContext;
import com.android.dialer.inject.DialerVariant;
import com.android.dialer.inject.InstallIn;
import dagger.Module;
import dagger.Provides;

/** Module for call recording dependencies. */
@InstallIn(variants = {DialerVariant.DIALER_TEST})
@Module
public final class CallRecordingModule {

  private CallRecordingModule() {}

  @Provides
  static CallRecordingDependencies provideCallRecordingDependencies(
      @ApplicationContext Context context) {
    return CallRecordingDefaultDependencies.create(context);
  }
}
