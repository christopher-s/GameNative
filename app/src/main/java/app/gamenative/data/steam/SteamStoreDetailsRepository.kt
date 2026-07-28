package app.gamenative.data.steam

import app.gamenative.utils.Net
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import timber.log.Timber

@Serializable
data class SteamStoreScreenshot(
    val id: Int = 0,
    @SerialName("path_thumbnail") val thumbnailUrl: String = "",
    @SerialName("path_full") val fullUrl: String = "",
)

@Serializable
data class SteamStoreGenre(
    val id: String = "",
    val description: String = "",
)

@Serializable
data class SteamStoreMetacritic(
    val score: Int = 0,
    val url: String = "",
)

@Serializable
data class SteamStoreReleaseDate(
    @SerialName("coming_soon") val comingSoon: Boolean = false,
    val date: String = "",
)

@Serializable
data class SteamStoreDetails(
    val appId: Int,
    val name: String,
    val shortDescriptionHtml: String,
    val aboutTheGameHtml: String,
    val developers: List<String>,
    val publishers: List<String>,
    val genres: List<String>,
    val screenshots: List<SteamStoreScreenshot>,
    val metacriticScore: Int?,
    val metacriticUrl: String?,
    val releaseDate: String,
    val comingSoon: Boolean,
    val website: String?,
    val headerImageUrl: String?,
)

sealed interface SteamStoreDetailsResult {
    data class Success(
        val details: SteamStoreDetails,
        val isStale: Boolean = false,
    ) : SteamStoreDetailsResult

    data class Failure(
        val message: String,
    ) : SteamStoreDetailsResult
}

@Serializable
private data class SteamStoreEnvelope(
    val success: Boolean = false,
    val data: SteamStoreResponseData? = null,
)

@Serializable
private data class SteamStoreResponseData(
    val type: String = "",
    val name: String = "",
    @SerialName("steam_appid") val appId: Int = 0,
    @SerialName("short_description") val shortDescription: String = "",
    @SerialName("detailed_description") val detailedDescription: String = "",
    @SerialName("about_the_game") val aboutTheGame: String = "",
    @SerialName("header_image") val headerImage: String = "",
    val website: String? = null,
    val developers: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    val genres: List<SteamStoreGenre> = emptyList(),
    val screenshots: List<SteamStoreScreenshot> = emptyList(),
    val metacritic: SteamStoreMetacritic? = null,
    @SerialName("release_date") val releaseDate: SteamStoreReleaseDate = SteamStoreReleaseDate(),
)

@Serializable
private data class SteamStoreCacheEntry(
    val cachedAtMillis: Long,
    val details: SteamStoreDetails,
)

class SteamStoreDetailsRepository(
    private val httpClient: OkHttpClient = Net.http,
    private val endpoint: HttpUrl = DEFAULT_ENDPOINT.toHttpUrl(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val freshForMillis: Long = DEFAULT_CACHE_TTL_MILLIS,
) {
    private val memoryCache = ConcurrentHashMap<String, SteamStoreCacheEntry>()

    suspend fun load(
        cacheDir: File,
        appId: Int,
        locale: Locale = Locale.getDefault(),
        forceRefresh: Boolean = false,
    ): SteamStoreDetailsResult = withContext(ioDispatcher) {
        require(appId > 0) { "Steam appId must be positive" }

        val language = steamLanguage(locale)
        val country = locale.country.lowercase(Locale.ROOT).takeIf { it.length == 2 } ?: DEFAULT_COUNTRY
        val acceptLanguage = locale.toLanguageTag().takeIf { it.isNotBlank() && it != "und" } ?: "en-CA"
        val cacheKey = "$appId-$language"
        val cacheFile = cacheFile(cacheDir, cacheKey)
        val cached = memoryCache[cacheKey] ?: readCache(cacheFile)?.also {
            memoryCache[cacheKey] = it
        }

        if (!forceRefresh && cached != null) {
            return@withContext SteamStoreDetailsResult.Success(
                details = cached.details,
                isStale = !isFresh(cached),
            )
        }

        try {
            val details = fetch(appId, language, country, acceptLanguage)
            val entry = SteamStoreCacheEntry(clock(), details)
            memoryCache[cacheKey] = entry
            writeCache(cacheFile, entry)
            SteamStoreDetailsResult.Success(details)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Steam Store details fetch failed for appId %d", appId)
            if (cached != null) {
                SteamStoreDetailsResult.Success(cached.details, isStale = true)
            } else {
                SteamStoreDetailsResult.Failure(e.message ?: "Steam Store details are unavailable")
            }
        }
    }

    private fun fetch(
        appId: Int,
        language: String,
        country: String,
        acceptLanguage: String,
    ): SteamStoreDetails {
        val url = endpoint.newBuilder()
            .addQueryParameter("appids", appId.toString())
            .addQueryParameter("l", language)
            .addQueryParameter("cc", country)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Accept-Language", acceptLanguage)
            .header("User-Agent", USER_AGENT)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Steam Store returned HTTP ${response.code}")
            }
            val body = response.body?.string()?.removePrefix("\uFEFF")
                ?: error("Steam Store returned an empty response")
            val envelope = json.decodeFromString<Map<String, SteamStoreEnvelope>>(body)[appId.toString()]
                ?: error("Steam Store response did not contain app $appId")
            val data = envelope.data?.takeIf { envelope.success }
                ?: error("Steam Store does not provide details for app $appId")
            return data.toDetails(appId)
        }
    }

    private fun SteamStoreResponseData.toDetails(requestedAppId: Int): SteamStoreDetails {
        val resolvedAppId = appId.takeIf { it > 0 } ?: requestedAppId
        return SteamStoreDetails(
            appId = resolvedAppId,
            name = name.trim(),
            shortDescriptionHtml = shortDescription.trim(),
            aboutTheGameHtml = aboutTheGame.ifBlank { detailedDescription }.trim(),
            developers = developers.cleanStrings(),
            publishers = publishers.cleanStrings(),
            genres = genres.map { it.description }.cleanStrings(),
            screenshots = screenshots
                .asSequence()
                .filter { it.fullUrl.startsWith("https://") }
                .distinctBy { it.fullUrl }
                .take(MAX_SCREENSHOTS)
                .toList(),
            metacriticScore = metacritic?.score?.takeIf { it in 1..100 },
            metacriticUrl = metacritic?.url?.takeIf { it.startsWith("https://") },
            releaseDate = releaseDate.date.trim(),
            comingSoon = releaseDate.comingSoon,
            website = website?.trim()?.takeIf { it.startsWith("https://") },
            headerImageUrl = headerImage.trim().takeIf { it.startsWith("https://") },
        )
    }

    private fun List<String>.cleanStrings(): List<String> =
        asSequence().map(String::trim).filter(String::isNotBlank).distinct().toList()

    private fun isFresh(entry: SteamStoreCacheEntry): Boolean {
        val age = (clock() - entry.cachedAtMillis).coerceAtLeast(0L)
        return age <= freshForMillis
    }

    private fun cacheFile(cacheDir: File, cacheKey: String): File =
        File(File(cacheDir, CACHE_DIRECTORY), "$cacheKey.json")

    private fun readCache(file: File): SteamStoreCacheEntry? {
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<SteamStoreCacheEntry>(file.readText()) }
            .onFailure {
                Timber.tag(TAG).w(it, "Discarding corrupt Steam Store cache %s", file.name)
                file.delete()
            }
            .getOrNull()
    }

    private fun writeCache(file: File, entry: SteamStoreCacheEntry) {
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(json.encodeToString(entry))
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        }.onFailure {
            Timber.tag(TAG).w(it, "Unable to write Steam Store cache %s", file.name)
        }
    }

    companion object {
        private const val TAG = "SteamStoreDetails"
        private const val DEFAULT_ENDPOINT = "https://store.steampowered.com/api/appdetails"
        private const val USER_AGENT = "GameNative Android (Steam Store details)"
        private const val CACHE_DIRECTORY = "steam-store-details"
        private const val DEFAULT_COUNTRY = "ca"
        private const val MAX_SCREENSHOTS = 20
        private const val DEFAULT_CACHE_TTL_MILLIS = 24L * 60L * 60L * 1000L

        val shared: SteamStoreDetailsRepository by lazy { SteamStoreDetailsRepository() }

        internal fun steamLanguage(locale: Locale): String = when (locale.language.lowercase(Locale.ROOT)) {
            "ar" -> "arabic"
            "bg" -> "bulgarian"
            "cs" -> "czech"
            "da" -> "danish"
            "de" -> "german"
            "el" -> "greek"
            "es" -> "spanish"
            "fi" -> "finnish"
            "fr" -> "french"
            "hu" -> "hungarian"
            "id" -> "indonesian"
            "it" -> "italian"
            "ja" -> "japanese"
            "ko" -> "koreana"
            "nl" -> "dutch"
            "no", "nb", "nn" -> "norwegian"
            "pl" -> "polish"
            "pt" -> if (locale.country.equals("BR", ignoreCase = true)) "brazilian" else "portuguese"
            "ro" -> "romanian"
            "ru" -> "russian"
            "sv" -> "swedish"
            "th" -> "thai"
            "tr" -> "turkish"
            "uk" -> "ukrainian"
            "vi" -> "vietnamese"
            "zh" -> if (locale.country.equals("TW", ignoreCase = true) || locale.country.equals("HK", ignoreCase = true)) {
                "tchinese"
            } else {
                "schinese"
            }
            else -> "english"
        }

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
