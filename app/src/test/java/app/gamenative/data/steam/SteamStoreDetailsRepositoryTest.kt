package app.gamenative.data.steam

import java.io.File
import java.util.Locale
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SteamStoreDetailsRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cacheDir = createTempDirectory("steam-store-details-").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        cacheDir.deleteRecursively()
    }

    @Test
    fun `loads and maps Steam Store details`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(STORE_RESPONSE))
        val repository = repository(clock = { 1_000L })

        val result = repository.load(
            cacheDir = cacheDir,
            appId = 620,
            locale = Locale.CANADA,
        )

        assertTrue(result is SteamStoreDetailsResult.Success)
        val success = result as SteamStoreDetailsResult.Success
        assertFalse(success.isStale)
        assertEquals(620, success.details.appId)
        assertEquals("Portal 2", success.details.name)
        assertEquals("A test description.", success.details.shortDescriptionHtml)
        assertEquals(listOf("Valve"), success.details.developers)
        assertEquals(listOf("Valve"), success.details.publishers)
        assertEquals(listOf("Action", "Adventure"), success.details.genres)
        assertEquals(2, success.details.screenshots.size)
        assertEquals(95, success.details.metacriticScore)
        assertEquals("Apr 18, 2011", success.details.releaseDate)

        val request = server.takeRequest()
        assertEquals("620", request.requestUrl?.queryParameter("appids"))
        assertEquals("english", request.requestUrl?.queryParameter("l"))
        assertEquals("ca", request.requestUrl?.queryParameter("cc"))
        assertEquals("en-CA", request.getHeader("Accept-Language"))
    }

    @Test
    fun `fresh details are served from cache without a second request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(STORE_RESPONSE))
        val repository = repository(clock = { 1_000L })

        repository.load(cacheDir, 620, Locale.CANADA)
        val second = repository.load(cacheDir, 620, Locale.CANADA)

        assertTrue(second is SteamStoreDetailsResult.Success)
        assertFalse((second as SteamStoreDetailsResult.Success).isStale)
        assertEquals(1, server.requestCount)
        assertTrue(File(cacheDir, "steam-store-details/620-english.json").isFile)
    }

    @Test
    fun `stale cached details are returned immediately and survive a failed refresh`() = runBlocking {
        var now = 1_000L
        server.enqueue(MockResponse().setResponseCode(200).setBody(STORE_RESPONSE))
        val repository = repository(
            clock = { now },
            freshForMillis = 1_000L,
        )
        repository.load(cacheDir, 620, Locale.CANADA)

        now = 3_000L
        val cachedResult = repository.load(cacheDir, 620, Locale.CANADA)

        assertTrue(cachedResult is SteamStoreDetailsResult.Success)
        val cachedSuccess = cachedResult as SteamStoreDetailsResult.Success
        assertTrue(cachedSuccess.isStale)
        assertEquals("Portal 2", cachedSuccess.details.name)
        assertEquals(1, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(503))
        val refreshResult = repository.load(
            cacheDir = cacheDir,
            appId = 620,
            locale = Locale.CANADA,
            forceRefresh = true,
        )

        assertTrue(refreshResult is SteamStoreDetailsResult.Success)
        val refreshed = refreshResult as SteamStoreDetailsResult.Success
        assertTrue(refreshed.isStale)
        assertEquals("Portal 2", refreshed.details.name)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `maps Steam language names`() {
        assertEquals("english", SteamStoreDetailsRepository.steamLanguage(Locale.CANADA))
        assertEquals("brazilian", SteamStoreDetailsRepository.steamLanguage(Locale.forLanguageTag("pt-BR")))
        assertEquals("portuguese", SteamStoreDetailsRepository.steamLanguage(Locale.forLanguageTag("pt-PT")))
        assertEquals("tchinese", SteamStoreDetailsRepository.steamLanguage(Locale.TRADITIONAL_CHINESE))
        assertEquals("schinese", SteamStoreDetailsRepository.steamLanguage(Locale.SIMPLIFIED_CHINESE))
    }

    private fun repository(
        clock: () -> Long,
        freshForMillis: Long = 24L * 60L * 60L * 1000L,
    ): SteamStoreDetailsRepository = SteamStoreDetailsRepository(
        httpClient = OkHttpClient(),
        endpoint = server.url("/api/appdetails"),
        clock = clock,
        freshForMillis = freshForMillis,
    )

    private companion object {
        val STORE_RESPONSE = """
            {
              "620": {
                "success": true,
                "data": {
                  "type": "game",
                  "name": "Portal 2",
                  "steam_appid": 620,
                  "short_description": "A test description.",
                  "detailed_description": "<p>Detailed fallback.</p>",
                  "about_the_game": "<h2>About</h2><p>Test chamber details.</p>",
                  "header_image": "https://cdn.example/header.jpg",
                  "website": "https://example.com",
                  "developers": ["Valve", "Valve"],
                  "publishers": ["Valve"],
                  "genres": [
                    {"id": "1", "description": "Action"},
                    {"id": "2", "description": "Adventure"}
                  ],
                  "screenshots": [
                    {"id": 0, "path_thumbnail": "https://cdn.example/0-thumb.jpg", "path_full": "https://cdn.example/0.jpg"},
                    {"id": 1, "path_thumbnail": "https://cdn.example/1-thumb.jpg", "path_full": "https://cdn.example/1.jpg"},
                    {"id": 2, "path_thumbnail": "http://insecure.example/2-thumb.jpg", "path_full": "http://insecure.example/2.jpg"}
                  ],
                  "metacritic": {"score": 95, "url": "https://metacritic.example/portal2"},
                  "release_date": {"coming_soon": false, "date": "Apr 18, 2011"},
                  "unknown_field": "ignored"
                }
              }
            }
        """.trimIndent()
    }
}
