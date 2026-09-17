#ifndef ROXSTAR_PLAYBACK_SESSION_H
#define ROXSTAR_PLAYBACK_SESSION_H

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>

#include "Status.h"
#include "src/WavReader.h"

namespace roxstar {

// Mirrored by PlaybackState in AudioEngine.kt — keep in sync.
enum class PlaybackState : int32_t {
    Idle    = 0,
    Playing = 1,
    Paused  = 2,
    Stopped = 3,
    Error   = 4,
};

// Layout of the snapshot array returned by the coarse playback config call.
// Mirrored by the IDX_* constants in AudioEngine.kt. Scoped (enum class),
// unlike AudioEngine.h's ConfigIndex, so its kIdx* names don't collide with
// ConfigIndex's identically-named enumerators in this same namespace.
enum class PlaybackConfigIndex : int32_t {
    kIdxState = 0,
    kIdxSampleRate,
    kIdxChannelCount,
    kIdxFrameCount,
    kIdxFramePosition,
    kIdxLastResult,
    kIdxCount,
};

/**
 * Owns one Oboe OUTPUT stream and the fully-loaded WAV samples it plays.
 *
 * Threading:
 *  - prepare()/play()/pause()/stop()/close() run on the JNI thread and are
 *    serialised by mLock — the same shape as AudioEngine's own input-stream
 *    lifecycle. prepare() does blocking file I/O (WavReader::load); callers
 *    must invoke it off the main thread.
 *  - onAudioReady runs on the real-time playback thread: it only reads
 *    already-loaded samples by index and writes them into the output buffer
 *    in whatever format/channel count Oboe actually granted. No allocation,
 *    locking, logging, file I/O or JNI.
 *
 * The whole WAV is decoded into memory up front (see WavReader) — these are
 * short voice recordings, so streaming playback would be unneeded complexity.
 */
class PlaybackSession : public oboe::AudioStreamDataCallback,
                        public oboe::AudioStreamErrorCallback {
public:
    PlaybackSession() = default;
    ~PlaybackSession() override;

    PlaybackSession(const PlaybackSession &) = delete;
    PlaybackSession &operator=(const PlaybackSession &) = delete;

    /**
     * JNI thread. Loads `path` and opens the output stream, ready for
     * play(). Safe to call again with a different path at any time — it
     * tears down any previous stream/loaded audio first.
     */
    Status prepare(const std::string &path);

    Status play();
    Status pause();
    /** Stops and rewinds to the start; a subsequent play() starts over. */
    Status stop();
    /** Stops and releases the stream and the loaded WAV. Safe to call repeatedly. */
    void close();

    PlaybackState state() const {
        return static_cast<PlaybackState>(mState.load(std::memory_order_relaxed));
    }

    /** Fills `out` with kIdxCount values describing playback. */
    void snapshot(int64_t *out, int32_t count) const;

    const char *lastResultText() const;

    // --- oboe::AudioStreamDataCallback (REAL-TIME THREAD) ---
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream,
                                          void *audioData,
                                          int32_t numFrames) override;

    // --- oboe::AudioStreamErrorCallback (Oboe error thread) ---
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result result) override;

private:
    void releaseStreamLocked();

    mutable std::mutex mLock;
    std::shared_ptr<oboe::AudioStream> mStream;
    WavReader mReader;

    std::atomic<int32_t> mState{static_cast<int32_t>(PlaybackState::Idle)};
    std::atomic<int32_t> mLastResult{static_cast<int32_t>(oboe::Result::OK)};
    std::atomic<bool> mDisconnected{false};

    // Set once in prepare(), before the stream opens; read-only afterward.
    int32_t mSampleRate = 0;
    int64_t mFrameCount = 0;

    // Advanced only by the audio callback; reset to 0 by stop()/prepare() on
    // the JNI thread, which only happens while the stream is not running.
    std::atomic<int64_t> mFramePosition{0};

    // Published under mLock before the stream can start; read by the callback.
    std::atomic<int32_t> mCallbackFormat{static_cast<int32_t>(oboe::AudioFormat::Invalid)};
    std::atomic<int32_t> mCallbackChannels{0};
};

}  // namespace roxstar

#endif  // ROXSTAR_PLAYBACK_SESSION_H
