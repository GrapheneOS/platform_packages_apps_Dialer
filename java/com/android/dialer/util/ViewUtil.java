/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.dialer.util;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.PowerManager;
import android.provider.Settings.Global;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.ViewTreeObserver.OnPreDrawListener;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.Locale;

/** Provides static functions to work with views */
public class ViewUtil {

  private ViewUtil() {}

  /** Similar to {@link Runnable} but takes a View parameter to operate on */
  public interface ViewRunnable {
    void run(@NonNull View view);
  }

  /**
   * Returns the width as specified in the LayoutParams
   *
   * @throws IllegalStateException Thrown if the view's width is unknown before a layout pass s
   */
  public static int getConstantPreLayoutWidth(View view) {
    // We haven't been layed out yet, so get the size from the LayoutParams
    final ViewGroup.LayoutParams p = view.getLayoutParams();
    if (p.width < 0) {
      throw new IllegalStateException(
          "Expecting view's width to be a constant rather " + "than a result of the layout pass");
    }
    return p.width;
  }

  /**
   * Returns a boolean indicating whether or not the view's layout direction is RTL
   *
   * @param view - A valid view
   * @return True if the view's layout direction is RTL
   */
  public static boolean isViewLayoutRtl(View view) {
    return view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
  }

  public static boolean isRtl() {
    return TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL;
  }

  public static void resizeText(TextView textView, int originalTextSize, int minTextSize) {
    final Paint paint = textView.getPaint();
    final int width = textView.getWidth();
    if (width == 0) {
      return;
    }
    textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, originalTextSize);
    float ratio = width / paint.measureText(textView.getText().toString());
    if (ratio <= 1.0f) {
      textView.setTextSize(
          TypedValue.COMPLEX_UNIT_PX, Math.max(minTextSize, originalTextSize * ratio));
    }
  }

  /** Runs a piece of code just before the next draw, after layout and measurement */
  public static void doOnPreDraw(
      @NonNull final View view, final boolean drawNextFrame, final Runnable runnable) {
    view.getViewTreeObserver()
        .addOnPreDrawListener(
            new OnPreDrawListener() {
              @Override
              public boolean onPreDraw() {
                view.getViewTreeObserver().removeOnPreDrawListener(this);
                runnable.run();
                return drawNextFrame;
              }
            });
  }

  public static void doOnPreDraw(
      @NonNull final View view, final boolean drawNextFrame, final ViewRunnable runnable) {
    view.getViewTreeObserver()
        .addOnPreDrawListener(
            new OnPreDrawListener() {
              @Override
              public boolean onPreDraw() {
                view.getViewTreeObserver().removeOnPreDrawListener(this);
                runnable.run(view);
                return drawNextFrame;
              }
            });
  }

  public static void doOnGlobalLayout(@NonNull final View view, final ViewRunnable runnable) {
    view.getViewTreeObserver()
        .addOnGlobalLayoutListener(
            new OnGlobalLayoutListener() {
              @Override
              public void onGlobalLayout() {
                view.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                runnable.run(view);
              }
            });
  }

  /**
   * Returns {@code true} if animations should be disabled.
   *
   * <p>Animations should be disabled if {@link
   * android.provider.Settings.Global#ANIMATOR_DURATION_SCALE} is set to 0 through system settings
   * or the device is in power save mode.
   */
  public static boolean areAnimationsDisabled(Context context) {
    ContentResolver contentResolver = context.getContentResolver();
    PowerManager powerManager = context.getSystemService(PowerManager.class);
    return Settings.Global.getFloat(contentResolver, Global.ANIMATOR_DURATION_SCALE, 1.0f) == 0
        || powerManager.isPowerSaveMode();
  }

  /**
   * Pads the activity's content view by the system bar and display cutout insets, restoring the
   * layout apps had before edge-to-edge.
   *
   * <p>Apps targeting SDK 35 and above always get an edge-to-edge window, and SDK 36 dropped the
   * {@code android:windowOptOutEdgeToEdgeEnforcement} theme opt-out, so every activity that draws
   * its own chrome has to consume the insets itself. {@code adjustResize} is likewise ignored for
   * edge-to-edge windows, so the IME inset is folded in for the activities that asked for it,
   * leaving {@code adjustPan} and {@code adjustNothing} activities to keep managing the keyboard
   * themselves.
   *
   * <p>Edge-to-edge also turned {@code android:statusBarColor} into a no-op, so the strip behind
   * the status bar is repainted with the theme's {@code colorPrimaryDark} — what the platform
   * used to draw there, and what the status bar icon colours were picked against. Themes whose
   * {@code colorPrimaryDark} already matches their window background get an invisible no-op.
   *
   * <p>Call from {@code onCreate} after {@code setContentView}. Do not call it for an activity
   * whose own views handle insets, such as the in-call screens — this consumes them.
   */
  public static void applyWindowInsets(@NonNull Activity activity) {
    final int adjust =
        activity.getWindow().getAttributes().softInputMode
            & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
    final int types =
        WindowInsetsCompat.Type.systemBars()
            | WindowInsetsCompat.Type.displayCutout()
            | (adjust == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                ? WindowInsetsCompat.Type.ime()
                : 0);

    final TypedArray themeColors =
        activity.obtainStyledAttributes(new int[] {android.R.attr.colorPrimaryDark});
    final View statusBarScrim = new View(activity);
    statusBarScrim.setBackgroundColor(themeColors.getColor(0, Color.TRANSPARENT));
    themeColors.recycle();
    statusBarScrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    ((ViewGroup) activity.getWindow().getDecorView())
        .addView(
            statusBarScrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));

    ViewCompat.setOnApplyWindowInsetsListener(
        activity.findViewById(android.R.id.content),
        (view, windowInsets) -> {
          final Insets insets = windowInsets.getInsets(types);
          view.setPadding(insets.left, insets.top, insets.right, insets.bottom);

          // Any ancestor that fits system windows has already trimmed the insets reaching the
          // content view — AppCompat's ActionBarOverlayLayout does, to seat the ActionBar below
          // the status bar — so size the scrim from the root insets, which are never consumed.
          final WindowInsetsCompat rootInsets = ViewCompat.getRootWindowInsets(view);
          if (rootInsets != null) {
            final int height =
                rootInsets
                    .getInsets(
                        WindowInsetsCompat.Type.statusBars()
                            | WindowInsetsCompat.Type.displayCutout())
                    .top;
            final ViewGroup.LayoutParams params = statusBarScrim.getLayoutParams();
            if (params.height != height) {
              params.height = height;
              statusBarScrim.setLayoutParams(params);
            }
          }
          return WindowInsetsCompat.CONSUMED;
        });
  }
}
