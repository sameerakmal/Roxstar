package com.roxstar.voicedraft

import java.io.File
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers all four persistence cases documented in [DraftRepository]:
 *
 * 1. Draft metadata + WAV both exist → normal, wavMissing = false
 * 2. Draft metadata exists but WAV is missing → wavMissing = true
 * 3. WAV exists but Draft metadata is missing → silently ignored
 * 4. Deleting a Draft → both files removed
 *
 * Also covers [DraftRepository.reconcileOrphans] and [DraftRepository.wavDurationMs].
 *
 * Pure java.io — no Android framework dependency, runs on the JVM host.
 */
class DraftRepositoryTest {

    private lateinit var dir: File
    private lateinit var repo: DraftRepository

    @Before
    fun setUp() {
        dir = File.createTempFile("drafts", "").apply { delete(); mkdirs() }
        repo = DraftRepository(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ── Case 1: metadata + WAV both present ──────────────────────────────────

    @Test
    fun case1_bothPresent_loadedAsNormalDraft() {
        val draft = makeDraft("id-001", Effect.ECHO)
        writeWav(File(dir, "id-001.wav"))
        repo.saveDraft(draft)

        val list = repo.loadAll()

        assertEquals(1, list.size)
        assertEquals("id-001", list[0].draft.id)
        assertFalse("wavMissing should be false when WAV is present", list[0].wavMissing)
    }

    @Test
    fun case1_allFieldsRoundTripCorrectly() {
        val draft = makeDraft("id-rtrip", Effect.REVERB, durationMs = 3500L)
        writeWav(File(dir, "id-rtrip.wav"))
        repo.saveDraft(draft)

        val loaded = repo.loadAll().first().draft
        assertEquals(draft.id, loaded.id)
        assertEquals(draft.name, loaded.name)
        assertEquals(draft.createdAt, loaded.createdAt)
        assertEquals(draft.durationMs, loaded.durationMs)
        assertEquals(draft.effect, loaded.effect)
        assertEquals(draft.filePath, loaded.filePath)
    }

    // ── Case 2: metadata present but WAV missing ─────────────────────────────

    @Test
    fun case2_metadataOnlyDraft_reportedWithWavMissing() {
        val draft = makeDraft("id-002", Effect.NONE)
        // Intentionally do NOT write the WAV file.
        repo.saveDraft(draft)

        val list = repo.loadAll()

        assertEquals(1, list.size)
        assertTrue("wavMissing should be true when WAV is absent", list[0].wavMissing)
    }

    @Test
    fun case2_missingWavDraft_canStillBeDeleted() {
        val draft = makeDraft("id-del-missing", Effect.NONE)
        repo.saveDraft(draft)
        assertTrue(File(dir, "id-del-missing.json").exists())

        repo.deleteDraft("id-del-missing")

        assertFalse(File(dir, "id-del-missing.json").exists())
    }

    // ── Case 3: WAV present but no metadata ──────────────────────────────────

    @Test
    fun case3_wavWithoutMetadata_isSilentlyIgnored() {
        // A finalized WAV with no JSON should not appear in the list.
        writeWav(File(dir, "orphan-wav.wav"))

        val list = repo.loadAll()

        assertEquals(0, list.size)
    }

    // ── Case 4: deleteDraft ───────────────────────────────────────────────────

    @Test
    fun case4_deleteRemovesBothJsonAndWav() {
        val draft = makeDraft("id-004", Effect.PITCH_SHIFT)
        writeWav(File(dir, "id-004.wav"))
        repo.saveDraft(draft)

        repo.deleteDraft("id-004")

        assertFalse(File(dir, "id-004.json").exists())
        assertFalse(File(dir, "id-004.wav").exists())
        assertEquals(0, repo.loadAll().size)
    }

    @Test
    fun case4_deleteIsSafeOnMissingFiles() {
        // Should not throw even if both files are absent.
        repo.deleteDraft("nonexistent-id")
    }

    // ── App restart ───────────────────────────────────────────────────────────

    @Test
    fun appRestart_loadAllPreservesAllDrafts() {
        val d1 = makeDraft("id-r1", Effect.ECHO, createdAt = 1000L)
        val d2 = makeDraft("id-r2", Effect.REVERB, createdAt = 2000L)
        writeWav(File(dir, "id-r1.wav"))
        writeWav(File(dir, "id-r2.wav"))
        repo.saveDraft(d1)
        repo.saveDraft(d2)

        // Simulate restart by creating a new repo pointing at the same dir.
        val reloaded = DraftRepository(dir).loadAll()

        assertEquals(2, reloaded.size)
    }

    @Test
    fun appRestart_draftsAreReturnedNewestFirst() {
        val older = makeDraft("id-old", Effect.NONE, createdAt = 1000L)
        val newer = makeDraft("id-new", Effect.ECHO, createdAt = 9000L)
        writeWav(File(dir, "id-old.wav"))
        writeWav(File(dir, "id-new.wav"))
        repo.saveDraft(older)
        repo.saveDraft(newer)

        val list = repo.loadAll()

        assertEquals("id-new", list[0].draft.id)
        assertEquals("id-old", list[1].draft.id)
    }

    // ── reconcileOrphans ─────────────────────────────────────────────────────

    @Test
    fun reconcileOrphans_removesJsonWithNoWav() {
        val draft = makeDraft("id-reconcile", Effect.NONE)
        repo.saveDraft(draft)
        // WAV was deleted externally after the Draft was saved.

        val removed = repo.reconcileOrphans()

        assertEquals(1, removed)
        assertFalse(File(dir, "id-reconcile.json").exists())
    }

    @Test
    fun reconcileOrphans_keepsJsonWithWav() {
        val draft = makeDraft("id-keep", Effect.ECHO)
        writeWav(File(dir, "id-keep.wav"))
        repo.saveDraft(draft)

        val removed = repo.reconcileOrphans()

        assertEquals(0, removed)
        assertTrue(File(dir, "id-keep.json").exists())
    }

    // ── wavDurationMs ─────────────────────────────────────────────────────────

    @Test
    fun wavDurationMs_returnsCorrectDurationForKnownWav() {
        // 16 kHz, 16-bit mono, 16000 samples = 1000 ms
        val wav = File(dir, "duration-test.wav")
        val sampleRate = 16000
        val numSamples = 16000
        writeWavWithData(wav, sampleRate, numSamples)

        val durationMs = DraftRepository.wavDurationMs(wav)
        assertEquals(1000L, durationMs)
    }

    @Test
    fun wavDurationMs_returnsZeroForTooShortFile() {
        val tiny = File(dir, "tiny.wav").apply { writeBytes(ByteArray(10)) }
        assertEquals(0L, DraftRepository.wavDurationMs(tiny))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun makeDraft(
        id: String,
        effect: Effect,
        createdAt: Long = System.currentTimeMillis(),
        durationMs: Long = 1000L,
    ) = Draft(
        id = id,
        name = "Test Draft $id",
        createdAt = createdAt,
        durationMs = durationMs,
        effect = effect,
        filePath = File(dir, "$id.wav").absolutePath,
    )

    /** Writes a minimal but internally consistent 44-byte WAV header with no audio data. */
    private fun writeWav(file: File) = writeWavWithData(file, sampleRate = 16000, numSamples = 0)

    private fun writeWavWithData(file: File, sampleRate: Int, numSamples: Int) {
        val dataBytes = numSamples * 2  // 16-bit = 2 bytes per sample, mono
        val riffSize = 36 + dataBytes
        val header = ByteArray(44)
        "RIFF".toByteArray().copyInto(header, 0)
        putU32LE(header, 4, riffSize)
        "WAVE".toByteArray().copyInto(header, 8)
        "fmt ".toByteArray().copyInto(header, 12)
        putU32LE(header, 16, 16)            // chunk size
        header[20] = 1; header[22] = 1     // PCM, mono
        putU32LE(header, 24, sampleRate)
        putU32LE(header, 28, sampleRate * 2)
        header[32] = 2; header[34] = 16    // block align, bits per sample
        "data".toByteArray().copyInto(header, 36)
        putU32LE(header, 40, dataBytes)
        RandomAccessFile(file, "rw").use { raf ->
            raf.write(header)
            if (dataBytes > 0) raf.write(ByteArray(dataBytes))
        }
    }

    private fun putU32LE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
