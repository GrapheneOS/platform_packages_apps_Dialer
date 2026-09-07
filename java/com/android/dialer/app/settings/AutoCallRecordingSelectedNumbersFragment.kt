// TODO: Migrate this screen off Dialer's deprecated platform settings stack.
@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.android.dialer.app.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceCategory
import android.preference.PreferenceFragment
import android.preference.PreferenceScreen
import android.preference.SwitchPreference
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.widget.Toast
import com.android.dialer.app.R
import com.android.dialer.callrecord.AutoCallRecordingContactResolver
import com.android.dialer.callrecord.AutoCallRecordingContactResolver.ResolvedSelectedNumber
import com.android.dialer.callrecord.CallRecordingPermissionHelper
import com.android.dialer.callrecord.CallRecordingPreferenceValues
import com.android.dialer.callrecord.CallRecordingPreferencesStore
import com.android.dialer.callrecord.ContactRecordingMode
import com.android.dialer.common.LogUtil
import com.android.dialer.common.concurrent.DialerExecutorComponent
import com.android.dialer.phonenumberutil.PhoneNumberCanonicalizer
import com.android.incallui.R as InCallUiR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings screen for contact recording and its included or excluded numbers. */
class AutoCallRecordingSelectedNumbersFragment : PreferenceFragment() {

  private lateinit var appContext: Context
  private lateinit var preferenceRoot: PreferenceScreen
  private lateinit var enabledPreference: SwitchPreference
  private lateinit var fragmentScope: CoroutineScope
  private lateinit var backgroundDispatcher: CoroutineDispatcher
  private lateinit var autoRecordingEnableFlow: AutoCallRecordingEnableFlow
  private var refreshJob: Job? = null
  private var lastRefreshResult: RefreshResult? = null
  private var pickerMode: ContactRecordingMode? = null
  private var modeDialog: AlertDialog? = null
  private var changingMode = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    pickerMode =
        savedInstanceState?.getString(STATE_PICKER_MODE)?.let { ContactRecordingMode.valueOf(it) }
    val context = activity ?: return
    val executorComponent = DialerExecutorComponent.get(context)
    fragmentScope =
        CoroutineScope(SupervisorJob() + executorComponent.uiExecutor().asCoroutineDispatcher())
    backgroundDispatcher = executorComponent.backgroundExecutor().asCoroutineDispatcher()
    appContext = context.applicationContext ?: context
    autoRecordingEnableFlow =
        AutoCallRecordingEnableFlow(
            fragment = this,
            requestCode = REQUEST_CODE_AUTO_RECORD_PERMISSION,
            isWarningPresented = { lastRefreshResult?.recordingWarningPresented == true },
            writeWarningPresented = { onSuccess, onFailure ->
              refreshAfterPreferenceWrite(
                  "AutoCallRecordingSelectedNumbersFragment.recordingWarningPresented",
                  onFailure = { onFailure.onFailure(it) },
                  onSuccess = { onSuccess.run() }) {
                    CallRecordingPreferencesStore.update(appContext) {
                      it.setRecordingWarningPresented(true)
                    }
                  }
            },
            onPermissionDenied = ::refreshSelectedNumbers,
            onPermissionPermanentlyDenied = ::showCallRecordingPermissionDialog)
    preferenceRoot = preferenceManager.createPreferenceScreen(context)
    preferenceRoot.setTitle(R.string.call_recording_auto_record_selected_numbers_title)
    preferenceScreen = preferenceRoot
  }

  override fun onResume() {
    super.onResume()
    refreshSelectedNumbers()
  }

  override fun onDestroy() {
    modeDialog?.dismiss()
    if (::fragmentScope.isInitialized) {
      fragmentScope.cancel()
    }
    super.onDestroy()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    pickerMode?.let { outState.putString(STATE_PICKER_MODE, it.name) }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode == REQUEST_PICK_AUTO_RECORD_NUMBER) {
      val expectedMode = pickerMode
      pickerMode = null
      if (resultCode == Activity.RESULT_OK && expectedMode != null) {
        data?.data?.let { addSelectedNumber(it, expectedMode) }
      }
      return
    }
    super.onActivityResult(requestCode, resultCode, data)
  }

  override fun onRequestPermissionsResult(
      requestCode: Int,
      permissions: Array<out String>,
      grantResults: IntArray
  ) {
    if (requestCode == REQUEST_CODE_AUTO_RECORD_PERMISSION) {
      autoRecordingEnableFlow.onRequestPermissionsResult(
          permissions, grantResults, ::refreshSelectedNumbers)
      return
    }
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
  }

  private fun refreshSelectedNumbers() {
    if (!::appContext.isInitialized || !::fragmentScope.isInitialized) {
      return
    }
    refreshJob?.cancel()
    refreshJob = fragmentScope.launch {
      try {
        val result = withContext(backgroundDispatcher) { loadSelectedNumbers(appContext) }
        render(result)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LogUtil.e(
            "AutoCallRecordingSelectedNumbersFragment.refreshSelectedNumbers",
            "failed to refresh selected numbers",
            e)
        renderStoredNumbersWithoutCleanup()
      }
    }
  }

  private fun addSelectedNumber(uri: Uri, expectedMode: ContactRecordingMode) {
    if (!::appContext.isInitialized || !::fragmentScope.isInitialized) {
      return
    }
    fragmentScope.launch {
      try {
        val status = withContext(backgroundDispatcher) {
          addSelectedNumber(appContext, uri, expectedMode)
        }
        onAddNumberComplete(status)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LogUtil.e(
            "AutoCallRecordingSelectedNumbersFragment.addSelectedNumber",
            "failed to add selected number",
            e)
        showAddFailed()
      }
    }
  }

  private suspend fun loadSelectedNumbers(context: Context): RefreshResult {
    val preferences = CallRecordingPreferencesStore.load(context)
    val selectedNumbers = CallRecordingPreferenceValues.selectedNumbers(preferences)
    val resolveResult =
        AutoCallRecordingContactResolver.resolveSelectedNumbersAsync(context, selectedNumbers)
    return RefreshResult(
        getDisplayNumbers(selectedNumbers, resolveResult.resolvedNumbers),
        preferences.autoRecordContactsEnabled,
        preferences.recordingWarningPresented,
        preferences.contactRecordingMode)
  }

  private fun renderStoredNumbersWithoutCleanup() {
    lastRefreshResult?.let(::render)
  }

  private fun render(result: RefreshResult) {
    val context = activity ?: return
    lastRefreshResult = result
    val (selectedNumbers, selectedNumbersEnabled, _, mode) = result
    preferenceRoot.removeAll()

    enabledPreference =
        SwitchPreference(context).apply {
          setTitle(R.string.call_recording_auto_record_selected_numbers_title)
          isPersistent = false
          isChecked = selectedNumbersEnabled
          setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            if (!enabled) {
              refreshAfterPreferenceWrite(
                  "AutoCallRecordingSelectedNumbersFragment.disableSelectedNumbers") {
                    CallRecordingPreferencesStore.update(appContext) {
                      it.setAutoRecordContactsEnabled(false)
                          .setAutoRecordingSetAtLeastOnce(true)
                    }
                  }
              return@setOnPreferenceChangeListener true
            }
            requestEnableAutomaticRecording {
              refreshAfterPreferenceWrite(
                  "AutoCallRecordingSelectedNumbersFragment.enableSelectedNumbers") {
                    CallRecordingPreferencesStore.update(appContext) {
                      it.setAutoRecordContactsEnabled(true)
                          .setAutoRecordingSetAtLeastOnce(true)
                    }
                  }
              enabledPreference.isChecked = true
            }
            false
          }
        }
    preferenceRoot.addPreference(enabledPreference)

    val excludesNumbers = mode == ContactRecordingMode.ALL_EXCEPT_SELECTED_NUMBERS
    preferenceRoot.addPreference(
        ListPreference(context).apply {
          key = "call_recording_contact_mode"
          setTitle(R.string.call_recording_contact_mode_title)
          setEntries(R.array.call_recording_contact_mode_entries)
          entryValues = arrayOf(
              ContactRecordingMode.SELECTED_NUMBERS.name,
              ContactRecordingMode.ALL_EXCEPT_SELECTED_NUMBERS.name)
          isPersistent = false
          value = mode.name
          summary = entry
          setOnPreferenceChangeListener { _, newValue ->
            requestModeChange(ContactRecordingMode.valueOf(newValue as String))
            false
          }
        })

    preferenceRoot.addPreference(
        PreferenceCategory(context).apply {
          setTitle(
              if (excludesNumbers) {
                R.string.call_recording_numbers_to_exclude
              } else {
                R.string.call_recording_numbers_to_record
              })
        })
    if (selectedNumbers.isEmpty()) {
      preferenceRoot.addPreference(
          Preference(context).apply {
            isSelectable = false
            setSummary(
                if (excludesNumbers) {
                  R.string.call_recording_all_contacts_empty
                } else {
                  R.string.call_recording_auto_record_selected_numbers_empty
                })
          })
    }

    preferenceRoot.addPreference(
        Preference(context).apply {
          setTitle(R.string.call_recording_auto_record_selected_numbers_add)
          isEnabled = selectedNumbersEnabled
          setOnPreferenceClickListener {
            launchContactNumberPicker(mode)
            true
          }
        })

    for (selectedNumber in selectedNumbers) {
      preferenceRoot.addPreference(
          Preference(context).apply {
            val display = getPreferenceDisplay(selectedNumber)
            title = display.title
            display.summary?.let { summary = it }
            isEnabled = selectedNumbersEnabled
            setOnPreferenceClickListener {
              showRemoveConfirmation(selectedNumber.canonicalNumber, mode)
              true
            }
          })
    }
    preferenceRoot.isEnabled = !changingMode
  }

  private fun requestModeChange(mode: ContactRecordingMode) {
    val current = lastRefreshResult ?: return
    if (mode == current.mode || changingMode || modeDialog != null) {
      return
    }
    if (current.selectedNumbers.isEmpty()) {
      changeMode(current, mode)
      return
    }
    val context = activity ?: return
    modeDialog = AlertDialog.Builder(context)
        .setTitle(R.string.call_recording_change_mode_title)
        .setMessage(
            if (mode == ContactRecordingMode.ALL_EXCEPT_SELECTED_NUMBERS) {
              R.string.call_recording_change_mode_to_all_message
            } else {
              R.string.call_recording_change_mode_to_selected_message
            })
        .setPositiveButton(R.string.call_recording_discard_and_switch) { _, _ ->
          changeMode(current, mode)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create().also { dialog ->
          dialog.setOnDismissListener { modeDialog = null }
          dialog.show()
        }
  }

  private fun changeMode(current: RefreshResult, mode: ContactRecordingMode) {
    changingMode = true
    preferenceRoot.isEnabled = false
    refreshAfterPreferenceWrite(
        "AutoCallRecordingSelectedNumbersFragment.changeMode",
        onFailure = {
          changingMode = false
          activity?.let { context ->
            Toast.makeText(context, R.string.call_recording_change_mode_failed, Toast.LENGTH_SHORT)
                .show()
          }
          refreshSelectedNumbers()
        },
        onSuccess = {
          changingMode = false
          refreshSelectedNumbers()
        }) {
          CallRecordingPreferencesStore.update(appContext) {
            CallRecordingPreferenceValues.switchContactRecordingMode(
                it, current.mode, current.selectedNumbers.map { it.canonicalNumber }.toSet(), mode)
          }
        }
  }

  private fun launchContactNumberPicker(mode: ContactRecordingMode) {
    pickerMode = mode
    try {
      startActivityForResult(
          Intent(Intent.ACTION_PICK, Phone.CONTENT_URI), REQUEST_PICK_AUTO_RECORD_NUMBER)
    } catch (e: RuntimeException) {
      pickerMode = null
      showAddFailed()
    }
  }

  private fun showRemoveConfirmation(canonicalNumber: String, mode: ContactRecordingMode) {
    val activity = activity ?: return
    AlertDialog.Builder(activity)
        .setTitle(R.string.call_recording_auto_record_remove_number_title)
        .setMessage(
            if (mode == ContactRecordingMode.ALL_EXCEPT_SELECTED_NUMBERS) {
              R.string.call_recording_remove_exception_message
            } else {
              R.string.call_recording_auto_record_remove_number_message
            })
        .setPositiveButton(android.R.string.ok) { _, _ ->
          refreshAfterPreferenceWrite(
              "AutoCallRecordingSelectedNumbersFragment.removeSelectedNumber") {
                withContext(backgroundDispatcher) {
                  CallRecordingPreferencesStore.update(appContext) {
                    check(it.contactRecordingMode == mode) { "Contact recording mode changed" }
                    val numbers =
                        CallRecordingPreferenceValues.selectedNumbers(it.build()).toMutableSet()
                    numbers.remove(canonicalNumber)
                    CallRecordingPreferenceValues.setSelectedNumbers(it, numbers)
                  }
                }
              }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private fun onAddNumberComplete(status: AddNumberStatus) {
    if (status == AddNumberStatus.FAILED) {
      showAddFailed()
      return
    }
    if (status == AddNumberStatus.ALREADY_ADDED) {
      activity?.let {
        Toast.makeText(
                it,
                R.string.call_recording_auto_record_number_already_added,
                Toast.LENGTH_SHORT)
            .show()
      }
    }
    refreshSelectedNumbers()
  }

  private fun showAddFailed() {
    activity?.let {
      Toast.makeText(
              it, R.string.call_recording_auto_record_number_add_failed, Toast.LENGTH_SHORT)
          .show()
    }
    refreshSelectedNumbers()
  }

  private fun requestEnableAutomaticRecording(enableAction: () -> Unit) {
    autoRecordingEnableFlow.requestEnable(enableAction)
  }

  private fun refreshAfterPreferenceWrite(
      operation: String,
      onFailure: (Exception) -> Unit = { refreshSelectedNumbers() },
      onSuccess: () -> Unit = ::refreshSelectedNumbers,
      write: suspend () -> Unit
  ) {
    if (!::fragmentScope.isInitialized) {
      return
    }
    fragmentScope.launchCallRecordingPreferenceWrite(
        operation = operation,
        onFailure = onFailure,
        onSuccess = onSuccess) {
          write()
        }
  }

  private fun showCallRecordingPermissionDialog() {
    val activity = activity ?: return
    AlertDialog.Builder(activity)
        .setTitle(R.string.call_recording_auto_record_permissions_required_title)
        .setMessage(R.string.call_recording_auto_record_permissions_required_message)
        .setPositiveButton(InCallUiR.string.call_recording_permission_open_settings) { _, _ ->
          openDialerAppSettings()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private fun openDialerAppSettings() {
    val context = activity ?: return
    CallRecordingPermissionHelper.openAppSettings(context)
  }

  private suspend fun addSelectedNumber(
      context: Context,
      uri: Uri,
      expectedMode: ContactRecordingMode
  ): AddNumberStatus {
    val canonicalNumber = readPickedCanonicalNumber(context, uri)
    if (canonicalNumber.isNullOrEmpty()) {
      return AddNumberStatus.FAILED
    }
    return when (SelectedNumberPreferenceUpdater.add(context, canonicalNumber, expectedMode)) {
      SelectedNumberPreferenceUpdater.AddResult.FAILED -> AddNumberStatus.FAILED
      SelectedNumberPreferenceUpdater.AddResult.ADDED -> AddNumberStatus.ADDED
      SelectedNumberPreferenceUpdater.AddResult.ALREADY_ADDED -> AddNumberStatus.ALREADY_ADDED
    }
  }

  private fun readPickedCanonicalNumber(context: Context, uri: Uri): String? {
    return try {
      context.contentResolver.query(uri, PHONE_PROJECTION, null, null, null)?.use { cursor ->
        readPickedCanonicalNumber(context, cursor)
      }
    } catch (e: RuntimeException) {
      null
    }
  }

  private fun readPickedCanonicalNumber(context: Context, cursor: Cursor): String? {
    if (!cursor.moveToFirst()) {
      return null
    }
    val normalizedNumber = cursor.getString(NORMALIZED_NUMBER_INDEX)
    if (!normalizedNumber.isNullOrEmpty()) {
      return normalizedNumber
    }
    return PhoneNumberCanonicalizer.canonicalize(
        context, cursor.getString(PHONE_NUMBER_INDEX))
  }

  private data class RefreshResult(
      val selectedNumbers: List<ResolvedSelectedNumber>,
      val selectedNumbersEnabled: Boolean,
      val recordingWarningPresented: Boolean,
      val mode: ContactRecordingMode
  )

  private data class PreferenceDisplay(val title: CharSequence, val summary: String?)

  private enum class AddNumberStatus {
    ADDED,
    ALREADY_ADDED,
    FAILED,
  }

  private companion object {
    private const val STATE_PICKER_MODE = "picker_mode"
    private const val REQUEST_PICK_AUTO_RECORD_NUMBER = 1
    private const val REQUEST_CODE_AUTO_RECORD_PERMISSION = 1002
    private const val PHONE_NUMBER_INDEX = 0
    private const val NORMALIZED_NUMBER_INDEX = 1
    private val PHONE_PROJECTION = arrayOf(Phone.NUMBER, Phone.NORMALIZED_NUMBER)

    private fun getDisplayNumbers(
        selectedNumbers: Set<String>,
        resolvedNumbers: Map<String, ResolvedSelectedNumber>
    ): List<ResolvedSelectedNumber> {
      return selectedNumbers
          .map { selectedNumber ->
            // Settings refresh is display-only; periodic cleanup owns pruning stale contact rows.
            resolvedNumbers[selectedNumber]
                ?: ResolvedSelectedNumber.createUnresolved(selectedNumber)
          }
          .sortedWith { first, second ->
            getDisplayNameOrCanonicalNumber(first)
                .compareTo(getDisplayNameOrCanonicalNumber(second), ignoreCase = true)
          }
    }

    private fun getPreferenceDisplay(
        selectedNumber: ResolvedSelectedNumber
    ): PreferenceDisplay {
      val displayNumber = selectedNumber.displayNumber
      if (displayNumber.isNullOrEmpty()) {
        return PreferenceDisplay(
            getDisplayNameOrCanonicalNumber(selectedNumber),
            if (selectedNumber.isLocalContact) selectedNumber.canonicalNumber else null)
      }
      val label = selectedNumber.label
      val summary = if (label.isNullOrEmpty()) displayNumber else "$label $displayNumber"
      return PreferenceDisplay(getDisplayNameOrCanonicalNumber(selectedNumber), summary)
    }

    private fun getDisplayNameOrCanonicalNumber(number: ResolvedSelectedNumber): String {
      val displayName = number.displayName
      if (!displayName.isNullOrEmpty()) {
        return displayName
      }
      return number.canonicalNumber
    }
  }
}
