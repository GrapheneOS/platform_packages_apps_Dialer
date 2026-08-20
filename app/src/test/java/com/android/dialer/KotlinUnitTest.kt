package com.android.dialer

import com.android.dialer.binary.aosp.AospDialerApplication
import com.android.dialer.binary.common.DialerApplication
import org.junit.Assert.assertEquals
import org.junit.Test

class KotlinUnitTest {
    @Test
    fun aospApplicationExtendsDialerApplication() {
        assertEquals(DialerApplication::class.java, AospDialerApplication::class.java.superclass)
    }
}
