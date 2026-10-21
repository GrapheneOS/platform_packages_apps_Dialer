package com.android.incallui.call;

import android.content.Context;
import com.android.dialer.inject.HasRootComponent;
import com.android.dialer.inject.IncludeInDialerRoot;
import dagger.Subcomponent;

/** Dagger component for call recording dependencies. */
@Subcomponent
public abstract class CallRecordingComponent {

  public abstract CallRecordingDependencies callRecordingDependencies();

  public static CallRecordingComponent get(Context context) {
    return ((HasComponent) ((HasRootComponent) context.getApplicationContext()).component())
        .callRecordingComponent();
  }

  /** Used to refer to the root application component. */
  @IncludeInDialerRoot
  public interface HasComponent {
    CallRecordingComponent callRecordingComponent();
  }
}
