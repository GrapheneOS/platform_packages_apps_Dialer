package dialer.signing

import org.gradle.api.GradleException
import org.gradle.api.provider.ProviderFactory

class SharedKey(
    val storeFile: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
) {
    companion object {
        private const val STORE_FILE = "dialer.sharedKey.storeFile"
        private const val STORE_PASSWORD = "dialer.sharedKey.storePassword"
        private const val KEY_ALIAS = "dialer.sharedKey.keyAlias"
        private const val KEY_PASSWORD = "dialer.sharedKey.keyPassword"

        fun fromProperties(providers: ProviderFactory): SharedKey? {
            val values = listOf(STORE_FILE, STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)
                .associateWith { providers.gradleProperty(it).orNull }
            if (values.values.all { it == null }) {
                return null
            }
            val missing = values.filterValues { it == null }.keys
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Signing with the shared key needs all four properties; " +
                        "missing: ${missing.joinToString()}",
                )
            }
            return SharedKey(
                storeFile = values.getValue(STORE_FILE)!!,
                storePassword = values.getValue(STORE_PASSWORD)!!,
                keyAlias = values.getValue(KEY_ALIAS)!!,
                keyPassword = values.getValue(KEY_PASSWORD)!!,
            )
        }
    }
}
