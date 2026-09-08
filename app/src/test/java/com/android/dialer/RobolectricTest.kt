package com.android.dialer

import android.os.Build
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// Robolectric 4.16.1 only supports through API 36.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.BAKLAVA])
class RobolectricTest {
    @Test
    fun androidViewsWorkOnJvm() {
        val textView = TextView(RuntimeEnvironment.getApplication())

        textView.text = "Dialer"

        assertEquals("Dialer", textView.text.toString())
    }
}
