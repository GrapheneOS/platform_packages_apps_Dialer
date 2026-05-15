package com.android.dialer.callrecord

import android.content.Context
import android.support.annotation.VisibleForTesting
import com.android.dialer.common.LogUtil
import kotlinx.coroutines.CancellationException

/** Removes selected automatic recording numbers that no longer resolve to local contacts. */
object AutoCallRecordingStaleContactCleaner {

  suspend fun clean(context: Context): Result =
      clean(context, AutoCallRecordingContactResolver::resolveSelectedNumbersAsync)

  @VisibleForTesting
  suspend fun clean(context: Context, selectedNumberResolver: SelectedNumberResolver): Result {
    val preferences = CallRecordingPreferencesStore.load(context)
    val selectedNumbers = CallRecordingPreferenceValues.selectedNumbers(preferences)
    val selectedNumberRecordingEnabled = preferences.autoRecordSelectedNumbersEnabled
    if (!selectedNumberRecordingEnabled || selectedNumbers.isEmpty()) {
      LogUtil.i(
          "AutoCallRecordingStaleContactCleaner.clean",
          "skipping stale contact cleanup; selectedEnabled=%b, selectedCount=%d",
          selectedNumberRecordingEnabled,
          selectedNumbers.size)
      return Result.unchanged(selectedNumbers.size)
    }

    val resolveResult =
        try {
          selectedNumberResolver.resolveSelectedNumbers(context, selectedNumbers)
        } catch (e: CancellationException) {
          throw e
        } catch (e: RuntimeException) {
          // Failed contact lookups are not evidence that the user's selected numbers are stale.
          LogUtil.e("AutoCallRecordingStaleContactCleaner.clean", "contact lookup failed", e)
          return Result.unchanged(selectedNumbers.size)
        }
    if (!resolveResult.lookupSucceeded) {
      return Result.unchanged(selectedNumbers.size)
    }

    val cleanedNumbers = selectedNumbers.toMutableSet()
    for ((number, resolvedNumber) in resolveResult.resolvedNumbers) {
      if (!resolvedNumber.isLocalContact) {
        cleanedNumbers.remove(number)
      }
    }

    val staleNumbers = selectedNumbers - cleanedNumbers
    val removedCount = staleNumbers.size
    if (removedCount == 0) {
      LogUtil.i(
          "AutoCallRecordingStaleContactCleaner.clean",
          "no stale selected numbers found; selectedCount=%d",
          selectedNumbers.size)
      return Result.unchanged(selectedNumbers.size)
    }

    CallRecordingPreferencesStore.update(context) { builder ->
      val currentSelectedNumbers = builder.autoRecordSelectedNumbersList.toMutableSet()
      currentSelectedNumbers.removeAll(staleNumbers)
      CallRecordingPreferenceValues.setSelectedNumbers(builder, currentSelectedNumbers)
    }
    LogUtil.i(
        "AutoCallRecordingStaleContactCleaner.clean",
        "removed %d stale selected numbers from %d",
        removedCount,
        selectedNumbers.size)
    return Result(selectedNumbers.size, removedCount, true)
  }

  @VisibleForTesting
  fun interface SelectedNumberResolver {
    suspend fun resolveSelectedNumbers(
        context: Context,
        selectedNumbers: Set<String>
    ): AutoCallRecordingContactResolver.ResolveResult
  }

  @VisibleForTesting
  class Result(
      @JvmField val selectedNumberCount: Int,
      @JvmField val staleNumberCount: Int,
      @JvmField val changed: Boolean,
  ) {
    companion object {
      fun unchanged(selectedNumberCount: Int): Result {
        return Result(selectedNumberCount, 0, false)
      }
    }
  }
}
