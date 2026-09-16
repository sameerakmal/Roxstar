package com.roxstar.voicedraft

import java.io.File
import java.io.RandomAccessFile

/**
 * Startup cleanup: removes WAV files left behind by a process death between
 * [WavFileWriter.open] and [WavFileWriter.finalize] — the header still
 * declares the placeholder size (0 bytes of data) while the file on disk is
 * longer, or (rarer) the header's own two size fields disagree with each
 * other. Either mismatch means finalize() never ran, so the file is
 * incomplete and safe to delete; nothing else writes to filesDir/drafts/.
 *
 * Pure java.io — no Android framework dependency, so this runs under plain
 * JUnit on the host with no device or Robolectric.
 */
object RecordingCleanup {

    private const val HEADER_SIZE = 44

    /** Deletes orphaned .wav files directly inside [draftsDir]. Returns how many were removed. */
    fun removeOrphaned(draftsDir: File): Int {
        val files = draftsDir.listFiles { f -> f.isFile && f.extension.equals("wav", ignoreCase = true) }
            ?: return 0

        var removed = 0
        for (file in files) {
            if (isOrphaned(file)) {
                if (file.delete()) removed++
            }
        }
        return removed
    }

    private fun isOrphaned(file: File): Boolean {
        if (file.length() < HEADER_SIZE) {
            return true  // too short to even hold a valid header
        }
        val header = ByteArray(HEADER_SIZE)
        RandomAccessFile(file, "r").use { raf -> raf.readFully(header) }

        val riffChunkSize = readU32LE(header, 4)
        val dataBytes = readU32LE(header, 40)

        // A finalized file is internally consistent on both counts; anything
        // else means finalize() never patched the header.
        val riffMatchesFileLength = riffChunkSize + 8 == file.length()
        val dataMatchesRiff = dataBytes + 36 == riffChunkSize
        return !(riffMatchesFileLength && dataMatchesRiff)
    }

    private fun readU32LE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}
