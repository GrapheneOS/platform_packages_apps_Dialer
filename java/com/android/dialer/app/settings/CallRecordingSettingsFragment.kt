// TODO: Migrate this screen off Dialer's deprecated platform settings stack.
@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.android.dialer.app.settings

import android.content.Context
import android.os.Bundle
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceFragment
import android.preference.PreferenceScreen
import android.preference.SwitchPreference
import com.android.dialer.app.R
import com.android.dialer.callrecord.CallRecordingPreferences
import com.android.dialer.callrecord.CallRecordingPreferencesStore
import com.android.dialer.callrecord.RecordingOutputFormat
import com.android.dialer.common.LogUtil
import com.android.dialer.common.concurrent.DialerExecutorComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CallRecordingSettingsFragment : PreferenceFragment() {

  private lateinit var appContext: Context
  private lateinit var fragmentScope: CoroutineScope
  private lateinit var useV2: SwitchPreference
  private lateinit var audioSource: ListPreference
  private lateinit var formatV1: ListPreference
  private lateinit var formatV2: ListPreference
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

    setPreferencesEnabled(CallRecordingPreferencesStore.isLoaded(appContext))
    configureListeners()
    loadPreferences()
  }

  override fun onResume() {
    super.onResume()
    loadPreferences()
  }

  override fun onDestroy() {
    if (::fragmentScope.isInitialized) {
      fragmentScope.cancel()
    }
    super.onDestroy()
  }

  private fun loadPreferences() {
    if (!::appContext.isInitialized || !::fragmentScope.isInitialized) {
      return
    }
    fragmentScope.launch {
      try {
        renderPreferences(CallRecordingPreferencesStore.load(appContext))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LogUtil.e(
            "CallRecordingSettingsFragment.loadPreferences",
            "failed to load call recording preferences",
            e)
      }
      setPreferencesEnabled(CallRecordingPreferencesStore.isLoaded(appContext))
    }
  }

  private fun renderPreferences(preferences: CallRecordingPreferences) {
    if (!::useV2.isInitialized) {
      return
    }
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
      setOutputOptionsVisibility(isV2Enabled)
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
    setPreferenceChangeListenersEnabled(true)
  }

  private fun addPreferenceChangeListener(
      preference: Preference,
      listener: Preference.OnPreferenceChangeListener
  ) {
    preferenceChangeListeners += preference to listener
  }

  private fun updatePreference(update: suspend () -> Unit) {
    if (!::fragmentScope.isInitialized) {
      return
    }
    fragmentScope.launch {
      try {
        update()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LogUtil.e(
            "CallRecordingSettingsFragment.updatePreference",
            "failed to update call recording preferences",
            e)
        loadPreferences()
      }
    }
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
    val screen: PreferenceScreen = preferenceScreen
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
    private val DEFAULT_OUTPUT_FORMAT = RecordingOutputFormat.AAC_MPEG_4
  }
}
