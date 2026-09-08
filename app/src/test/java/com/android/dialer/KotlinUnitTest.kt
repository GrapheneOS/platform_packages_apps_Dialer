package com.android.dialer

import com.android.dialer.binary.aosp.AospDialerApplication
import com.android.dialer.binary.common.DialerApplication
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinUnitTest {
    // Hilt's bytecode transform splices Hilt_AospDialerApplication in between the two, so this
    // is a transitive check rather than a direct-superclass one.
    @Test
    fun aospApplicationExtendsDialerApplication() {
        val aospApplication = AospDialerApplication::class.java

        assertTrue(DialerApplication::class.java.isAssignableFrom(aospApplication))
    }
}
