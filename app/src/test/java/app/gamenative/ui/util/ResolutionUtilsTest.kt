package app.gamenative.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionUtilsTest {

    @Test
    fun evenRound_roundsToNearestEven() {
        assertEquals(1280, evenRound(1280.0f))
        assertEquals(1280, evenRound(1280.4f))
        assertEquals(1282, evenRound(1281.0f))
        assertEquals(1282, evenRound(1281.6f))
        assertEquals(720, evenRound(720.0f))
        assertEquals(720, evenRound(719.1f))
        assertEquals(0, evenRound(0.0f))
        assertEquals(2, evenRound(1.0f))
    }

    @Test
    fun gcd_calculatesCorrectly() {
        assertEquals(80, gcd(1280, 720))
        assertEquals(120, gcd(1920, 1080))
        assertEquals(1, gcd(13, 7))
        assertEquals(10, gcd(100, 10))
    }

    @Test
    fun calculateAspectRatio_returnsCorrectRatios() {
        assertEquals("16:9", calculateAspectRatio(1920, 1080))
        assertEquals("16:9", calculateAspectRatio(1280, 720))
        assertEquals("4:3", calculateAspectRatio(1024, 768))
        assertEquals("4:3", calculateAspectRatio(800, 600))
        assertEquals("19.5:9", calculateAspectRatio(2340, 1080))
        assertEquals("21.5:9", calculateAspectRatio(2580, 1080))
        assertEquals("20:9", calculateAspectRatio(2400, 1080))
        assertEquals("5:2", calculateAspectRatio(1000, 400))
    }

    @Test
    fun calculateAspectRatio_zeroDimensionsDoNotCrash() {
        assertEquals("0:0", calculateAspectRatio(0, 0))
        assertEquals("0:1080", calculateAspectRatio(0, 1080))
    }

    @Test
    fun parseResolution_parsesBareAndLabelledEntries() {
        assertEquals(1280 to 720, parseResolution("1280x720"))
        assertEquals(2400 to 1080, parseResolution("2400x1080 (20:9, Native)"))
        assertNull(parseResolution("Custom"))
        assertNull(parseResolution("1280x"))
    }

    @Test
    fun generateAdaptiveScreenSizes_addsAllScalesWhenNoOverlap() {
        val result = generateAdaptiveScreenSizes(
            deviceWidth = 2400,
            deviceHeight = 1080,
            baseScreenSizes = listOf("Custom", "1280x720 (16:9)"),
            nativeLabel = "Native",
            optimizedLabel = "75%",
            halfLabel = "50%",
        )
        assertEquals(
            listOf(
                "2400x1080 (20:9, Native)",
                "1800x810 (20:9, 75%)",
                "1200x540 (20:9, 50%)",
            ),
            result,
        )
    }

    @Test
    fun generateAdaptiveScreenSizes_skipsResolutionsCoveredByPresets() {
        val result = generateAdaptiveScreenSizes(
            deviceWidth = 1280,
            deviceHeight = 720,
            baseScreenSizes = listOf("Custom", "1280x720 (16:9)"),
            nativeLabel = "Native",
            optimizedLabel = "75%",
            halfLabel = "50%",
        )
        assertTrue(result.none { it.startsWith("1280x720") })
        assertEquals(2, result.size)
    }

    @Test
    fun generateAdaptiveScreenSizes_dedupesAmongScalesAndRejectsZero() {
        // Tiny screen where 100% and 75% round to the same resolution.
        val tiny = generateAdaptiveScreenSizes(4, 2, emptyList(), "N", "O", "H")
        assertEquals(tiny.map { it.substringBefore(' ') }.distinct().size, tiny.size)

        // Zero-sized display produces no entries instead of "0x0" garbage.
        assertTrue(generateAdaptiveScreenSizes(0, 0, emptyList(), "N", "O", "H").isEmpty())
    }
}
