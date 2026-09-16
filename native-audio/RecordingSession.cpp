#include "RecordingSession.h"

#include <chrono>

namespace roxstar {

namespace {
constexpr double kRingBufferSeconds = 2.0;
constexpr size_t kWriterChunkFrames = 1024;
constexpr auto kWriterIdleSleep = std::chrono::milliseconds(3);
}  // namespace

RecordingSession::~RecordingSession() {
    // Safety net: if the owner (AudioEngine) is destroyed without an explicit
    // stop/cancel, do not leak the writer thread or leave a half-written file.
    if (mWriterThread.joinable()) {
        cancelAndDiscard();
    }
}

Status RecordingSession::start(const std::string &path, int32_t sampleRate) {
    if (mActive.load(std::memory_order_acquire)) {
        return Status::AlreadyRecording;
    }
    if (sampleRate <= 0) {
        return Status::FileError;
    }

    if (!mWriter.open(path, static_cast<uint32_t>(sampleRate), /*channelCount=*/1)) {
        mState.store(static_cast<int32_t>(RecordingState::Error), std::memory_order_relaxed);
        return Status::FileError;
    }

    const size_t capacitySamples =
        static_cast<size_t>(sampleRate * kRingBufferSeconds) + kWriterChunkFrames;
    mRingBuffer = std::make_unique<RingBuffer>(capacitySamples);

    mFramesCaptured.store(0, std::memory_order_relaxed);
    mOverrunFrames.store(0, std::memory_order_relaxed);
    mStopRequested.store(false, std::memory_order_relaxed);
    mLastPath = path;

    // Publishes mRingBuffer and the freshly-opened mWriter to the audio
    // thread: the release store here pairs with the acquire load of mActive
    // in pushSamples().
    mState.store(static_cast<int32_t>(RecordingState::Recording), std::memory_order_relaxed);
    mActive.store(true, std::memory_order_release);

    mWriterThread = std::thread(&RecordingSession::writerLoop, this);
    return Status::Ok;
}

void RecordingSession::pushSamples(const float *mono, int32_t numFrames) {
    // REAL-TIME THREAD. No allocation, locking, logging or file I/O.
    if (!mActive.load(std::memory_order_acquire) || mRingBuffer == nullptr) {
        return;
    }

    const size_t written = mRingBuffer->push(mono, static_cast<size_t>(numFrames));
    mFramesCaptured.fetch_add(static_cast<int64_t>(written), std::memory_order_relaxed);

    if (written < static_cast<size_t>(numFrames)) {
        const int32_t dropped = numFrames - static_cast<int32_t>(written);
        mOverrunFrames.fetch_add(dropped, std::memory_order_relaxed);
    }
}

void RecordingSession::writerLoop() {
    float chunk[kWriterChunkFrames];
    while (true) {
        const size_t n = mRingBuffer->pop(chunk, kWriterChunkFrames);
        if (n > 0) {
            mWriter.appendFloatSamples(chunk, n);
            continue;
        }
        // Ring buffer is empty. Only exit once the producer has been told to
        // stop — this is the "drain remaining samples" step: any samples
        // pushed before mActive flipped false are still popped here first.
        if (mStopRequested.load(std::memory_order_acquire)) {
            break;
        }
        std::this_thread::sleep_for(kWriterIdleSleep);
    }
}

void RecordingSession::joinAndStopProducer() {
    mActive.store(false, std::memory_order_release);   // step 2a: stop accepting new samples
    mStopRequested.store(true, std::memory_order_release);  // step 2b: let the writer exit once drained
    if (mWriterThread.joinable()) {
        mWriterThread.join();  // steps 3+4: drain happens inside writerLoop, then join
    }
}

Status RecordingSession::stopAndFinalize() {
    if (!mActive.load(std::memory_order_acquire)) {
        return Status::NotRecording;
    }

    mState.store(static_cast<int32_t>(RecordingState::Finalizing), std::memory_order_relaxed);
    joinAndStopProducer();

    // Defensive final drain: closes the narrow window where an in-flight audio
    // callback observed a stale mActive==true and pushed one last chunk after
    // the writer thread's final (empty) pop but before it observed
    // mStopRequested. By this point no more callbacks can arrive (the Oboe
    // stream was already told to stop before this method was entered), so a
    // single extra pop pass is sufficient.
    float chunk[kWriterChunkFrames];
    size_t drained;
    while ((drained = mRingBuffer->pop(chunk, kWriterChunkFrames)) > 0) {
        mWriter.appendFloatSamples(chunk, drained);
    }

    const bool ok = mWriter.finalize();  // steps 5+6: patch header, close file
    mState.store(
        static_cast<int32_t>(ok ? RecordingState::Idle : RecordingState::Error),
        std::memory_order_relaxed);
    return ok ? Status::Ok : Status::FileError;
}

void RecordingSession::cancelAndDiscard() {
    if (mActive.load(std::memory_order_acquire)) {
        joinAndStopProducer();
    } else if (mWriterThread.joinable()) {
        mStopRequested.store(true, std::memory_order_release);
        mWriterThread.join();
    }
    mWriter.abortAndDelete();
    mState.store(static_cast<int32_t>(RecordingState::Idle), std::memory_order_relaxed);
}

}  // namespace roxstar
