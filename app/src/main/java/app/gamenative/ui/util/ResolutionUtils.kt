package app.gamenative.ui.util

import android.content.Context
import android.os.Build
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Rounds a float value to the nearest even integer.
 * This is required by many mobile GPU drivers to avoid rendering artifacts or crashes
 * when using resolutions that are not divisible by 2.
 */
internal fun evenRound(value: Float): Int = (value / 2.0f).roundToInt() * 2

internal fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

private val wellKnownAspectRatios = mapOf(
    (13 to 6) to "19.5:9",
    (43 to 18) to "21.5:9",
)

internal fun calculateAspectRatio(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) return "$width:$height"
    val common = gcd(width, height)
    val reduced = (width / common) to (height / common)
    return wellKnownAspectRatios[reduced] ?: "${reduced.first}:${reduced.second}"
}

internal fun parseResolution(entry: String): Pair<Int, Int>? {
    val dims = entry.substringBefore(' ').split('x')
    val width = dims.getOrNull(0)?.toIntOrNull() ?: return null
    val height = dims.getOrNull(1)?.toIntOrNull() ?: return null
    return width to height
}

/**
 * Physical panel resolution as (long side, short side). Uses WindowMetrics on API 30+
 * so multi-window and foldable states still report the real display size instead of
 * the app window bounds that resources.displayMetrics would give.
 */
internal fun deviceNativeResolution(context: Context): Pair<Int, Int> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            return maxOf(bounds.width(), bounds.height()) to minOf(bounds.width(), bounds.height())
        }
    }
    val metrics = context.resources.displayMetrics
    return maxOf(metrics.widthPixels, metrics.heightPixels) to minOf(metrics.widthPixels, metrics.heightPixels)
}

/**
 * Builds the device-specific screen-size entries (native, 75%, 50%) that are not
 * already covered by [baseScreenSizes]. Entries are formatted as "WxH (ratio, label)".
 */
internal fun generateAdaptiveScreenSizes(
    deviceWidth: Int,
    deviceHeight: Int,
    baseScreenSizes: List<String>,
    nativeLabel: String,
    optimizedLabel: String,
    halfLabel: String,
): List<String> {
    val baseResolutions = baseScreenSizes.mapNotNull { parseResolution(it) }
    val seen = mutableSetOf<Pair<Int, Int>>()
    return listOf(1.0f to nativeLabel, 0.75f to optimizedLabel, 0.5f to halfLabel)
        .mapNotNull { (scale, label) ->
            val resolution = evenRound(deviceWidth * scale) to evenRound(deviceHeight * scale)
            if (resolution.first <= 0 || resolution.second <= 0) return@mapNotNull null
            if (resolution in baseResolutions || !seen.add(resolution)) return@mapNotNull null
            "${resolution.first}x${resolution.second} (${calculateAspectRatio(resolution.first, resolution.second)}, $label)"
        }
}
