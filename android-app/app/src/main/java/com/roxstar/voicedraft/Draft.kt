package com.roxstar.voicedraft

/**
 * Immutable metadata record for a locally stored voice draft.
 *
 * One [Draft] corresponds to exactly one WAV file at [filePath].
 * The file is written by the native audio engine; this record is
 * written by [DraftRepository] immediately after a successful stop.
 */
data class Draft(
    /** Stable UUID, also used as the filename stem (both `<id>.json` and `<id>.wav`). */
    val id: String,
    /** Human-readable name shown in the Draft list (defaults to the recording timestamp). */
    val name: String,
    /** Wall-clock milliseconds since epoch when the Draft was saved. */
    val createdAt: Long,
    /** Duration of the audio, in milliseconds, derived from the WAV header. */
    val durationMs: Long,
    /** Effect that was applied during recording. */
    val effect: Effect,
    /** Absolute path to the WAV file on this device. */
    val filePath: String,
)

/**
 * A [Draft] paired with a flag indicating whether its WAV file is present.
 *
 * A missing WAV can occur when:
 * - The app was force-stopped between saving the JSON metadata and writing the WAV.
 * - The user deleted the file externally (e.g. via a file manager).
 *
 * The UI shows such drafts with a "file missing" indicator; they can still be
 * deleted (which removes just the metadata record).
 */
data class DraftWithStatus(
    val draft: Draft,
    val wavMissing: Boolean,
)
