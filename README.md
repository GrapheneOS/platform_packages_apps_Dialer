# Dialer

# Tests

```
atest DialerTests
```

`DialerTests` temporarily sets persistent system-app update policy properties so
Tradefed can install the bundled Dialer test APKs. The test config clears those
properties before setup and during teardown; if a run is interrupted, rerun the
target or reset them manually before using the device for unrelated testing.
