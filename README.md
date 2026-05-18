# Dialer

## Tests

```
atest DialerTests
atest DialerIntegrationTests
```

`DialerTests` and `DialerIntegrationTests` temporarily set persistent system-app update policy
properties so Tradefed can install the bundled Dialer test APKs. The test config clears those
properties before setup and during teardown; if a run is interrupted, rerun the target or reset them
manually before using the device for unrelated testing.

`DialerIntegrationTests` set the system Dialer as the default dialer and install a helper
connection service APK. They expect the screen to be unlocked. They will also ring the device via
Telecom, and some scenarios can make call audio noises when calls are put on hold, so consider
silencing notifications before running if needed.
