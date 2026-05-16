package com.android.dialer.callrecord;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.Pair;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CallRecordingPreferencesStoreTest {

  private static final String DEFAULT_AUDIO_SOURCE = "4";
  private static final String AUDIO_SOURCE_MIC = "1";
  private static final String LEGACY_OUTPUT_FORMAT_AMR_WB = "1";
  private static final String LEGACY_OUTPUT_FORMAT_WAV = "2";
  // Legacy SharedPreferences XML keys are deliberately pinned here rather than read from
  // production constants, so these tests do not follow future DataStore or settings key changes.
  private static final String LEGACY_KEY_USE_V2 = "call_recording_use_v2";
  private static final String LEGACY_KEY_AUDIO_SOURCE = "call_recording_audio_source";
  private static final String LEGACY_KEY_OUTPUT_FORMAT = "call_recording_output_format";
  private static final String LEGACY_KEY_OUTPUT_FORMAT_V2 = "call_recording_output_format_v2";
  private static final RecordingOutputFormat DEFAULT_OUTPUT_FORMAT =
      RecordingOutputFormat.AAC_MPEG_4;

  private Context context;
  private SharedPreferences legacyPrefs;

  @Before
  public void setUp() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    legacyPrefs = CallRecordingPreferencesStore.getLegacySharedPreferencesForTesting(context);
    resetStoreAndLegacyPrefs();
  }

  @After
  public void tearDown() {
    resetStoreAndLegacyPrefs();
    AutoCallRecordingStaleContactCleanupJobService.cancelJob(context);
  }

  @Test
  public void migrationCopiesLegacyRecorderPreferencesAndClearsLegacyKeys() {
    CallRecordingPreferencesStore.resetForTesting(context, false /* sharedPreferencesMigrated */);
    assertThat(
            legacyPrefs
                .edit()
                .putBoolean(LEGACY_KEY_USE_V2, true)
                .putString(LEGACY_KEY_AUDIO_SOURCE, AUDIO_SOURCE_MIC)
                .putString(LEGACY_KEY_OUTPUT_FORMAT, LEGACY_OUTPUT_FORMAT_AMR_WB)
                .putString(LEGACY_KEY_OUTPUT_FORMAT_V2, LEGACY_OUTPUT_FORMAT_WAV)
                .commit())
        .isTrue();

    CallRecordingPreferences preferences = CallRecordingPreferencesStore.readBlocking(context);
    assertThat(preferences.getUseCallRecordingV2()).isTrue();
    assertThat(getAudioSource(preferences)).isEqualTo(AUDIO_SOURCE_MIC);
    assertThat(getOutputFormat(preferences)).isEqualTo(RecordingOutputFormat.AMR_WB);
    assertThat(getOutputFormatV2(preferences)).isEqualTo(RecordingOutputFormat.LPCM_WAV);
    assertLegacyRecorderKeysCleared();
  }

  @Test
  public void preferenceLoadMigratesLegacyValues() throws Exception {
    CallRecordingPreferencesStore.resetForTesting(context, false /* sharedPreferencesMigrated */);
    assertThat(
            legacyPrefs
                .edit()
                .putBoolean(LEGACY_KEY_USE_V2, true)
                .commit())
        .isTrue();

    assertThat(loadPreferencesAsync().get(5, TimeUnit.SECONDS).getUseCallRecordingV2()).isTrue();
    assertLegacyRecorderKeysCleared();
  }

  @Test
  public void cancelingOnePreferenceLoadDoesNotStopAnotherLoad() throws Exception {
    CallRecordingPreferencesStore.resetForTesting(context, false /* sharedPreferencesMigrated */);
    assertThat(
            legacyPrefs
                .edit()
                .putBoolean(LEGACY_KEY_USE_V2, true)
                .commit())
        .isTrue();

    ListenableFuture<CallRecordingPreferences> canceledWaiter = loadPreferencesAsync();
    ListenableFuture<CallRecordingPreferences> continuingWaiter = loadPreferencesAsync();

    canceledWaiter.cancel(true);

    CallRecordingPreferences preferences = continuingWaiter.get(5, TimeUnit.SECONDS);
    assertThat(preferences.getUseCallRecordingV2()).isTrue();
  }

  @Test
  public void preferenceLoadReadsUpdatedDataStoreValue() throws Exception {
    CallRecordingPreferencesStore.updateBlocking(
        context, builder -> builder.setUseCallRecordingV2(true));

    assertThat(loadPreferencesAsync().get(5, TimeUnit.SECONDS).getUseCallRecordingV2()).isTrue();
  }

  @Test
  public void migrationIgnoresInvalidLegacyOutputFormats() {
    CallRecordingPreferencesStore.resetForTesting(context, false /* sharedPreferencesMigrated */);
    assertThat(
            legacyPrefs
                .edit()
                .putString(LEGACY_KEY_OUTPUT_FORMAT, "bad")
                .putString(LEGACY_KEY_OUTPUT_FORMAT_V2, "99")
                .commit())
        .isTrue();

    CallRecordingPreferences preferences = CallRecordingPreferencesStore.readBlocking(context);
    assertThat(getOutputFormat(preferences)).isEqualTo(DEFAULT_OUTPUT_FORMAT);
    assertThat(getOutputFormatV2(preferences)).isEqualTo(DEFAULT_OUTPUT_FORMAT);
    assertLegacyRecorderKeysCleared();
  }

  @Test
  public void migrationDoesNotImportAutoRecordingKeys() {
    CallRecordingPreferencesStore.resetForTesting(context, false /* sharedPreferencesMigrated */);
    assertThat(
            legacyPrefs
                .edit()
                .putBoolean(CallRecordingPreferencesStore.KEY_AUTO_RECORD_NON_CONTACTS, true)
                .putBoolean(
                    CallRecordingPreferencesStore.KEY_AUTO_RECORD_SELECTED_NUMBERS_ENABLED, true)
                .putStringSet(
                    CallRecordingPreferencesStore.KEY_AUTO_RECORD_SELECTED_NUMBERS,
                    new HashSet<>(Arrays.asList("+15551230001")))
                .putBoolean(
                    CallRecordingPreferencesStore.KEY_AUTO_RECORDING_SET_AT_LEAST_ONCE, true)
                .putBoolean(CallRecordingPreferencesStore.KEY_RECORDING_WARNING_PRESENTED, true)
                .commit())
        .isTrue();

    CallRecordingPreferencesStore.readBlocking(context);

    CallRecordingPreferences preferences = CallRecordingPreferencesStore.readBlocking(context);
    assertThat(preferences.getAutoRecordNonContacts()).isFalse();
    assertThat(preferences.getAutoRecordSelectedNumbersEnabled()).isFalse();
    assertThat(CallRecordingPreferenceValues.selectedNumbers(preferences)).isEmpty();
    assertThat(preferences.getAutoRecordingSetAtLeastOnce()).isFalse();
    assertThat(preferences.getRecordingWarningPresented()).isFalse();
  }

  @Test
  public void outputFormatRoundTripsTypedValuesForV1AndV2() {
    CallRecordingPreferencesStore.updateBlocking(
        context,
        builder ->
            builder
                .setCallRecordingOutputFormat(RecordingOutputFormat.AMR_WB)
                .setCallRecordingOutputFormatV2(RecordingOutputFormat.LPCM_WAV));

    CallRecordingPreferences preferences = CallRecordingPreferencesStore.readBlocking(context);
    assertThat(getOutputFormat(preferences)).isEqualTo(RecordingOutputFormat.AMR_WB);
    assertThat(getOutputFormatV2(preferences)).isEqualTo(RecordingOutputFormat.LPCM_WAV);
  }

  @Test
  public void selectedNumberStorageIgnoresBlankNumbersAndMatchesCanonicalNumbers() {
    CallRecordingPreferences.Builder builder =
        CallRecordingPreferences.newBuilder().setSharedPreferencesMigrated(true);

    CallRecordingPreferenceValues.setSelectedNumbers(
        builder, new HashSet<>(Arrays.asList("+15551230002", "", "+15551230001")));
    CallRecordingPreferences preferences = builder.build();

    assertThat(preferences.getAutoRecordSelectedNumbersList())
        .containsExactly("+15551230001", "+15551230002")
        .inOrder();
    assertThat(CallRecordingPreferenceValues.selectedNumbers(preferences))
        .containsExactly("+15551230001", "+15551230002");
    assertThat(CallRecordingPreferenceValues.containsSelectedNumber(preferences, "+15551230001"))
        .isTrue();
    assertThat(CallRecordingPreferenceValues.containsSelectedNumber(preferences, null)).isFalse();
  }

  @Test
  public void addingSelectedNumberReportsWhetherTheNumberWasNew() {
    CallRecordingPreferences preferences =
        CallRecordingPreferences.newBuilder().setSharedPreferencesMigrated(true).build();

    Pair<CallRecordingPreferences, CallRecordingPreferenceValues.SelectedNumberAddResult> added =
        CallRecordingPreferenceValues.addSelectedNumber(preferences, "+15551230001");
    Pair<CallRecordingPreferences, CallRecordingPreferenceValues.SelectedNumberAddResult>
        alreadyAdded =
            CallRecordingPreferenceValues.addSelectedNumber(added.getFirst(), "+15551230001");

    assertThat(added.getSecond())
        .isEqualTo(CallRecordingPreferenceValues.SelectedNumberAddResult.ADDED);
    assertThat(CallRecordingPreferenceValues.selectedNumbers(added.getFirst()))
        .containsExactly("+15551230001");
    assertThat(alreadyAdded.getSecond())
        .isEqualTo(CallRecordingPreferenceValues.SelectedNumberAddResult.ALREADY_ADDED);
    assertThat(CallRecordingPreferenceValues.selectedNumbers(alreadyAdded.getFirst()))
        .containsExactly("+15551230001");
  }

  @Test
  public void callRecordingBackupSkipsSensitivePreferenceKeys() {
    assertThat(
            CallRecordingPreferenceValues.isSensitiveBackupKey(
                CallRecordingPreferencesStore.KEY_AUTO_RECORD_NON_CONTACTS))
        .isTrue();
    assertThat(
            CallRecordingPreferenceValues.isSensitiveBackupKey(
                CallRecordingPreferencesStore.KEY_RECORDING_WARNING_PRESENTED))
        .isTrue();
    assertThat(CallRecordingPreferenceValues.isSensitiveBackupKey("unrelated_preference"))
        .isFalse();
  }

  @Test
  public void lockedUserReturnsDefaultsAndDoesNotTouchStorage() throws Exception {
    CallRecordingPreferencesStore.updateBlocking(
        context, builder -> builder.setUseCallRecordingV2(true));
    Context lockedContext = LockedUserContext.wrap(context);
    AtomicBoolean writeAttempted = new AtomicBoolean();

    assertThat(CallRecordingPreferencesStore.readBlocking(lockedContext))
        .isEqualTo(CallRecordingPreferences.getDefaultInstance());
    assertThat(CallRecordingPreferencesStore.loadAsync(lockedContext).get(5, TimeUnit.SECONDS))
        .isEqualTo(CallRecordingPreferences.getDefaultInstance());
    assertThat(
            CallRecordingPreferencesStore.updateBlocking(
                lockedContext,
                builder -> {
                  writeAttempted.set(true);
                  builder.setUseCallRecordingV2(false);
                }))
        .isEqualTo(CallRecordingPreferences.getDefaultInstance());

    assertThat(writeAttempted.get()).isFalse();
    assertThat(CallRecordingPreferencesStore.readBlocking(context).getUseCallRecordingV2())
        .isTrue();
  }

  private void resetStoreAndLegacyPrefs() {
    CallRecordingPreferencesStore.resetForTesting(context, true /* sharedPreferencesMigrated */);
    assertThat(
            legacyPrefs
                .edit()
                .remove(LEGACY_KEY_USE_V2)
                .remove(LEGACY_KEY_AUDIO_SOURCE)
                .remove(LEGACY_KEY_OUTPUT_FORMAT)
                .remove(LEGACY_KEY_OUTPUT_FORMAT_V2)
                .remove(CallRecordingPreferencesStore.KEY_AUTO_RECORD_NON_CONTACTS)
                .remove(CallRecordingPreferencesStore.KEY_AUTO_RECORD_SELECTED_NUMBERS_ENABLED)
                .remove(CallRecordingPreferencesStore.KEY_AUTO_RECORD_SELECTED_NUMBERS)
                .remove(CallRecordingPreferencesStore.KEY_AUTO_RECORDING_SET_AT_LEAST_ONCE)
                .remove(CallRecordingPreferencesStore.KEY_RECORDING_WARNING_PRESENTED)
                .commit())
        .isTrue();
  }

  private ListenableFuture<CallRecordingPreferences> loadPreferencesAsync() {
    return CallRecordingPreferencesStore.loadAsync(context);
  }

  private void assertLegacyRecorderKeysCleared() {
    assertThat(legacyPrefs.contains(LEGACY_KEY_USE_V2)).isFalse();
    assertThat(legacyPrefs.contains(LEGACY_KEY_AUDIO_SOURCE)).isFalse();
    assertThat(legacyPrefs.contains(LEGACY_KEY_OUTPUT_FORMAT)).isFalse();
    assertThat(legacyPrefs.contains(LEGACY_KEY_OUTPUT_FORMAT_V2)).isFalse();
  }

  private static String getAudioSource(CallRecordingPreferences preferences) {
    return preferences.hasCallRecordingAudioSource()
            && !preferences.getCallRecordingAudioSource().isEmpty()
        ? preferences.getCallRecordingAudioSource()
        : DEFAULT_AUDIO_SOURCE;
  }

  private static RecordingOutputFormat getOutputFormat(CallRecordingPreferences preferences) {
    return preferences.hasCallRecordingOutputFormat()
        ? preferences.getCallRecordingOutputFormat()
        : DEFAULT_OUTPUT_FORMAT;
  }

  private static RecordingOutputFormat getOutputFormatV2(CallRecordingPreferences preferences) {
    return preferences.hasCallRecordingOutputFormatV2()
        ? preferences.getCallRecordingOutputFormatV2()
        : DEFAULT_OUTPUT_FORMAT;
  }

}
