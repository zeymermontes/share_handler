package com.shoutsocial.share_handler

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ShareInboxTest {
    private lateinit var cacheDir: File
    private lateinit var inbox: File

    @Before
    fun setUp() {
        cacheDir = Files.createTempDirectory("share-cache").toFile()
        inbox = File(cacheDir, ShareInbox.DIR_NAME)
    }

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    @Test
    fun relativeDisplayNameStaysInsideInbox() {
        val target = ShareInbox.targetFor(inbox, "id-1", "../shared_prefs/x.xml", null)
        assertEquals("x.xml", target.name)
        assertEquals(File(inbox, "id-1").canonicalPath, target.parentFile!!.canonicalPath)
        assertTrue(ShareInbox.isInside(inbox, target))
        assertFalse(target.canonicalPath.contains("shared_prefs"))
    }

    @Test
    fun nestedPathsAndBackslashesAreStripped() {
        listOf(
            "../../../../data/data/com.example.app/shared_prefs/settings.xml",
            "..\\..\\app_flutter\\data.sqlite",
            "/absolute/path/lib.so",
        ).forEach { raw ->
            val target = ShareInbox.targetFor(inbox, "id", raw, null)
            assertTrue(raw, ShareInbox.isInside(inbox, target))
            assertEquals(raw, File(inbox, "id").canonicalPath, target.parentFile!!.canonicalPath)
        }
    }

    @Test
    fun dotNamesFallBackToGenericName() {
        assertNull(ShareInbox.sanitizeDisplayName(".."))
        assertNull(ShareInbox.sanitizeDisplayName("."))
        assertNull(ShareInbox.sanitizeDisplayName("../"))
        assertNull(ShareInbox.sanitizeDisplayName("\u0000"))
        assertEquals("file.pdf", ShareInbox.targetFor(inbox, "id", "..", "pdf").name)
        assertEquals("hidden", ShareInbox.sanitizeDisplayName(".hidden"))
    }

    @Test
    fun keepsRealNamesAndAddsMimeExtension() {
        assertEquals("Informe final.pdf", ShareInbox.fileName("Informe final.pdf", "pdf"))
        assertEquals("IMG_0001.jpg", ShareInbox.fileName("IMG_0001.jpg", "jpeg"))
        assertEquals("audio.ogg", ShareInbox.fileName("audio", "ogg"))
        assertEquals("file.mp4", ShareInbox.fileName(null, "mp4"))
        assertEquals("file", ShareInbox.fileName(null, "../x"))
    }

    @Test
    fun longNamesAreCutKeepingTheExtension() {
        val name = ShareInbox.fileName("a".repeat(500) + ".pdf", null)
        assertEquals(120, name.length)
        assertTrue(name.endsWith(".pdf"))
    }

    @Test(expected = SecurityException::class)
    fun idWithSeparatorsIsRejected() {
        ShareInbox.targetFor(inbox, "../x", "a.txt", null)
    }

    @Test
    fun isInsideRejectsSiblingsAndTheInboxItself() {
        assertFalse(ShareInbox.isInside(inbox, File(cacheDir, "share_in_other/a")))
        assertFalse(ShareInbox.isInside(inbox, inbox))
        assertFalse(ShareInbox.isInside(inbox, File(inbox, "../shared_prefs/x.xml")))
    }

    @Test
    fun pruneDropsOnlyOldEntries() {
        val old = File(inbox, "old").apply { mkdirs() }
        File(old, "a.txt").writeText("a")
        val fresh = File(inbox, "fresh").apply { mkdirs() }
        old.setLastModified(1_000L)
        fresh.setLastModified(100_000L)
        ShareInbox.prune(inbox, nowMs = 100_500L, maxAgeMs = 50_000L)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }
}
