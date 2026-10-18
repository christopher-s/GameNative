package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class GameStoragePathsTest {

    @Test
    fun persistedUsableRootWinsOverPrimaryStorage() {
        val result = GameStoragePaths.resolveExternalRoot(
            primaryPath = "/primary/files",
            selectedPath = "/usb/files",
            removablePaths = listOf("/usb/files"),
            isUsable = { true },
        )

        assertEquals("/usb/files", result)
    }

    @Test
    fun unusablePersistedRootFallsBackToPrimaryStorage() {
        val result = GameStoragePaths.resolveExternalRoot(
            primaryPath = "/primary/files",
            selectedPath = "/missing/files",
            removablePaths = emptyList(),
            isUsable = { it == "/primary/files" },
        )

        assertEquals("/primary/files", result)
    }

    @Test
    fun removableStorageIsFallbackWhenPrimaryIsUnavailable() {
        val result = GameStoragePaths.resolveExternalRoot(
            primaryPath = "",
            selectedPath = "",
            removablePaths = listOf("/sd/files"),
            isUsable = { it == "/sd/files" },
        )

        assertEquals("/sd/files", result)
    }

    @Test
    fun noUsableStorageReturnsAnEmptyPath() {
        val result = GameStoragePaths.resolveExternalRoot(
            primaryPath = "/primary/files",
            selectedPath = "/missing/files",
            removablePaths = listOf("/sd/files"),
            isUsable = { false },
        )

        assertEquals("", result)
    }

    @Test
    fun storefrontPathsUseStableSubdirectories() {
        val root = "/storage/root"

        assertEquals("/storage/root/Steam/steamapps/common", GameStoragePaths.steamInstallPath(root))
        assertEquals("/storage/root/GOG/games/common", GameStoragePaths.gogInstallPath(root))
        assertEquals("/storage/root/Epic/games", GameStoragePaths.epicInstallPath(root))
        assertEquals("/storage/root/Amazon/games", GameStoragePaths.amazonInstallPath(root))
    }

    @Test
    fun storefrontPathsStayEmptyWhenNoExternalRootExists() {
        assertEquals("", GameStoragePaths.steamInstallPath(""))
        assertEquals("", GameStoragePaths.gogInstallPath(""))
        assertEquals("", GameStoragePaths.epicInstallPath(""))
        assertEquals("", GameStoragePaths.amazonInstallPath(""))
    }
}
