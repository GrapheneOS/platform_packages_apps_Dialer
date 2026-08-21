package com.android.dialer

import com.android.dialer.binary.aosp.AospDialerApplication
import com.android.dialer.binary.common.DialerApplication
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinUnitTest {

    @Test
    fun aospApplicationExtendsDialerApplication() {
        val aospApplication = AospDialerApplication::class.java

        assertTrue(DialerApplication::class.java.isAssignableFrom(aospApplication))
    }
}
