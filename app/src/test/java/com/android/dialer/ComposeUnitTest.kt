package com.android.dialer

import android.content.ComponentName
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.BAKLAVA])
class ComposeUnitTest {

    @get:Rule(order = 0)
    val componentActivityRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val application = RuntimeEnvironment.getApplication()
                shadowOf(application.packageManager).addActivityIfNotPresent(
                    ComponentName(application, ComponentActivity::class.java),
                )
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    @Test
    fun composableRendersText() {
        composeRule.setContent { Text("Dialer") }

        composeRule.onNodeWithText("Dialer").assertIsDisplayed()
    }
}
