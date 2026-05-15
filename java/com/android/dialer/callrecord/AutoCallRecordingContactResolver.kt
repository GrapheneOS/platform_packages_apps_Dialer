package com.android.dialer.callrecord

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.PhoneLookup
import android.support.annotation.VisibleForTesting
import android.support.annotation.WorkerThread
import android.util.ArrayMap
import com.android.dialer.common.concurrent.DialerExecutorComponent
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/** Resolves stored automatic recording numbers against local ContactsProvider rows. */
object AutoCallRecordingContactResolver {

  private val PHONE_LOOKUP_PROJECTION =
      arrayOf(
          PhoneLookup.DISPLAY_NAME,
          PhoneLookup.NUMBER,
          PhoneLookup.TYPE,
          PhoneLookup.LABEL)

  private const val DISPLAY_NAME_INDEX = 0
  private const val NUMBER_INDEX = 1
  private const val TYPE_INDEX = 2
  private const val LABEL_INDEX = 3

  @JvmStatic
  @WorkerThread
  fun resolveSelectedNumbers(context: Context, numbers: Set<String>): ResolveResult {
    return runBlocking { resolveSelectedNumbersAsync(context, numbers) }
  }

  @JvmStatic
  suspend fun resolveSelectedNumbersAsync(context: Context, numbers: Set<String>): ResolveResult {
    val appContext = context.applicationContext ?: context
    val dispatcher =
        DialerExecutorComponent.get(appContext).backgroundExecutor().asCoroutineDispatcher()
    return resolveSelectedNumbers(numbers, dispatcher) { number ->
      resolveSelectedNumber(appContext, number)
    }
  }

  @JvmStatic
  @VisibleForTesting
  fun resolveSelectedNumbersForTesting(
      numbers: Set<String>,
      executor: ExecutorService,
      lookup: SelectedNumberLookup,
  ): ResolveResult {
    return runBlocking {
      resolveSelectedNumbers(numbers, executor.asCoroutineDispatcher()) { number ->
        LookupResult(lookup.resolve(number), true)
      }
    }
  }

  private suspend fun resolveSelectedNumbers(
      numbers: Set<String>,
      dispatcher: CoroutineDispatcher,
      lookup: suspend (String) -> LookupResult,
  ): ResolveResult = coroutineScope {
    val resolvedNumbers = ArrayMap<String, ResolvedSelectedNumber>()
    var lookupSucceeded = true

    val lookupJobs = numbers.associateWith { number -> async(dispatcher) { lookup(number) } }
    for ((number, job) in lookupJobs) {
      val result = job.await()
      if (!result.lookupSucceeded) {
        lookupSucceeded = false
      }
      resolvedNumbers[number] = result.resolvedSelectedNumber
    }
    ResolveResult(resolvedNumbers, lookupSucceeded)
  }

  @WorkerThread
  private fun resolveSelectedNumber(context: Context, number: String): LookupResult {
    val unresolved = ResolvedSelectedNumber.createUnresolved(number)
    if (number.isEmpty()) {
      return LookupResult(unresolved, true)
    }

    val lookupUri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
    return try {
      val cursor =
          context.contentResolver.query(lookupUri, PHONE_LOOKUP_PROJECTION, null, null, null)
              ?: return LookupResult(unresolved, false)
      cursor.use {
        if (!it.moveToFirst()) {
          return LookupResult(unresolved, true)
        }
        val label =
            Phone.getTypeLabel(
                context.resources,
                it.getInt(TYPE_INDEX),
                it.getString(LABEL_INDEX))
        LookupResult(
            ResolvedSelectedNumber(
                canonicalNumber = number,
                displayName = it.getString(DISPLAY_NAME_INDEX),
                displayNumber = it.getString(NUMBER_INDEX),
                label = label?.toString(),
                isLocalContact = true),
            true)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: RuntimeException) {
      LookupResult(unresolved, false)
    }
  }

  @VisibleForTesting
  fun interface SelectedNumberLookup {
    fun resolve(number: String): ResolvedSelectedNumber
  }

  class ResolveResult(
      @JvmField val resolvedNumbers: Map<String, ResolvedSelectedNumber>,
      @JvmField val lookupSucceeded: Boolean,
  )

  class ResolvedSelectedNumber(
      @JvmField val canonicalNumber: String,
      @JvmField val displayName: String?,
      @JvmField val displayNumber: String?,
      @JvmField val label: String?,
      @JvmField val isLocalContact: Boolean,
  ) {
    companion object {
      @JvmStatic
      fun createUnresolved(canonicalNumber: String): ResolvedSelectedNumber {
        return ResolvedSelectedNumber(canonicalNumber, null, null, null, false)
      }
    }
  }

  private class LookupResult(
      val resolvedSelectedNumber: ResolvedSelectedNumber,
      val lookupSucceeded: Boolean,
  )
}
