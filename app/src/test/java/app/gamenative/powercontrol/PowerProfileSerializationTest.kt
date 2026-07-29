package app.gamenative.powercontrol

import app.gamenative.powercontrol.profiles.CpuGovernor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PowerProfileSerializationTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun defaultProfile_whenCreated_disablesAutoTuning() {
        val profile = PowerProfile(
            name = "Custom",
            governor = CpuGovernor.SCHEDUTIL,
            minCpuFreq = 300000,
            maxCpuFreq = 1000000,
        )

        assertFalse(profile.enableAutoTuning)
    }

    @Test
    fun legacyProfile_whenDriverIdIsAbsent_decodesWithNullDriverId() {
        val profile = json.decodeFromString<PowerProfile>(
            """{"name":"Custom","governor":"SCHEDUTIL","minCpuFreq":300000,"maxCpuFreq":1000000,"enableAutoTuning":true}""",
        )

        assertNull(profile.driverId)
        assertEquals(true, profile.enableAutoTuning)
    }

    @Test
    fun legacyProfile_whenAutoTuningIsAbsent_disablesAutoTuning() {
        val profile = json.decodeFromString<PowerProfile>(
            """{"name":"Custom","governor":"SCHEDUTIL","minCpuFreq":300000,"maxCpuFreq":1000000}""",
        )

        assertFalse(profile.enableAutoTuning)
    }

    @Test
    fun profile_whenUnknownFieldsArePresent_decodesKnownFields() {
        val profile = json.decodeFromString<PowerProfile>(
            """{"name":"Custom","governor":"SCHEDUTIL","minCpuFreq":300000,"maxCpuFreq":1000000,"futureField":"ignored"}""",
        )

        assertEquals("Custom", profile.name)
    }

    @Test
    fun profile_whenDriverIdIsPresent_preservesDriverId() {
        val profile = PowerProfile(
            driverId = "pserver",
            name = "Custom",
            governor = CpuGovernor.SCHEDUTIL,
            minCpuFreq = 300000,
            maxCpuFreq = 1000000,
        )

        val restored = json.decodeFromString<PowerProfile>(json.encodeToString(profile))

        assertEquals("pserver", restored.driverId)
    }

    @Test
    fun profile_whenEncoded_includesNullDriverId() {
        val profile = PowerProfile(
            name = "Custom",
            governor = CpuGovernor.SCHEDUTIL,
            minCpuFreq = 300000,
            maxCpuFreq = 1000000,
        )

        val encoded = json.encodeToJsonElement(PowerProfile.serializer(), profile).jsonObject

        assertEquals(JsonNull, encoded.getValue("driverId"))
    }
}
