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
import android.preference.Preference
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
import com.android.dialer.callrecord.CallRecordingWarningHelper
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

/** Settings screen for selected contact numbers that should be recorded automatically. */
class AutoCallRecordingSelectedNumbersFragment : PreferenceFragment() {

  private lateinit var appContext: Context
  private lateinit var preferenceRoot: PreferenceScreen
  private lateinit var enabledPreference: SwitchPreference
  private lateinit var fragmentScope: CoroutineScope
  private lateinit var backgroundDispatcher: CoroutineDispatcher
  private lateinit var autoRecordingEnableFlow: AutoCallRecordingEnableFlow
  private var refreshJob: Job? = null
  private var lastRefreshResult: RefreshResult? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
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
    if (::fragmentScope.isInitialized) {
      fragmentScope.cancel()
    }
    super.onDestroy()
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode == REQUEST_PICK_AUTO_RECORD_NUMBER) {
      if (resultCode == Activity.RESULT_OK) {
        data?.data?.let { addSelectedNumber(it) }
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
        render(
            result.selectedNumbers,
            result.selectedNumbersEnabled,
            result.recordingWarningPresented)
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

  private fun addSelectedNumber(uri: Uri) {
    if (!::appContext.isInitialized || !::fragmentScope.isInitialized) {
      return
    }
    fragmentScope.launch {
      try {
        val status = withContext(backgroundDispatcher) { addSelectedNumber(appContext, uri) }
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
        preferences.autoRecordSelectedNumbersEnabled,
        preferences.recordingWarningPresented)
  }

  private fun renderStoredNumbersWithoutCleanup() {
    lastRefreshResult?.let {
      render(it.selectedNumbers, it.selectedNumbersEnabled, it.recordingWarningPresented)
    }
  }

  private fun render(
      selectedNumbers: List<ResolvedSelectedNumber>,
      selectedNumbersEnabled: Boolean,
      recordingWarningPresented: Boolean
  ) {
    val context = activity ?: return
    lastRefreshResult =
        RefreshResult(selectedNumbers, selectedNumbersEnabled, recordingWarningPresented)
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
                      it.setAutoRecordSelectedNumbersEnabled(false)
                          .setAutoRecordingSetAtLeastOnce(true)
                    }
                  }
              return@setOnPreferenceChangeListener true
            }
            requestEnableAutomaticRecording {
              refreshAfterPreferenceWrite(
                  "AutoCallRecordingSelectedNumbersFragment.enableSelectedNumbers") {
                    CallRecordingPreferencesStore.update(appContext) {
                      it.setAutoRecordSelectedNumbersEnabled(true)
                          .setAutoRecordingSetAtLeastOnce(true)
                    }
                  }
              enabledPreference.isChecked = true
            }
            false
          }
        }
    preferenceRoot.addPreference(enabledPreference)

    preferenceRoot.addPreference(
        Preference(context).apply {
          setTitle(R.string.call_recording_auto_record_selected_numbers_add)
          isEnabled = selectedNumbersEnabled
          setOnPreferenceClickListener {
            launchContactNumberPicker()
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
              showRemoveConfirmation(selectedNumber.canonicalNumber)
              true
            }
          })
    }
  }

  private fun launchContactNumberPicker() {
    try {
      startActivityForResult(
          Intent(Intent.ACTION_PICK, Phone.CONTENT_URI), REQUEST_PICK_AUTO_RECORD_NUMBER)
    } catch (e: RuntimeException) {
      showAddFailed()
    }
  }

  private fun showRemoveConfirmation(canonicalNumber: String) {
    val activity = activity ?: return
    AlertDialog.Builder(activity)
        .setTitle(R.string.call_recording_auto_record_remove_number_title)
        .setMessage(R.string.call_recording_auto_record_remove_number_message)
        .setPositiveButton(android.R.string.ok) { _, _ ->
          refreshAfterPreferenceWrite(
              "AutoCallRecordingSelectedNumbersFragment.removeSelectedNumber") {
                withContext(backgroundDispatcher) {
                  CallRecordingPreferencesStore.update(appContext) {
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

  private suspend fun addSelectedNumber(context: Context, uri: Uri): AddNumberStatus {
    val canonicalNumber = readPickedCanonicalNumber(context, uri)
    if (canonicalNumber.isNullOrEmpty()) {
      return AddNumberStatus.FAILED
    }
    return when (SelectedNumberPreferenceUpdater.add(context, canonicalNumber)) {
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
      val recordingWarningPresented: Boolean
  )

  private data class PreferenceDisplay(val title: CharSequence, val summary: String?)

  private enum class AddNumberStatus {
    ADDED,
    ALREADY_ADDED,
    FAILED,
  }

  private companion object {
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
