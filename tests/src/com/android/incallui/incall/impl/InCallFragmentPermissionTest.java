package com.android.incallui.incall.impl;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import androidx.fragment.app.FragmentController;
import androidx.fragment.app.FragmentHostCallback;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.dialer.common.FragmentUtils;
import com.android.incallui.incall.protocol.InCallButtonUiDelegate;
import com.android.incallui.incall.protocol.InCallButtonUiDelegateFactory;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class InCallFragmentPermissionTest {

  @After
  public void tearDown() {
    FragmentUtils.setParentForTesting(null);
  }

  @Test
  public void recordAudioPermissionGrantIsReportedToDelegate() throws Exception {
    InCallButtonUiDelegate delegate = mock(InCallButtonUiDelegate.class);
    InCallFragment fragment = createFragmentOnMain(delegate);

    runOnMain(
        () -> {
          fragment.onRequestPermissionsResult(
              InCallFragment.REQUEST_CODE_CALL_RECORD_PERMISSION,
              new String[] {Manifest.permission.RECORD_AUDIO},
              new int[] {PackageManager.PERMISSION_GRANTED});
        });

    verify(delegate).onCallRecordingPermissionsResult(true /* allGranted */);
  }

  @Test
  public void recordAudioPermissionDenialIsReportedToDelegate() throws Exception {
    InCallButtonUiDelegate delegate = mock(InCallButtonUiDelegate.class);
    InCallFragment fragment = createFragmentOnMain(delegate);

    runOnMain(
        () -> {
          fragment.onRequestPermissionsResult(
              InCallFragment.REQUEST_CODE_CALL_RECORD_PERMISSION,
              new String[] {Manifest.permission.RECORD_AUDIO},
              new int[] {PackageManager.PERMISSION_DENIED});
        });

    verify(delegate).onCallRecordingPermissionsResult(false /* allGranted */);
  }

  private static InCallFragment createFragmentOnMain(InCallButtonUiDelegate delegate) {
    AtomicReference<InCallFragment> fragment = new AtomicReference<>();
    // InCallFragment creates a Handler in its constructor, so build it on the main looper.
    runOnMain(
        () -> {
          FragmentUtils.setParentForTesting(
              (InCallButtonUiDelegateFactory) () -> delegate);
          InCallFragment inCallFragment = new InCallFragment();
          FragmentController controller = FragmentController.createController(new BareHost());
          controller.attachHost(null /* parent */);
          controller
              .getSupportFragmentManager()
              .beginTransaction()
              .add(inCallFragment, null /* tag */)
              .commitNow();
          controller.dispatchCreate();
          fragment.set(inCallFragment);
        });
    return fragment.get();
  }

  private static final class BareHost extends FragmentHostCallback<Object> {
    BareHost() {
      super(
          InstrumentationRegistry.getInstrumentation().getTargetContext(),
          new Handler(Looper.getMainLooper()),
          0 /* windowAnimations */);
    }

    @Override
    public Object onGetHost() {
      return null;
    }
  }

  private static void runOnMain(Runnable runnable) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable);
  }
}
