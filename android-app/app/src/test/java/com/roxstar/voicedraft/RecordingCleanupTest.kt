package com.roxstar.voicedraft

import java.io.File
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordingCleanupTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("drafts", "").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun removesFileWithPlaceholderHeaderAndRealData() {
        // Exactly what a crash between open() and finalize() leaves behind:
        // header says 0 data bytes, but the file is longer.
        writeWav(File(dir, "orphan.wav"), riffSize = 36, dataSize = 0, totalLength = 44 + 6)

        val removed = RecordingCleanup.removeOrphaned(dir)

        assertEquals(1, removed)
        assertFalse(File(dir, "orphan.wav").exists())
    }

    @Test
    fun keepsAFinalizedFileWhoseHeaderMatchesItsLength() {
        writeWav(File(dir, "good.wav"), riffSize = 44, dataSize = 8, totalLength = 44 + 8)

        val removed = RecordingCleanup.removeOrphaned(dir)

        assertEquals(0, removed)
        assertTrue(File(dir, "good.wav").exists())
    }

    @Test
    fun removesFileShorterThanAHeader() {
        File(dir, "tiny.wav").writeBytes(ByteArray(10))

        val removed = RecordingCleanup.removeOrphaned(dir)

        assertEquals(1, removed)
        assertFalse(File(dir, "tiny.wav").exists())
    }

    @Test
    fun removesFileWhoseInternalSizeFieldsDisagreeWithEachOther() {
        // riffSize says the file should be 44+8, but dataSize says it should
        // be 44+16 — the header itself is inconsistent, regardless of length.
        writeWav(File(dir, "inconsistent.wav"), riffSize = 44, dataSize = 16, totalLength = 44 + 8)

        val removed = RecordingCleanup.removeOrphaned(dir)

        assertEquals(1, removed)
    }

    @Test
    fun ignoresNonWavFiles() {
        File(dir, "notes.txt").writeText("not audio")

        val removed = RecordingCleanup.removeOrphaned(dir)

        assertEquals(0, removed)
        assertTrue(File(dir, "notes.txt").exists())
    }

    @Test
    fun handlesMissingDirectoryWithoutCrashing() {
        val missing = File(dir, "does-not-exist")
        assertEquals(0, RecordingCleanup.removeOrphaned(missing))
    }

    @Test
    fun handlesMultipleFilesRemovingOnlyOrphans() {
        writeWav(File(dir, "good1.wav"), riffSize = 44, dataSize = 8, totalLength = 44 + 8)
        writeWav(File(dir, "orphan1.wav"), riffSize = 36, dataSize = 0, totalLength = 44 + 100)
        writeWav(File(dir, "good2.wav"), riffSize = 236, dataSize = 200, totalLength = 44 + 200)

        val removed = RecordingCleanup.removeOrphaned(dir)

        assertEquals(1, removed)
        assertTrue(File(dir, "good1.wav").exists())
        assertTrue(File(dir, "good2.wav").exists())
        assertFalse(File(dir, "orphan1.wav").exists())
    }

    /** Writes a syntactically-44-byte-header WAV with the given (possibly inconsistent) sizes. */
    private fun writeWav(file: File, riffSize: Int, dataSize: Int, totalLength: Int) {
        val header = ByteArray(44)
        "RIFF".toByteArray().copyInto(header, 0)
        putU32LE(header, 4, riffSize)
        "WAVE".toByteArray().copyInto(header, 8)
        "fmt ".toByteArray().copyInto(header, 12)
        putU32LE(header, 16, 16)
        header[20] = 1; header[22] = 1  // PCM, mono (u16 LE, high byte 0)
        putU32LE(header, 24, 16000)
        putU32LE(header, 28, 32000)
        header[32] = 2; header[34] = 16
        "data".toByteArray().copyInto(header, 36)
        putU32LE(header, 40, dataSize)

        RandomAccessFile(file, "rw").use { raf ->
            raf.write(header)
            val padding = (totalLength - header.size).coerceAtLeast(0)
            if (padding > 0) raf.write(ByteArray(padding))
        }
    }

    private fun putU32LE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
