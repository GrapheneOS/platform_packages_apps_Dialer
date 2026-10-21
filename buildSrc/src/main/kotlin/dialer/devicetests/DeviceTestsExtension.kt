package dialer.devicetests

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty

abstract class DeviceTestsExtension {

    abstract val adb: RegularFileProperty

    abstract val installTasks: ListProperty<String>

    abstract val packagesToUninstall: ListProperty<String>
}
