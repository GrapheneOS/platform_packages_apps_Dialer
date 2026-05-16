package com.android.incallui.call

import android.content.Context
import android.content.pm.PackageManager
import android.support.v4.os.UserManagerCompat
import android.widget.Toast
import com.android.dialer.callrecord.CallRecordingPreferences
import com.android.dialer.callrecord.CallRecordingPreferencesStore
import com.android.dialer.common.concurrent.DialerExecutorComponent
import com.android.dialer.util.PermissionsUtil
import com.android.incallui.ContactInfoCache
import com.android.incallui.ContactInfoCache.ContactCacheEntry
import com.android.incallui.ContactInfoCache.ContactInfoCacheCallback
import com.android.incallui.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Adapts production incallui singletons and Android services to CallRecordingDependencies.
 */
object CallRecordingDefaultDependencies {
  @JvmStatic
  fun create(context: Context): CallRecordingDependencies {
    val appContext = context.applicationContext ?: context
    val executorComponent = DialerExecutorComponent.get(appContext)
    return CallRecordingDependencies(
        GlobalCurrentCalls,
        ContactInfoCacheLookup(appContext),
        DataStorePreferenceSource(appContext),
        DefaultEligibilityChecker(appContext),
        AndroidCallRecordingSystem(appContext),
        executorComponent.uiExecutor().asCoroutineDispatcher(),
        executorComponent.backgroundExecutor().asCoroutineDispatcher())
  }
}

private object GlobalCurrentCalls : CurrentCalls {
  override fun hasLiveCall(): Boolean = CallList.getInstance().hasLiveCall()

  override fun hasActiveOrBackgroundCall(): Boolean =
      CallList.getInstance().getActiveOrBackgroundCall() != null

  override fun requiresManualRecordingStart(): Boolean =
      RecordingRules.requiresManualRecordingStart(CallList.getInstance())

  override fun getActiveCall(): CallSnapshot? = CallList.getInstance().activeCall.toCallSnapshot()

  override fun getCallById(callId: String): CallSnapshot? =
      CallList.getInstance().getCallById(callId).toCallSnapshot()
}

private class DataStorePreferenceSource(context: Context) : PreferenceSource {
  private val context = context.applicationContext ?: context

  override suspend fun load(): CallRecordingPreferences =
      CallRecordingPreferencesStore.load(context)
}

private class DefaultEligibilityChecker(context: Context) : EligibilityChecker {
  private val context = context.applicationContext ?: context

  override fun getDecision(
      call: CallSnapshot,
      preferences: CallRecordingPreferences,
      requireContactsPermission: Boolean
  ): AutoCallRecordingEligibility.AutoRecordDecision {
    return AutoCallRecordingEligibility.getDecision(
        true /* hasCall */,
        call.isVideoCall,
        true /* snapshotReady */,
        preferences,
        PermissionsUtil.hasMicrophonePermissions(context),
        PermissionsUtil.hasContactsReadPermissions(context),
        requireContactsPermission)
  }
}

private class AndroidCallRecordingSystem(context: Context) : CallRecordingSystem {
  private val context = context.applicationContext ?: context

  override fun hasAllPermissions(permissions: Array<String>): Boolean {
    return permissions.all { permission ->
      context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
  }

  override fun isUserUnlocked(): Boolean = UserManagerCompat.isUserUnlocked(context)

  override fun showLockedUserMessage() {
    Toast.makeText(
            context,
            R.string.call_recording_unlock_to_record_message,
            Toast.LENGTH_SHORT)
        .show()
  }
}

private class ContactInfoCacheLookup(context: Context) : ContactLookup {
  private val context = context.applicationContext ?: context

  override suspend fun findInfo(call: CallSnapshot): ContactInfo? {
    val dialerCall = call.dialerCall ?: return null
    // TODO: Use suspendCancellableCoroutine when Dialer is not built with JVM target 1.8.
    val result = CompletableDeferred<ContactInfo?>()
    ContactInfoCache.getInstance(context)
        .findInfo(
            dialerCall,
            false /* isIncoming */,
            object : ContactInfoCacheCallback {
              override fun onContactInfoComplete(
                  callbackCallId: String,
                  entry: ContactCacheEntry?,
              ) {
                if (callbackCallId != call.id) {
                  return
                }
                if (entry?.hasPendingContactLookup() == true) {
                  // ContactInfoCache can report a pending entry before the contact lookup is done.
                  // Automatic policy needs the final contact or non-contact answer.
                  return
                }
                result.complete(
                    entry?.let { ContactInfo(it.isLocalContact(), it.getNormalizedNumber()) })
              }

              override fun onImageLoadComplete(
                  callbackCallId: String,
                  entry: ContactCacheEntry?,
              ) {}
            })
    try {
      return result.await()
    } catch (e: CancellationException) {
      // ContactInfoCache has no callback removal API. Cancel the deferred so a late callback cannot
      // resume abandoned work; complete() will simply return false.
      result.cancel(e)
      throw e
    }
  }
}
