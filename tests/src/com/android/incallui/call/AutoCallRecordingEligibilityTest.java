package com.android.incallui.call;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.android.dialer.callrecord.CallRecordingPreferences;
import com.android.incallui.call.AutoCallRecordingEligibility.AutoRecordDecision;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class AutoCallRecordingEligibilityTest {

  @Test
  public void incomingSwitchCannotRecordWithoutMicrophonePermission() {
    assertThat(AutoRecordDecision.MISSING_MICROPHONE_PERMISSION.canRecordIncomingCall())
        .isFalse();
  }

  @Test
  public void incomingSwitchCannotRecordWithoutMicrophoneAndContactsPermissions() {
    assertThat(
            AutoRecordDecision.MISSING_MICROPHONE_AND_CONTACTS_PERMISSIONS
                .canRecordIncomingCall())
        .isFalse();
  }

  @Test
  public void incomingSwitchCanRecordWithoutContactsPermission() {
    assertThat(AutoRecordDecision.MISSING_CONTACTS_PERMISSION.canRecordIncomingCall())
        .isTrue();
  }

  @Test
  public void incomingSwitchCannotRecordBeforeWarningIsShown() {
    assertThat(AutoRecordDecision.WARNING_NOT_PRESENTED.canRecordIncomingCall()).isFalse();
  }

  @Test
  public void settingsShowsPermissionNoticeWithoutMicrophonePermission() {
    assertThat(AutoRecordDecision.MISSING_MICROPHONE_PERMISSION.shouldShowPermissionNotice())
        .isTrue();
  }

  @Test
  public void settingsShowsPermissionNoticeWithoutContactsPermission() {
    assertThat(AutoRecordDecision.MISSING_CONTACTS_PERMISSION.shouldShowPermissionNotice())
        .isTrue();
  }

  @Test
  public void settingsShowsPermissionNoticeWithoutMicrophoneAndContactsPermissions() {
    assertThat(
            AutoRecordDecision.MISSING_MICROPHONE_AND_CONTACTS_PERMISSIONS
                .shouldShowPermissionNotice())
        .isTrue();
  }

  @Test
  public void answerUiShowsPermissionNoticeWithoutMicrophonePermission() {
    assertThat(
            AutoRecordDecision.MISSING_MICROPHONE_PERMISSION
                .isMicrophonePermissionMissing())
        .isTrue();
  }

  @Test
  public void answerUiHidesPermissionNoticeWhenOnlyContactsPermissionMissing() {
    assertThat(
            AutoRecordDecision.MISSING_CONTACTS_PERMISSION
                .isMicrophonePermissionMissing())
        .isFalse();
  }

  @Test
  public void permissionDecisionReportsBothMissingPermissions() {
    assertThat(
            AutoCallRecordingEligibility.getPermissionDecision(
                false /* hasMicrophonePermission */,
                false /* hasContactsPermission */,
                true /* requireContactsPermission */))
        .isEqualTo(AutoRecordDecision.MISSING_MICROPHONE_AND_CONTACTS_PERMISSIONS);
  }

  @Test
  public void automaticRecordingDecisionRequiresConfiguredPreferences() {
    assertThat(
            AutoCallRecordingEligibility.getDecision(
                true /* hasCall */,
                false /* isVideoCall */,
                true /* snapshotReady */,
                preferencesBuilder().setRecordingWarningPresented(true).build(),
                true /* hasMicrophonePermission */,
                true /* hasContactsPermission */,
                true /* requireContactsPermission */))
        .isEqualTo(AutoRecordDecision.NOT_CONFIGURED);
  }

  @Test
  public void automaticRecordingDecisionAllowsConfiguredAudioCall() {
    assertThat(
            AutoCallRecordingEligibility.getDecision(
                true /* hasCall */,
                false /* isVideoCall */,
                true /* snapshotReady */,
                preferencesBuilder()
                    .setRecordingWarningPresented(true)
                    .setAutoRecordNonContacts(true)
                    .build(),
                true /* hasMicrophonePermission */,
                true /* hasContactsPermission */,
                true /* requireContactsPermission */))
        .isEqualTo(AutoRecordDecision.ELIGIBLE);
  }

  @Test
  public void settingsHidesPermissionNoticeWhenAutomaticRecordingDisabled() {
    assertThat(AutoRecordDecision.NOT_CONFIGURED.shouldShowPermissionNotice()).isFalse();
  }

  @Test
  public void settingsHidesPermissionNoticeBeforeWarningIsShown() {
    assertThat(AutoRecordDecision.SNAPSHOT_NOT_READY.shouldShowPermissionNotice())
        .isFalse();
  }

  private static CallRecordingPreferences.Builder preferencesBuilder() {
    return CallRecordingPreferences.newBuilder().setSharedPreferencesMigrated(true);
  }
}
