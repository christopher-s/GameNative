package app.gamenative.utils

import app.gamenative.PrefManager
import app.gamenative.service.DownloadService
import java.io.File
import java.nio.file.Paths

/**
 * Resolves game payload locations independently from ImageFS and Wine prefixes.
 *
 * Game payloads may live in primary app-scoped external storage or on a mounted
 * removable volume. Runtime files and prefixes remain in private app storage.
 */
object GameStoragePaths {

    internal fun resolveExternalRoot(
        primaryPath: String,
        selectedPath: String,
        removablePaths: List<String>,
        isUsable: (String) -> Boolean,
    ): String {
        return buildList {
            if (selectedPath.isNotBlank()) add(selectedPath)
            if (primaryPath.isNotBlank()) add(primaryPath)
            addAll(removablePaths.filter { it.isNotBlank() })
        }
            .distinct()
            .firstOrNull(isUsable)
            .orEmpty()
    }

    val selectedExternalRoot: String
        get() = resolveExternalRoot(
            primaryPath = DownloadService.primaryExternalFilesPath,
            selectedPath = PrefManager.externalStoragePath,
            removablePaths = DownloadService.externalVolumePaths,
            isUsable = { path -> File(path).isDirectory && File(path).canWrite() },
        )

    val isExternalStorageReady: Boolean
        get() = PrefManager.useExternalStorage && selectedExternalRoot.isNotBlank()

    private fun pathUnderRoot(root: String, vararg segments: String): String =
        if (root.isBlank()) "" else Paths.get(root, *segments).toString()

    fun steamInstallPath(root: String): String =
        pathUnderRoot(root, "Steam", "steamapps", "common")

    fun steamStagingPath(root: String): String =
        pathUnderRoot(root, "Steam", "steamapps", "staging")

    fun gogInstallPath(root: String): String =
        pathUnderRoot(root, "GOG", "games", "common")

    fun epicInstallPath(root: String): String =
        pathUnderRoot(root, "Epic", "games")

    fun amazonInstallPath(root: String): String =
        pathUnderRoot(root, "Amazon", "games")
}
