package dialer.devicetests

import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class RestoreDeviceTask : AdbTask() {

    @get:Input
    abstract val packages: ListProperty<String>

    @TaskAction
    fun restore() {
        adb("wait-for-device")
        packages.get().forEach(::uninstallIfInDataApp)
        DEFAULT_POLICY.forEach { (name, value) -> setProperty(name, value) }
    }

    private fun uninstallIfInDataApp(packageName: String) {
        val paths = tryAdb("shell", "pm", "path", packageName)
        if (paths.exitValue != 0 || "/data/app/" !in paths.output) {
            return
        }
        shell("pm", "uninstall", packageName)
    }
}
