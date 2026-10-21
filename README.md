# Dialer

## Tests

The suites instrument the Dialer installed on the device, so the test build of the Dialer has to
be signed with the key of the system one: the `shared` platform key of a build signed with test
keys. Convert it into a PKCS#12 keystore once, outside the repository:

```
openssl pkcs8 -inform DER -nocrypt -in build/make/target/product/security/shared.pk8 -out shared.key
openssl pkcs12 -export -in build/make/target/product/security/shared.x509.pem -inkey shared.key \
    -name shared -out shared.p12
rm shared.key
```

Point Gradle at it, typically in `~/.gradle/gradle.properties`:

```
dialer.sharedKey.storeFile=/path/to/shared.p12
dialer.sharedKey.storePassword=...
dialer.sharedKey.keyAlias=shared
dialer.sharedKey.keyPassword=...
```

Without the properties the test build and the helper APK are signed with the debug key, which is
enough to compile everything but not to install them on a device.

The device must run a debuggable build signed with test keys. Set `ANDROID_SERIAL` if more than
one device is attached, then run a suite:

```
./gradlew :dialer-tests:connectedSystemTestAndroidTest
./gradlew :dialer-integration-tests:connectedSystemTestAndroidTest
./gradlew :dialer-outofprocess-tests:connectedAndroidTest
```

Run each suite in its own Gradle invocation: the cleanup after one suite removes APKs the next one
needs, and Gradle does not install them again within the same invocation.

Each run roots the device and temporarily sets persistent system-app update policy properties so
the test build of the Dialer can be installed over the system one. The properties are reset when
the run ends; if a run is interrupted, rerun the target or reset them manually before using the
device for unrelated testing. The test build of the Dialer is uninstalled after the run, which
restores the system one. The device stays rooted; run `adb unroot` to undo that.

`DialerIntegrationTests` set the system Dialer as the default dialer and install a helper
connection service APK. They expect the screen to be unlocked. They will also ring the device via
Telecom, and some scenarios can make call audio noises when calls are put on hold, so consider
silencing notifications before running if needed.

`DialerOutOfProcessTests` run outside Dialer so they can kill and restart Dialer during live
Telecom calls. They use the same helper connection service APK.
