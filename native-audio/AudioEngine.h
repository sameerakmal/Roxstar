#ifndef ROXSTAR_AUDIO_ENGINE_H
#define ROXSTAR_AUDIO_ENGINE_H

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>

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

// Status codes returned across JNI. Mirrored by AudioStatus in AudioEngine.kt.
enum class Status : int32_t {
    Ok           =  0,
    InvalidState = -1,
    OpenFailed   = -2,
    StartFailed  = -3,
    StopFailed   = -4,
    CloseFailed  = -5,
    NoEngine     = -6,
    Disconnected = -7,
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
 * Captured audio is intentionally discarded — the ring buffer, WAV writing and
 * effects arrive in later phases.
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
};

}  // namespace roxstar

#endif  // ROXSTAR_AUDIO_ENGINE_H
