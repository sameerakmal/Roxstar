#ifndef ROXSTAR_RECORDING_SESSION_H
#define ROXSTAR_RECORDING_SESSION_H

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <thread>

#include "RingBuffer.h"
#include "Status.h"
#include "WavWriter.h"
#include "src/effects/IEffect.h"

namespace roxstar {

// Mirrored by RecordingState in AudioEngine.kt — keep in sync.
enum class RecordingState : int32_t {
    Idle       = 0,
    Recording  = 1,
    Finalizing = 2,
    Error      = 3,
};

/**
 * Owns the ring buffer, the WAV file and the writer thread for one recording.
 *
 * Threading:
 *  - start()/stopAndFinalize()/cancelAndDiscard() run on the JNI (UI/IO)
 *    thread. stopAndFinalize() blocks until the writer thread has drained the
 *    ring buffer, so callers should invoke it off the main thread.
 *  - pushSamples() runs on the real-time audio callback thread: it only
 *    downmixes into a fixed-size stack scratch buffer and calls
 *    RingBuffer::push(), which itself never allocates, locks or blocks.
 *  - The writer thread loop is the sole consumer of the ring buffer and the
 *    sole owner of the WavFileWriter; it performs all file I/O.
 */
class RecordingSession {
public:
    RecordingSession() = default;
    ~RecordingSession();

    RecordingSession(const RecordingSession &) = delete;
    RecordingSession &operator=(const RecordingSession &) = delete;

    /**
     * JNI thread. Sets the effect applied for the *next* start() call. Fixed
     * for that recording session — call this again before starting the next
     * one to change it. Effect construction and buffer sizing happen inside
     * start(), once the actual sample rate is known.
     */
    void setEffect(effects::EffectType type) { mEffectType = type; }

    /**
     * JNI thread. Opens the file, sizes the ring buffer, builds and prepares
     * the selected effect, and starts the writer thread. Output is always
     * mono 16-bit PCM at `sampleRate` — the caller downmixes in
     * pushSamples(), so this class never needs a channel count.
     */
    Status start(const std::string &path, int32_t sampleRate);

    /** JNI thread. Signals, drains, joins, finalizes the header and closes the file. */
    Status stopAndFinalize();

    /** JNI thread. Signals, drains, joins, then deletes the (incomplete) file. */
    void cancelAndDiscard();

    /**
     * AUDIO CALLBACK THREAD ONLY. No allocation, locking, logging or file I/O.
     * Applies the effect selected for this session in place, if any — a no-op
     * when the effect is None or when no recording is active. The object was
     * fully constructed and prepared on the JNI thread inside start(), before
     * mActive was published, so this only ever dereferences a ready effect.
     */
    void applyEffect(float *mono, int32_t numFrames);

    /**
     * AUDIO CALLBACK THREAD ONLY. No allocation, locking, logging or file I/O.
     * `mono` must already be downmixed to one channel by the caller (this
     * class stays free of any Oboe/format dependency so it is host-testable).
     */
    void pushSamples(const float *mono, int32_t numFrames);

    bool isRecording() const {
        return mActive.load(std::memory_order_acquire);
    }

    RecordingState state() const {
        return static_cast<RecordingState>(mState.load(std::memory_order_relaxed));
    }

    int64_t framesCaptured() const { return mFramesCaptured.load(std::memory_order_relaxed); }
    int32_t overrunFrames() const { return mOverrunFrames.load(std::memory_order_relaxed); }
    std::string lastPath() const { return mLastPath; }

private:
    void writerLoop();
    void joinAndStopProducer();

    std::atomic<bool> mActive{false};       // gates the audio callback producer
    std::atomic<bool> mStopRequested{false};  // tells the writer loop to exit after draining
    std::atomic<int32_t> mState{static_cast<int32_t>(RecordingState::Idle)};

    std::atomic<int64_t> mFramesCaptured{0};
    std::atomic<int32_t> mOverrunFrames{0};

    // Published (release) before mActive is set, so the audio thread's
    // acquire load of mActive makes these safe to read without their own
    // synchronization. mEffect may be null (EffectType::None).
    std::unique_ptr<RingBuffer> mRingBuffer;
    std::unique_ptr<effects::IEffect> mEffect;

    effects::EffectType mEffectType = effects::EffectType::None;  // JNI thread only

    std::thread mWriterThread;
    WavFileWriter mWriter;
    std::string mLastPath;  // touched only by the JNI thread
};

}  // namespace roxstar

#endif  // ROXSTAR_RECORDING_SESSION_H
