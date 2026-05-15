package com.android.dialer.callrecord

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.ExtensionRegistryLite
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

internal object CallRecordingPreferencesSerializer : Serializer<CallRecordingPreferences> {
  override val defaultValue: CallRecordingPreferences =
      CallRecordingPreferenceValues.DEFAULT_PREFERENCES

  override suspend fun readFrom(input: InputStream): CallRecordingPreferences {
    try {
      return CallRecordingPreferences.parseFrom(input, ExtensionRegistryLite.getEmptyRegistry())
    } catch (e: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read call recording preferences.", e)
    }
  }

  override suspend fun writeTo(t: CallRecordingPreferences, output: OutputStream) {
    t.writeTo(output)
  }
}
