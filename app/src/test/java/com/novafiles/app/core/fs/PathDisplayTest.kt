package com.novafiles.app.core.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات عرض المسارات الودّي — خالصة على JVM.
 */
class PathDisplayTest {

    // ═══ normalize ═══

    @Test
    fun normalizeCollapsesDuplicateSlashes() {
        assertEquals("/a/b", PathDisplay.normalize("//a///b"))
    }

    @Test
    fun normalizeStripsTrailingSlash() {
        assertEquals("/a/b", PathDisplay.normalize("/a/b/"))
    }

    @Test
    fun normalizeKeepsDeviceRoot() {
        assertEquals("/", PathDisplay.normalize("/"))
        assertEquals("/", PathDisplay.normalize("///"))
    }

    @Test
    fun normalizeEmptyBecomesRoot() {
        assertEquals("/", PathDisplay.normalize(""))
    }

    // ═══ rootOf ═══

    @Test
    fun rootOfInternalStorageCanonical() {
        assertEquals(StorageRoot.INTERNAL, StorageRoot.of("/storage/emulated/0"))
    }

    @Test
    fun rootOfInternalStorageNested() {
        assertEquals(StorageRoot.INTERNAL, StorageRoot.of("/storage/emulated/0/DCIM/Camera"))
    }

    @Test
    fun rootOfInternalStorageTrailingSlash() {
        assertEquals(StorageRoot.INTERNAL, StorageRoot.of("/storage/emulated/0/"))
    }

    @Test
    fun rootOfSdcardAlias() {
        assertEquals(StorageRoot.INTERNAL, StorageRoot.of("/sdcard"))
        assertEquals(StorageRoot.INTERNAL, StorageRoot.of("/sdcard/Download"))
    }

    @Test
    fun rootOfDeviceRoot() {
        assertEquals(StorageRoot.DEVICE, StorageRoot.of("/"))
    }

    @Test
    fun rootOfUnknownSystemPathFallsBackToDevice() {
        assertEquals(StorageRoot.DEVICE, StorageRoot.of("/data/local/tmp"))
    }

    @Test
    fun rootOfRelativePathIsNull() {
        assertNull(StorageRoot.of("relative/path"))
    }

    // ═══ compactPath ═══

    @Test
    fun compactAtInternalRootIsNull() {
        assertNull(PathDisplay.compactPath("/storage/emulated/0"))
    }

    @Test
    fun compactUnderInternalStripsPrefix() {
        assertEquals("DCIM/Camera", PathDisplay.compactPath("/storage/emulated/0/DCIM/Camera"))
    }

    @Test
    fun compactUnderSdcardStripsAlias() {
        assertEquals("Download", PathDisplay.compactPath("/sdcard/Download"))
    }

    @Test
    fun compactAtDeviceRootIsNull() {
        assertNull(PathDisplay.compactPath("/"))
    }

    @Test
    fun compactUnderDeviceStripsSlash() {
        assertEquals("data/local", PathDisplay.compactPath("/data/local"))
    }

    // ═══ relativeSegments / depth ═══

    @Test
    fun segmentsOfNestedPath() {
        assertEquals(
            listOf("DCIM", "Camera"),
            PathDisplay.relativeSegments("/storage/emulated/0/DCIM/Camera"),
        )
    }

    @Test
    fun segmentsOfRootAreEmpty() {
        assertTrue(PathDisplay.relativeSegments("/storage/emulated/0").isEmpty())
    }

    @Test
    fun depthCountsRelativeLevels() {
        assertEquals(0, PathDisplay.depth("/"))
        assertEquals(0, PathDisplay.depth("/storage/emulated/0"))
        assertEquals(2, PathDisplay.depth("/storage/emulated/0/DCIM/Camera"))
    }
}
