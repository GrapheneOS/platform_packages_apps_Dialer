/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.incallui.audioroute;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff.Mode;
import android.os.Bundle;
import android.provider.Settings;
import android.telecom.CallAudioState;
import android.telecom.CallEndpoint;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import com.android.dialer.R;
import com.android.dialer.common.FragmentUtils;
import com.android.dialer.common.LogUtil;
import com.android.dialer.logging.DialerImpression;
import com.android.dialer.logging.Logger;
import com.android.dialer.theme.base.ThemeComponent;
import com.android.incallui.audiomode.AudioModeProvider;
import com.android.incallui.call.CallList;
import com.android.incallui.call.DialerCall;
import com.android.incallui.call.TelecomAdapter;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import java.util.List;

/** Shows picker for audio routes */
public class AudioRouteSelectorDialogFragment extends BottomSheetDialogFragment {

  public static final String TAG = "AudioRouteSelectorDialogFragment";
  private static final String ARG_AUDIO_STATE = "audio_state";

  /** Called when an audio route is picked */
  public interface AudioRouteSelectorPresenter {
    void onAudioRouteSelected(int audioRoute);

    void onAudioRouteSelectorDismiss();
  }

  public static AudioRouteSelectorDialogFragment newInstance(CallAudioState audioState) {
    AudioRouteSelectorDialogFragment fragment = new AudioRouteSelectorDialogFragment();
    Bundle args = new Bundle();
    args.putParcelable(ARG_AUDIO_STATE, audioState);
    fragment.setArguments(args);
    return fragment;
  }

  @Override
  public void onAttach(Context context) {
    super.onAttach(context);
    FragmentUtils.checkParent(this, AudioRouteSelectorPresenter.class);
  }

  @Override
  public Dialog onCreateDialog(final Bundle savedInstanceState) {
    LogUtil.i("AudioRouteSelectorDialogFragment.onCreateDialog", null);
    Dialog dialog = super.onCreateDialog(savedInstanceState);
    dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
    if (Settings.canDrawOverlays(getContext())) {
      dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
    }
    return dialog;
  }

  @Nullable
  @Override
  public View onCreateView(
      LayoutInflater layoutInflater, @Nullable ViewGroup viewGroup, @Nullable Bundle bundle) {
    View view = layoutInflater.inflate(R.layout.audioroute_selector, viewGroup, false);
    CallAudioState audioState = getArguments().getParcelable(ARG_AUDIO_STATE);

    AudioModeProvider audioModeProvider = AudioModeProvider.getInstance();
    CallEndpoint currentCallEndpoint = audioModeProvider.getCurrentCallEndpoint();
    List<CallEndpoint> bluetoothEndpoints =
        audioModeProvider.getAvailableCallEndpoints().stream()
            .filter(endpoint -> endpoint.getEndpointType() == CallEndpoint.TYPE_BLUETOOTH)
            .toList();
    if (!bluetoothEndpoints.isEmpty()) {
      for (CallEndpoint endpoint : bluetoothEndpoints) {
        boolean selected =
            endpoint.equals(currentCallEndpoint)
                || (currentCallEndpoint == null
                    && bluetoothEndpoints.size() == 1
                    && audioState.getRoute() == CallAudioState.ROUTE_BLUETOOTH);
        TextView textView = createBluetoothItem(endpoint, selected);
        ((LinearLayout) view).addView(textView, 0);
      }
    } else {
      TextView textView =
          (TextView) getLayoutInflater().inflate(R.layout.audioroute_item, null, false);
      textView.setText(getString(R.string.audioroute_bluetooth));
      initItem(
          textView,
          CallAudioState.ROUTE_BLUETOOTH,
          audioState,
          DialerImpression.Type.IN_CALL_SWITCH_AUDIO_ROUTE_BLUETOOTH);
      ((LinearLayout) view).addView(textView, 0);
    }

    initItem(
        (TextView) view.findViewById(R.id.audioroute_speaker),
        CallAudioState.ROUTE_SPEAKER,
        audioState,
        DialerImpression.Type.IN_CALL_SWITCH_AUDIO_ROUTE_SPEAKER);
    initItem(
        (TextView) view.findViewById(R.id.audioroute_headset),
        CallAudioState.ROUTE_WIRED_HEADSET,
        audioState,
        DialerImpression.Type.IN_CALL_SWITCH_AUDIO_ROUTE_WIRED_HEADSET);
    initItem(
        (TextView) view.findViewById(R.id.audioroute_earpiece),
        CallAudioState.ROUTE_EARPIECE,
        audioState,
        DialerImpression.Type.IN_CALL_SWITCH_AUDIO_ROUTE_EARPIECE);

    // TODO(a bug): set peak height correctly to fully expand it in landscape mode.
    return view;
  }

  @Override
  public void onCancel(DialogInterface dialogInterface) {
    super.onCancel(dialogInterface);
    FragmentUtils.getParentUnsafe(
            AudioRouteSelectorDialogFragment.this, AudioRouteSelectorPresenter.class)
        .onAudioRouteSelectorDismiss();
  }

  private void initItem(
      TextView item,
      final int itemRoute,
      CallAudioState audioState,
      DialerImpression.Type impressionType) {
    int selectedColor = ThemeComponent.get(getContext()).theme().getColorPrimary();
    if ((audioState.getSupportedRouteMask() & itemRoute) == 0) {
      item.setVisibility(View.GONE);
    } else if (audioState.getRoute() == itemRoute) {
      item.setSelected(true);
      item.setTextColor(selectedColor);
      item.setCompoundDrawableTintList(ColorStateList.valueOf(selectedColor));
      item.setCompoundDrawableTintMode(Mode.SRC_ATOP);
    }
    item.setOnClickListener(
        (v) -> {
          logCallAudioRouteImpression(impressionType);
          FragmentUtils.getParentUnsafe(
                  AudioRouteSelectorDialogFragment.this, AudioRouteSelectorPresenter.class)
              .onAudioRouteSelected(itemRoute);
          dismiss();
        });
  }

  private TextView createBluetoothItem(CallEndpoint callEndpoint, boolean selected) {
    int selectedColor = ThemeComponent.get(getContext()).theme().getColorPrimary();
    TextView textView =
        (TextView) getLayoutInflater().inflate(R.layout.audioroute_item, null, false);
    CharSequence endpointName = callEndpoint.getEndpointName();
    textView.setText(
        TextUtils.isEmpty(endpointName) ? getString(R.string.audioroute_bluetooth) : endpointName);
    if (selected) {
      textView.setSelected(true);
      textView.setTextColor(selectedColor);
      textView.setCompoundDrawableTintList(ColorStateList.valueOf(selectedColor));
      textView.setCompoundDrawableTintMode(Mode.SRC_ATOP);
    }
    textView.setOnClickListener(
        (v) -> {
          logCallAudioRouteImpression(DialerImpression.Type.IN_CALL_SWITCH_AUDIO_ROUTE_BLUETOOTH);
          TelecomAdapter.getInstance().requestCallEndpointChange(callEndpoint);
          FragmentUtils.getParentUnsafe(
                  AudioRouteSelectorDialogFragment.this, AudioRouteSelectorPresenter.class)
              .onAudioRouteSelectorDismiss();
          dismiss();
        });

    return textView;
  }

  private void logCallAudioRouteImpression(DialerImpression.Type impressionType) {
    DialerCall dialerCall = CallList.getInstance().getOutgoingCall();
    if (dialerCall == null) {
      dialerCall = CallList.getInstance().getActiveOrBackgroundCall();
    }

    if (dialerCall != null) {
      Logger.get(getContext())
          .logCallImpression(
              impressionType, dialerCall.getUniqueCallId(), dialerCall.getTimeAddedMs());
    } else {
      Logger.get(getContext()).logImpression(impressionType);
    }
  }
}
