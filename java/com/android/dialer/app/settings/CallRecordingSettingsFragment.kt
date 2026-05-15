// TODO: Migrate this screen off Dialer's deprecated platform settings stack.
@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.android.dialer.app.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceCategory
import android.preference.PreferenceFragment
import android.preference.PreferenceScreen
import android.preference.SwitchPreference
import com.android.dialer.app.R
import com.android.dialer.callrecord.CallRecordingPermissionHelper
import com.android.dialer.callrecord.CallRecordingPreferences
import com.android.dialer.callrecord.CallRecordingPreferencesStore
import com.android.dialer.callrecord.CallRecordingWarningHelper
import com.android.dialer.callrecord.RecordingOutputFormat
import com.android.dialer.common.concurrent.DialerExecutorComponent
import com.android.dialer.util.PermissionsUtil
import com.android.incallui.call.AutoCallRecordingEligibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class CallRecordingSettingsFragment : PreferenceFragment() {

  private lateinit var useV2: SwitchPreference
  private lateinit var audioSource: ListPreference
  private lateinit var formatV1: ListPreference
  private lateinit var formatV2: ListPreference
  private lateinit var autoRecordCategory: PreferenceCategory
  private lateinit var autoRecordPermissionWarning: Preference
  private lateinit var autoRecordNonContacts: SwitchPreference
  private lateinit var autoRecordSelectedNumbers: Preference
  private lateinit var appContext: Context
  // PreferenceFragment is not a LifecycleOwner, so cancel this UI scope explicitly in onDestroy.
  private lateinit var fragmentScope: CoroutineScope
  private lateinit var autoRecordingEnableFlow: AutoCallRecordingEnableFlow
  private var renderedPreferences: CallRecordingPreferences? = null
  private val preferenceChangeListeners =
      mutableListOf<Pair<Preference, Preference.OnPreferenceChangeListener>>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    addPreferencesFromResource(R.xml.call_recording_settings)
    val context = activity ?: return
    val executorComponent = DialerExecutorComponent.get(context)
    fragmentScope =
        CoroutineScope(SupervisorJob() + executorComponent.uiExecutor().asCoroutineDispatcher())
    appContext = context.applicationContext ?: context

    useV2 = findPreference("call_recording_use_v2") as SwitchPreference
    formatV1 = findPreference("call_recording_output_format") as ListPreference
    formatV2 = findPreference("call_recording_output_format_v2") as ListPreference
    audioSource = findPreference("call_recording_audio_source") as ListPreference
    autoRecordCategory = findPreference("call_recording_auto_record_category") as PreferenceCategory
    autoRecordPermissionWarning =
        findPreference("call_recording_auto_record_permission_warning") as Preference
    autoRecordCategory.removePreference(autoRecordPermissionWarning)
    autoRecordNonContacts =
        (findPreference(CallRecordingPreferencesStore.KEY_AUTO_RECORD_NON_CONTACTS)
            as SwitchPreference)
    autoRecordSelectedNumbers = findPreference("call_recording_auto_record_selected_numbers")
    autoRecordingEnableFlow =
        AutoCallRecordingEnableFlow(
            fragment = this,
            requestCode = REQUEST_CODE_AUTO_RECORD_PERMISSION,
            isWarningPresented = { renderedPreferences?.recordingWarningPresented == true },
            writeWarningPresented = CallRecordingWarningHelper.WarningAcknowledgementWriter {
                onSuccess, onFailure ->
              updatePreference(
                  operation = "CallRecordingSettingsFragment.recordingWarningPresented",
                  onFailure = { onFailure.onFailure(it) },
                  onSuccess = { onSuccess.run() }) {
                    CallRecordingPreferencesStore.update(appContext) {
                      it.setRecordingWarningPresented(true)
                    }
                  }
            },
            onPermissionDenied = ::renderLastPreferences,
            onPermissionPermanentlyDenied = {
              openDialerAppSettings()
              renderLastPreferences()
            })

    setPreferencesEnabled(false)
    startCollectingPreferences()
    configureListeners()
  }

  override fun onResume() {
    super.onResume()
    renderLastPreferences()
  }

  override fun onDestroy() {
    if (::fragmentScope.isInitialized) {
      fragmentScope.cancel()
    }
    super.onDestroy()
  }

  override fun onPreferenceTreeClick(
      preferenceScreen: PreferenceScreen,
      preference: Preference
  ): Boolean {
    if (preference === autoRecordSelectedNumbers) {
      startActivity(Intent(activity, AutoCallRecordingSelectedNumbersActivity::class.java))
      return true
    }
    return super.onPreferenceTreeClick(preferenceScreen, preference)
  }

  override fun onRequestPermissionsResult(
      requestCode: Int,
      permissions: Array<out String>,
      grantResults: IntArray
  ) {
    if (requestCode == REQUEST_CODE_AUTO_RECORD_PERMISSION) {
      autoRecordingEnableFlow.onRequestPermissionsResult(
          permissions, grantResults, ::renderLastPreferences)
      return
    }
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
  }

  private fun startCollectingPreferences() {
    if (!::appContext.isInitialized || !::fragmentScope.isInitialized) {
      return
    }
    fragmentScope.launch {
      CallRecordingPreferencesStore.preferencesFlow(appContext).collect { preferences ->
        renderPreferences(preferences)
      }
    }
  }

  private fun renderPreferences(preferences: CallRecordingPreferences) {
    if (!::useV2.isInitialized) {
      return
    }
    renderedPreferences = preferences
    setPreferenceChangeListenersEnabled(false)
    try {
      val isV2Enabled = preferences.useCallRecordingV2
      useV2.isChecked = isV2Enabled
      formatV1.value =
          toOutputFormatPreferenceValue(
              if (preferences.hasCallRecordingOutputFormat()) {
                preferences.callRecordingOutputFormat
              } else {
                DEFAULT_OUTPUT_FORMAT
              })
      formatV2.value =
          toOutputFormatPreferenceValue(
              if (preferences.hasCallRecordingOutputFormatV2()) {
                preferences.callRecordingOutputFormatV2
              } else {
                DEFAULT_OUTPUT_FORMAT
              })
      audioSource.value =
          preferences
              .takeIf { it.hasCallRecordingAudioSource() }
              ?.callRecordingAudioSource
              ?.takeIf { it.isNotEmpty() }
              ?: getString(R.string.call_recording_audio_source_default)
      autoRecordNonContacts.isChecked = preferences.autoRecordNonContacts
      setOutputOptionsVisibility(isV2Enabled)
      updateAutomaticRecordingPreferences(preferences)
      setPreferencesEnabled(true)
    } finally {
      setPreferenceChangeListenersEnabled(true)
    }
  }

  private fun configureListeners() {
    preferenceChangeListeners.clear()
    addPreferenceChangeListener(
        useV2,
        Preference.OnPreferenceChangeListener { _, newValue ->
          val isV2Enabled = newValue as Boolean
          updatePreference {
            CallRecordingPreferencesStore.update(appContext) {
              it.setUseCallRecordingV2(isV2Enabled)
            }
          }
          setOutputOptionsVisibility(isV2Enabled)
          true
        })
    addPreferenceChangeListener(
        formatV1,
        createOutputFormatChangeListener { builder, outputFormat ->
          builder.setCallRecordingOutputFormat(outputFormat)
        })
    addPreferenceChangeListener(
        formatV2,
        createOutputFormatChangeListener { builder, outputFormat ->
          builder.setCallRecordingOutputFormatV2(outputFormat)
        })
    addPreferenceChangeListener(
        audioSource,
        Preference.OnPreferenceChangeListener { _, newValue ->
          updatePreference {
            CallRecordingPreferencesStore.update(appContext) {
              it.setCallRecordingAudioSource(newValue as String)
            }
          }
          true
        })
    addPreferenceChangeListener(
        autoRecordNonContacts,
        Preference.OnPreferenceChangeListener { _, newValue ->
          val enabled = newValue as Boolean
          if (!enabled) {
            updatePreference {
              CallRecordingPreferencesStore.update(appContext) {
                it.setAutoRecordNonContacts(false).setAutoRecordingSetAtLeastOnce(true)
              }
            }
            return@OnPreferenceChangeListener true
          }
          requestEnableAutomaticRecording {
            updatePreference {
              CallRecordingPreferencesStore.update(appContext) {
                it.setAutoRecordNonContacts(true).setAutoRecordingSetAtLeastOnce(true)
              }
            }
            autoRecordNonContacts.isChecked = true
          }
          false
        })
    setPreferenceChangeListenersEnabled(true)
    autoRecordPermissionWarning.setOnPreferenceClickListener {
      requestMissingAutomaticRecordingPermissions()
      true
    }
  }

  private fun addPreferenceChangeListener(
      preference: Preference,
      listener: Preference.OnPreferenceChangeListener
  ) {
    preferenceChangeListeners += preference to listener
  }

  private fun updatePreference(
      operation: String = "CallRecordingSettingsFragment.updatePreference",
      onFailure: (Exception) -> Unit = { renderLastPreferences() },
      onSuccess: () -> Unit = {},
      update: suspend () -> Unit
  ) {
    if (!::fragmentScope.isInitialized) {
      return
    }
    fragmentScope.launchCallRecordingPreferenceWrite(
        operation = operation,
        onFailure = onFailure,
        onSuccess = onSuccess,
        write = update)
  }

  private fun createOutputFormatChangeListener(
      setOutputFormat: (CallRecordingPreferences.Builder, RecordingOutputFormat) -> Unit
  ): Preference.OnPreferenceChangeListener {
    return Preference.OnPreferenceChangeListener { _, newValue ->
      val outputFormat =
          parseOutputFormatPreferenceValue(newValue)
              ?: return@OnPreferenceChangeListener false
      updatePreference {
        CallRecordingPreferencesStore.update(appContext) {
          setOutputFormat(it, outputFormat)
        }
      }
      true
    }
  }

  private fun setPreferenceChangeListenersEnabled(enabled: Boolean) {
    preferenceChangeListeners.forEach { (preference, listener) ->
      preference.setOnPreferenceChangeListener(if (enabled) listener else null)
    }
  }

  private fun setOutputOptionsVisibility(isV2Enabled: Boolean) {
    if (isV2Enabled) {
      hideFirstShowSecond(formatV1, formatV2)
    } else {
      hideFirstShowSecond(formatV2, formatV1)
    }
  }

  private fun hideFirstShowSecond(first: Preference, second: Preference) {
    val screen = preferenceScreen
    screen.removePreference(first)
    if (screen.findPreference(second.key) == null) {
      screen.addPreference(second)
    }
  }

  private fun setPreferencesEnabled(enabled: Boolean) {
    if (!::useV2.isInitialized) {
      return
    }
    useV2.isEnabled = enabled
    formatV1.isEnabled = enabled
    formatV2.isEnabled = enabled
    audioSource.isEnabled = enabled
    autoRecordNonContacts.isEnabled = enabled
    autoRecordPermissionWarning.isEnabled =
        enabled && autoRecordCategory.findPreference(autoRecordPermissionWarning.key) != null
    autoRecordSelectedNumbers.isEnabled = enabled
  }

  private fun updateAutomaticRecordingPreferences(preferences: CallRecordingPreferences) {
    updateAutomaticRecordingPermissionWarning(preferences)
    if (!preferences.autoRecordSelectedNumbersEnabled) {
      autoRecordSelectedNumbers.setSummary(R.string.call_recording_auto_record_selected_numbers_off)
      return
    }
    val count = preferences.autoRecordSelectedNumbersCount
    if (count == 0) {
      autoRecordSelectedNumbers.setSummary(
          R.string.call_recording_auto_record_selected_numbers_empty)
    } else {
      autoRecordSelectedNumbers.summary =
          resources.getQuantityString(
              R.plurals.call_recording_auto_record_selected_numbers_count, count, count)
    }
  }

  private fun updateAutomaticRecordingPermissionWarning(preferences: CallRecordingPreferences) {
    val context = activity ?: return
    val hasMicrophonePermission = hasMicrophonePermission()
    val hasContactsPermission = hasContactsPermission()
    val showWarning =
        (preferences.autoRecordNonContacts || preferences.autoRecordSelectedNumbersEnabled) &&
            (!hasMicrophonePermission || !hasContactsPermission)
    val isShown = autoRecordCategory.findPreference(autoRecordPermissionWarning.key) != null
    if (showWarning) {
      autoRecordPermissionWarning.summary = getAutomaticRecordingPermissionMessage(context)
    }
    if (showWarning && !isShown) {
      autoRecordCategory.addPreference(autoRecordPermissionWarning)
    } else if (!showWarning && isShown) {
      autoRecordCategory.removePreference(autoRecordPermissionWarning)
    }
  }

  private fun getAutomaticRecordingPermissionMessage(context: Context): CharSequence? {
    val decision =
        AutoCallRecordingEligibility.getPermissionDecision(
            context, true /* requireContactsPermission */)
    if (!decision.shouldShowPermissionNotice()) {
      return null
    }
    val microphoneMissing = decision.isMicrophonePermissionMissing()
    val contactsMissing = decision.isContactsPermissionMissing()
    if (microphoneMissing && contactsMissing) {
      return getString(R.string.call_recording_auto_record_permissions_settings_message)
    }
    if (microphoneMissing) {
      return getString(R.string.call_recording_auto_record_microphone_permission_settings_message)
    }
    return if (contactsMissing) {
      getString(R.string.call_recording_auto_record_contacts_permission_settings_message)
    } else {
      null
    }
  }

  private fun requestEnableAutomaticRecording(enableAction: () -> Unit) {
    autoRecordingEnableFlow.requestEnable(enableAction)
  }

  private fun requestMissingAutomaticRecordingPermissions() {
    autoRecordingEnableFlow.requestMissingPermissions(::renderLastPreferences)
  }

  private fun renderLastPreferences() {
    renderedPreferences?.let(::renderPreferences)
  }

  private fun hasMicrophonePermission(): Boolean {
    val context = activity ?: return false
    return PermissionsUtil.hasMicrophonePermissions(context)
  }

  private fun hasContactsPermission(): Boolean {
    val context = activity ?: return false
    return PermissionsUtil.hasContactsReadPermissions(context)
  }

  private fun openDialerAppSettings() {
    val context = activity ?: return
    CallRecordingPermissionHelper.openAppSettings(context)
  }

  private fun parseOutputFormatPreferenceValue(value: Any?): RecordingOutputFormat? {
    return when ((value as? String)?.toIntOrNull()) {
      0 -> RecordingOutputFormat.AAC_MPEG_4
      1 -> RecordingOutputFormat.AMR_WB
      2 -> RecordingOutputFormat.LPCM_WAV
      else -> null
    }
  }

  private fun toOutputFormatPreferenceValue(outputFormat: RecordingOutputFormat): String {
    return when (outputFormat) {
      RecordingOutputFormat.AAC_MPEG_4 -> "0"
      RecordingOutputFormat.AMR_WB -> "1"
      RecordingOutputFormat.LPCM_WAV -> "2"
    }
  }

  companion object {
    private const val REQUEST_CODE_AUTO_RECORD_PERMISSION = 1001
    private val DEFAULT_OUTPUT_FORMAT = RecordingOutputFormat.AAC_MPEG_4
  }
}
