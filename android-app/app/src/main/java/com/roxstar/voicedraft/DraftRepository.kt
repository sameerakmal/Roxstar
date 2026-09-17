package com.roxstar.voicedraft

import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

/**
 * Persistence layer for local [Draft] records.
 *
 * Layout inside [draftsDir] (typically `filesDir/drafts/`):
 * ```
 *   <id>.wav   — the audio file, written by the native engine
 *   <id>.json  — the metadata record, written by this class after a successful stop
 * ```
 *
 * Serialization format: Java [Properties] (key=value), stored in `<id>.json`.
 * This format is available on both Android and the JVM host, so tests run
 * without Robolectric or the Android framework.
 *
 * ## Reconciliation rules (documented — must not be changed silently)
 *
 * | WAV present | JSON present | Outcome |
 * |---|---|---|
 * | ✅ | ✅ | Normal Draft — loaded and shown |
 * | ❌ | ✅ | `DraftWithStatus(wavMissing = true)` — shown greyed out; JSON deleted on Delete |
 * | ✅ | ❌ | **Orphan WAV**: `RecordingCleanup` already removes crash-incomplete WAVs (invalid header). A finalized WAV with no JSON is left alone and NOT auto-promoted — the native engine never creates a valid WAV without Kotlin subsequently calling [saveDraft], so this state should be unreachable in normal operation. If encountered, it is silently ignored. |
 * | ❌ | ❌ | Nothing to do |
 *
 * All public methods are safe to call from any thread, but callers should use
 * a background dispatcher (e.g. `Dispatchers.IO`) to avoid blocking the main thread.
 */
class DraftRepository(private val draftsDir: File) {

    init {
        draftsDir.mkdirs()
    }

    /**
     * Persists [draft]'s metadata as `<draft.id>.json` next to the WAV file.
     * Idempotent: overwriting with the same id is safe (e.g. retry after crash).
     */
    fun saveDraft(draft: Draft) {
        val props = Properties().apply {
            setProperty(KEY_ID, draft.id)
            setProperty(KEY_NAME, draft.name)
            setProperty(KEY_CREATED_AT, draft.createdAt.toString())
            setProperty(KEY_DURATION_MS, draft.durationMs.toString())
            setProperty(KEY_EFFECT, draft.effect.name)
            setProperty(KEY_FILE_PATH, draft.filePath)
        }
        metadataFile(draft.id).outputStream().use { out ->
            props.store(out, /* comments= */ null)
        }
    }

    /**
     * Scans [draftsDir] for `*.json` metadata files and returns one [DraftWithStatus]
     * per record, sorted newest-first by [Draft.createdAt].
     *
     * Malformed files are skipped silently.
     */
    fun loadAll(): List<DraftWithStatus> {
        val jsonFiles = draftsDir.listFiles { f ->
            f.isFile && f.extension.equals("json", ignoreCase = true)
        } ?: return emptyList()

        return jsonFiles
            .mapNotNull { file -> parseDraftOrNull(file) }
            .sortedByDescending { it.draft.createdAt }
    }

    /**
     * Deletes the metadata JSON and, if it exists, the WAV file for [id].
     * No-ops on missing files — safe to call after partial failures.
     */
    fun deleteDraft(id: String) {
        metadataFile(id).delete()
        wavFile(id).delete()
    }

    /**
     * Removes JSON metadata records whose corresponding WAV files are missing.
     *
     * Called at startup, after [RecordingCleanup.removeOrphaned] has already
     * removed crash-incomplete WAVs. Any JSON that lost its WAV in between
     * (e.g. via an external file manager) is cleaned up here.
     *
     * Returns the number of orphaned metadata records removed.
     */
    fun reconcileOrphans(): Int {
        val jsonFiles = draftsDir.listFiles { f ->
            f.isFile && f.extension.equals("json", ignoreCase = true)
        } ?: return 0

        var removed = 0
        for (file in jsonFiles) {
            val id = file.nameWithoutExtension
            if (!wavFile(id).exists()) {
                if (file.delete()) removed++
            }
        }
        return removed
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun metadataFile(id: String) = File(draftsDir, "$id.json")
    private fun wavFile(id: String) = File(draftsDir, "$id.wav")

    private fun parseDraftOrNull(file: File): DraftWithStatus? {
        return try {
            val props = Properties()
            file.inputStream().use { props.load(it) }
            val id = props.getProperty(KEY_ID) ?: return null
            val draft = Draft(
                id = id,
                name = props.getProperty(KEY_NAME) ?: "",
                createdAt = props.getProperty(KEY_CREATED_AT)?.toLongOrNull() ?: 0L,
                durationMs = props.getProperty(KEY_DURATION_MS)?.toLongOrNull() ?: 0L,
                effect = Effect.entries.firstOrNull { it.name == props.getProperty(KEY_EFFECT) }
                    ?: Effect.NONE,
                filePath = props.getProperty(KEY_FILE_PATH) ?: "",
            )
            DraftWithStatus(draft = draft, wavMissing = !wavFile(id).exists())
        } catch (_: Exception) {
            null  // Malformed file — skip; cleaned up by reconcileOrphans on next start.
        }
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_CREATED_AT = "createdAt"
        private const val KEY_DURATION_MS = "durationMs"
        private const val KEY_EFFECT = "effect"
        private const val KEY_FILE_PATH = "filePath"

        /**
         * Formats a wall-clock timestamp as a human-readable Draft name,
         * e.g. "Draft – 17 Sep, 3:13 PM".
         */
        fun defaultName(createdAt: Long): String {
            val fmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
            return "Draft \u2013 ${fmt.format(Date(createdAt))}"
        }

        /**
         * Extracts the duration from a finalized WAV file header.
         * Returns 0 if the file is unreadable or shorter than the 44-byte header.
         *
         * Formula: dataBytes / (sampleRate * bytesPerSample * channels)
         * For mono 16-bit PCM (what the native engine always writes):
         *   durationMs = (dataBytes * 1000) / (sampleRate * 2)
         */
        fun wavDurationMs(wavFile: File): Long {
            if (wavFile.length() < 44) return 0L
            return try {
                val header = ByteArray(44)
                RandomAccessFile(wavFile, "r").use { it.readFully(header) }
                val sampleRate = readU32LE(header, 24)
                val dataBytes = readU32LE(header, 40)
                if (sampleRate == 0L) 0L else (dataBytes * 1000L) / (sampleRate * 2L)
            } catch (_: Exception) {
                0L
            }
        }

        private fun readU32LE(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xFF) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }
}
