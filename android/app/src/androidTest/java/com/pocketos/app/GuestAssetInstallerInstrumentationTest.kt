package com.pocketos.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuestAssetInstallerInstrumentationTest {
    @Test
    fun installsPackagedApkAssetsIntoPrivateStorageAndKeepsDiskOnRepeat() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installer = GuestAssetInstaller.forContext(context)

        val first = installer.install()
        assertTrue(first.kernel.isFile && first.kernel.length() > 0)
        assertTrue(first.initramfs.isFile && first.initramfs.length() > 0)
        assertTrue(first.rootDisk.isFile)
        assertTrue(first.rootDisk.absolutePath.startsWith(context.filesDir.absolutePath))
        assertEquals(256L * 1024L * 1024L, first.rootDisk.length())

        val second = installer.install()
        assertFalse(second.seedDiskCreated)
        assertEquals(first.rootDisk.length(), second.rootDisk.length())
        assertEquals(first.rootDisk.absolutePath, second.rootDisk.absolutePath)
    }
}
