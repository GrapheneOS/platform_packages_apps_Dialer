package com.android.dialer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KotlinInstrumentedTest {
    @Test
    fun instrumentationTargetsDialer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
    }
}
