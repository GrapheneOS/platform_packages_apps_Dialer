package dialer.devicetests

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

abstract class AdbTask : DefaultTask() {

    @get:Internal
    abstract val adb: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val serial: Property<String>

    @get:Inject
    protected abstract val execOperations: ExecOperations

    protected class AdbResult(val exitValue: Int, val output: String)

    protected fun adb(vararg arguments: String): String {
        val result = tryAdb(*arguments)
        if (result.exitValue != 0) {
            throw GradleException(
                "adb ${arguments.joinToString(" ")} failed with exit code ${result.exitValue}: " +
                    result.output,
            )
        }
        return result.output
    }

    protected fun tryAdb(vararg arguments: String): AdbResult {
        val command = buildList {
            add(adb.get().asFile.absolutePath)
            serial.orNull?.let {
                add("-s")
                add(it)
            }
            addAll(arguments)
        }
        val output = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine(command)
            standardOutput = output
            errorOutput = output
            isIgnoreExitValue = true
        }
        val text = output.toString(Charsets.UTF_8).trim()
        logger.info("{} -> {}", command.joinToString(" "), text)
        return AdbResult(result.exitValue, text)
    }

    protected fun shell(vararg arguments: String): String = adb("shell", *arguments)

    protected fun setProperty(name: String, value: String) {
        shell("setprop", name, value)
    }

    companion object {
        const val DIALER_PACKAGE = "com.android.dialer"

        val UPDATE_POLICY = listOf(
            "persist.disable_install_time_fsverity_check" to "true",
            "persist.disable_same_versionCode_sys_pkg_update_check" to "true",
            "persist.allow_unknown_system_app_updates" to "1",
        )

        val DEFAULT_POLICY = listOf(
            "persist.disable_install_time_fsverity_check" to "false",
            "persist.disable_same_versionCode_sys_pkg_update_check" to "false",
            "persist.allow_unknown_system_app_updates" to "0",
        )
    }
}
