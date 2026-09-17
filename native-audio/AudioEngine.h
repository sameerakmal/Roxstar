#ifndef ROXSTAR_AUDIO_ENGINE_H
#define ROXSTAR_AUDIO_ENGINE_H

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>

#include "PlaybackSession.h"
#include "RecordingSession.h"
#include "Status.h"
#include "src/effects/IEffect.h"

namespace roxstar {

// Lifecycle states. Mirrored by EngineState in AudioEngine.kt — keep in sync.
enum class EngineState : int32_t {
    Uninitialized = 0,
    Open          = 1,
    Started       = 2,
    Stopped       = 3,
    Closed        = 4,
    Disconnected  = 5,  // Oboe reported a stream disconnect and closed the stream itself.
};

// Layout of the snapshot array returned by the single coarse-grained config call.
// Mirrored by the IDX_* constants in AudioEngine.kt.
enum ConfigIndex : int32_t {
    kIdxState = 0,
    kIdxSampleRate,
    kIdxChannelCount,
    kIdxFormat,
    kIdxSharingMode,
    kIdxPerformanceMode,
    kIdxFramesPerBurst,
    kIdxBufferSizeInFrames,
    kIdxBufferCapacityInFrames,
    kIdxDeviceId,
    kIdxInputPreset,
    kIdxAudioApi,
    kIdxFramesRead,
    kIdxPeakLevelMicros,
    kIdxLastResult,
    kIdxRecordingState,
    kIdxRecordingFramesCaptured,
    kIdxRecordingOverrunFrames,
    kIdxCount,
};

/**
 * Minimal Oboe input-stream owner.
 *
 * Lifecycle: Uninitialized -> Open -> Started -> Stopped -> Closed.
 * Stopped may be started again; Closed may be opened again.
 *
 * Threading:
 *  - open/start/stop/close/snapshot are called from the JNI (UI) thread and are
 *    serialised by mLock. mStream is only ever mutated there.
 *  - onAudioReady runs on the real-time audio thread. It does no allocation,
 *    locking, logging, file I/O or JNI — only relaxed atomic stores.
 *  - onErrorAfterClose runs on an Oboe-internal thread. It deliberately does NOT
 *    take mLock (that would risk deadlocking against a concurrent close()); it
 *    only publishes flags. The next lifecycle call performs the actual cleanup.
 *
 * Phase 2 scope: open/start/stop/close and reporting the real stream config.
 * Phase 3 adds recording: onAudioReady() routes captured audio into a
 * RecordingSession (ring buffer + WAV writer thread) whenever recording is
 * active. Phase 5 adds effects: onAudioReady() applies the selected effect
 * (see RecordingSession::applyEffect) to the mono buffer before it reaches
 * the ring buffer, so the saved WAV contains the processed signal. Phase 6
 * adds playback: a PlaybackSession owns its own independent Oboe OUTPUT
 * stream (see below) — it does not touch mStream or onAudioReady at all.
 */
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    AudioEngine() = default;
    ~AudioEngine() override;

    AudioEngine(const AudioEngine &) = delete;
    AudioEngine &operator=(const AudioEngine &) = delete;

    Status open();
    Status start();
    Status stop();
    Status close();

    /**
     * Selects the effect applied to the *next* recording. Fixed for that
     * session once startRecording() begins — call this again before the
     * next one to change it. Rejected with InvalidState while a recording is
     * already in progress (changing effects mid-recording is not supported).
     */
    Status setEffect(effects::EffectType type);

    /**
     * Auto-opens/starts the stream if needed, then starts recording to
     * `path` (already an absolute, unique path chosen by the caller).
     * Fails with AlreadyRecording if a recording is already in progress.
     */
    Status startRecording(const std::string &path);

    /**
     * Stops the Oboe stream, then signals, drains and joins the writer
     * thread, patches the WAV header and closes the file — in that order.
     * Blocking; call off the UI thread.
     */
    Status stopRecording();

    /**
     * Stops any in-progress recording and deletes the partial WAV file.
     * Equivalent to stopRecording() but discards instead of finalizing.
     * Blocking; call off the UI thread.
     */
    void cancelRecording();

    std::string lastRecordingPath() const { return mRecording.lastPath(); }

    /**
     * Loads `path` and opens the playback output stream. Blocking (file
     * I/O); call off the UI thread. Safe to call again with a different
     * path at any time, including mid-playback.
     */
    Status preparePlayback(const std::string &path) { return mPlayback.prepare(path); }
    Status startPlayback() { return mPlayback.play(); }
    Status pausePlayback() { return mPlayback.pause(); }
    Status stopPlayback() { return mPlayback.stop(); }

    /** Fills `out` with PlaybackConfigIndex::kIdxCount values describing playback. */
    void playbackSnapshot(int64_t *out, int32_t count) const { mPlayback.snapshot(out, count); }

    const char *lastPlaybackResultText() const { return mPlayback.lastResultText(); }

    /** Fills `out` with kIdxCount values describing the live stream. */
    void snapshot(int64_t *out, int32_t count);

    /** Oboe's own text for the last oboe::Result observed. */
    const char *lastResultText() const;

    // --- oboe::AudioStreamDataCallback (REAL-TIME THREAD) ---
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream,
                                          void *audioData,
                                          int32_t numFrames) override;

    // --- oboe::AudioStreamErrorCallback (Oboe error thread) ---
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result result) override;

private:
    oboe::Result openWithSharingMode(oboe::SharingMode sharingMode);
    /** Opens with the Exclusive->Shared fallback. Caller must hold mLock. */
    Status openStreamLocked();
    void releaseStreamLocked();

    mutable std::mutex mLock;
    std::shared_ptr<oboe::AudioStream> mStream;

    std::atomic<int32_t> mState{static_cast<int32_t>(EngineState::Uninitialized)};
    std::atomic<int32_t> mLastResult{static_cast<int32_t>(oboe::Result::OK)};
    std::atomic<bool> mDisconnected{false};

    // Written only by the audio callback, read by the UI thread.
    std::atomic<int64_t> mFramesRead{0};
    std::atomic<int32_t> mPeakLevelMicros{0};

    // Published under mLock before the stream can start; read by the audio callback.
    std::atomic<int32_t> mCallbackFormat{static_cast<int32_t>(oboe::AudioFormat::Invalid)};
    std::atomic<int32_t> mCallbackChannels{0};

    // Owns its own synchronization; pushSamples() is real-time safe. Mutated
    // (start/stop) only from the JNI thread, same as mStream.
    RecordingSession mRecording;

    // Owns its own Oboe output stream and synchronization, entirely
    // independent of mStream/mRecording above.
    PlaybackSession mPlayback;
};

}  // namespace roxstar

#endif  // ROXSTAR_AUDIO_ENGINE_H
