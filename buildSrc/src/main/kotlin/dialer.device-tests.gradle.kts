import dialer.devicetests.AdbTask
import dialer.devicetests.DeviceTestsExtension
import dialer.devicetests.PrepareDeviceTask
import dialer.devicetests.RestoreDeviceTask

val deviceTests = extensions.create<DeviceTestsExtension>("deviceTests")
val androidSerial = providers.environmentVariable("ANDROID_SERIAL")

val connectedTestTasks = tasks.named { Regex("connected[A-Z]\\w*AndroidTest").matches(it) }

val prepareDevice = tasks.register<PrepareDeviceTask>("prepareDevice") {
    group = "verification"
    description = "Roots the device and allows the system Dialer to be replaced for the test run."
    adb.set(deviceTests.adb)
    serial.set(androidSerial)
}

val restoreDevice = tasks.register<RestoreDeviceTask>("restoreDevice") {
    group = "verification"
    description = "Removes the APKs a run installed and restores the system-app update policy."
    adb.set(deviceTests.adb)
    serial.set(androidSerial)
    packages.set(deviceTests.packagesToUninstall.map { listOf(AdbTask.DIALER_PACKAGE) + it })
    mustRunAfter(connectedTestTasks)
}

prepareDevice.configure {
    finalizedBy(restoreDevice)
}

connectedTestTasks.configureEach {
    dependsOn(prepareDevice, deviceTests.installTasks)
    finalizedBy(restoreDevice)
}

afterEvaluate {
    deviceTests.installTasks.get().forEach { path ->
        val taskName = path.substringAfterLast(':')
        project(path.substringBeforeLast(':')).tasks.named { it == taskName }.configureEach {
            mustRunAfter(prepareDevice)
        }
    }
}
