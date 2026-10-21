package dialer.devicetests

import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction

abstract class PrepareDeviceTask : AdbTask() {

    @TaskAction
    fun prepare() {
        val root = adb("root")
        if ("cannot run as root" in root) {
            throw GradleException(
                "The suites install over the system Dialer and need adb root, which this device " +
                    "refuses: $root. Use a userdebug or eng build.",
            )
        }
        adb("wait-for-device")
        waitForShell()

        DEFAULT_POLICY.forEach { (name, value) -> setProperty(name, value) }
        UPDATE_POLICY.forEach { (name, value) -> setProperty(name, value) }
        shell("am", "force-stop", DIALER_PACKAGE)
    }

    private fun waitForShell() {
        var attempt = 0
        while (true) {
            try {
                shell("true")
                return
            } catch (e: GradleException) {
                if (++attempt == SHELL_ATTEMPTS) throw e
                Thread.sleep(SHELL_RETRY_DELAY_MS)
            }
        }
    }

    private companion object {
        const val SHELL_ATTEMPTS = 20
        const val SHELL_RETRY_DELAY_MS = 500L
    }
}
