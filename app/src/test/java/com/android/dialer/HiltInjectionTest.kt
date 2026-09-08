package com.android.dialer

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(
    application = HiltTestApplication::class,
    manifest = Config.NONE,
    sdk = [Build.VERSION_CODES.BAKLAVA],
)
class HiltInjectionTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    @ApplicationContext
    lateinit var applicationContext: Context

    @Test
    fun singletonComponentInjectsApplicationContext() {
        hiltRule.inject()

        assertSame(RuntimeEnvironment.getApplication(), applicationContext)
    }
}
