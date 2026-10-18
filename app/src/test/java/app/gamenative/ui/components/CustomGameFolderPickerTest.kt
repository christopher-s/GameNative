package app.gamenative.ui.components

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CustomGameFolderPickerTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun primaryVolumeFolderResolvesToFilesystemPath() {
        val uri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3AMyGames",
        )

        val expected = File(Environment.getExternalStorageDirectory(), "MyGames").path

        assertEquals(expected, getPathFromTreeUri(context, uri))
    }

    @Test
    fun primaryVolumeRootResolvesToExternalStorageRoot() {
        val uri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3A",
        )

        assertEquals(
            Environment.getExternalStorageDirectory().path,
            getPathFromTreeUri(context, uri),
        )
    }

    @Test
    fun unsupportedProviderDoesNotProduceAFilesystemPath() {
        val uri = Uri.parse("content://example.cloud.provider/tree/root%3AGames")

        assertNull(getPathFromTreeUri(context, uri))
    }
}
