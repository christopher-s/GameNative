package app.gamenative.utils

import android.content.Intent
import app.gamenative.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntentLaunchManagerTest {

    @Test
    fun launchActionUsesTheCurrentApplicationId() {
        assertEquals("${BuildConfig.APPLICATION_ID}.LAUNCH_GAME", IntentLaunchManager.launchGameAction)
    }

    @Test
    fun parserAcceptsTheVariantSpecificLaunchAction() {
        val request = IntentLaunchManager.parseLaunchIntent(
            Intent(IntentLaunchManager.launchGameAction)
                .putExtra("app_id", 123)
                .putExtra("game_source", "steam"),
        )

        assertNotNull(request)
        assertEquals("STEAM_123", request?.appId)
    }

    @Test
    fun parserRejectsAnActionForAnotherApplicationId() {
        val request = IntentLaunchManager.parseLaunchIntent(
            Intent("another.package.LAUNCH_GAME")
                .putExtra("app_id", 123),
        )

        assertNull(request)
    }
}
