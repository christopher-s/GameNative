package com.winlator.container

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ContainerRuntimePathsTest {

    @Test
    fun defaultDrivesDoNotEmbedAnApplicationId() {
        assertTrue(Container.DEFAULT_DRIVES.contains("D:"))
        assertFalse(Container.DEFAULT_DRIVES.contains("app.gamenative"))
        assertFalse(Container.DEFAULT_DRIVES.contains("/data/data/"))
    }

    @Test
    fun mediaConversionPathsAreBuiltFromTheActiveImageFsHome() {
        val vars = Container.getMediaConvEnvVars("/private/package/files/imagefs/home/xuser")

        assertTrue(vars.all { it.contains("/private/package/files/imagefs/home/xuser/") })
        assertTrue(vars.any { it.endsWith("audio.dmp") })
        assertTrue(vars.any { it.endsWith("transcoded.mkv") })
    }

    @Test
    fun setDrivePathAddsAMissingDrive() {
        val updated = Container.setDrivePath("D:/sdcard/Download", 'E', "/private/package/storage")

        assertTrue(updated.contains("D:/sdcard/Download"))
        assertTrue(updated.contains("E:/private/package/storage"))
    }

    @Test
    fun setDrivePathReplacesAStaleDriveWithoutChangingOthers() {
        val updated = Container.setDrivePath(
            "D:/sdcard/DownloadE:/data/data/app.gamenative/storageA:/games/Test",
            'E',
            "/data/user/0/app.gamenative.mod/storage",
        )

        assertTrue(updated.contains("D:/sdcard/Download"))
        assertTrue(updated.contains("E:/data/user/0/app.gamenative.mod/storage"))
        assertTrue(updated.contains("A:/games/Test"))
        assertFalse(updated.contains("E:/data/data/app.gamenative/storage"))
    }
}
