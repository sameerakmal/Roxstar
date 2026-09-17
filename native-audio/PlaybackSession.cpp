#include "PlaybackSession.h"

#include "src/PlaybackBuffer.h"

namespace roxstar {

namespace {
constexpr float kInt16Scale = 32767.0f;
constexpr int32_t kScratchFrames = 2048;  // floats; matches AudioEngine's callback scratch sizing
}  // namespace

PlaybackSession::~PlaybackSession() {
    close();
}

Status PlaybackSession::prepare(const std::string &path) {
    std::lock_guard<std::mutex> guard(mLock);

    releaseStreamLocked();

    if (!mReader.load(path)) {
        mState.store(static_cast<int32_t>(PlaybackState::Error), std::memory_order_relaxed);
        return Status::FileError;
    }

    mSampleRate = mReader.sampleRate();
    mFrameCount = mReader.frameCount();
    mFramePosition.store(0, std::memory_order_relaxed);

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(mSampleRate)
        // Lets Oboe/AAudio resample internally when the device's fixed native
        // rate differs from the WAV's rate, rather than us hand-rolling one.
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    const oboe::Result result = builder.openStream(mStream);
    mLastResult.store(static_cast<int32_t>(result), std::memory_order_relaxed);
    if (result != oboe::Result::OK || mStream == nullptr) {
        mStream.reset();
        mState.store(static_cast<int32_t>(PlaybackState::Error), std::memory_order_relaxed);
        return Status::OpenFailed;
    }

    mCallbackFormat.store(static_cast<int32_t>(mStream->getFormat()), std::memory_order_relaxed);
    mCallbackChannels.store(mStream->getChannelCount(), std::memory_order_relaxed);
    mDisconnected.store(false, std::memory_order_relaxed);
    mState.store(static_cast<int32_t>(PlaybackState::Stopped), std::memory_order_relaxed);
    return Status::Ok;
}

Status PlaybackSession::play() {
    std::lock_guard<std::mutex> guard(mLock);

    if (mDisconnected.load(std::memory_order_relaxed)) return Status::Disconnected;
    if (mStream == nullptr) return Status::InvalidState;

    const int32_t state = mState.load(std::memory_order_relaxed);
    if (state != static_cast<int32_t>(PlaybackState::Stopped) &&
        state != static_cast<int32_t>(PlaybackState::Paused)) {
        return Status::InvalidState;
    }

    const oboe::Result result = mStream->requestStart();
    mLastResult.store(static_cast<int32_t>(result), std::memory_order_relaxed);
    if (result != oboe::Result::OK) {
        return Status::StartFailed;
    }

    mState.store(static_cast<int32_t>(PlaybackState::Playing), std::memory_order_relaxed);
    return Status::Ok;
}

Status PlaybackSession::pause() {
    std::lock_guard<std::mutex> guard(mLock);

    if (mDisconnected.load(std::memory_order_relaxed)) return Status::Disconnected;
    if (mStream == nullptr) return Status::InvalidState;
    if (mState.load(std::memory_order_relaxed) != static_cast<int32_t>(PlaybackState::Playing)) {
        return Status::InvalidState;
    }

    const oboe::Result result = mStream->requestPause();
    mLastResult.store(static_cast<int32_t>(result), std::memory_order_relaxed);
    if (result != oboe::Result::OK) {
        return Status::PauseFailed;
    }

    mState.store(static_cast<int32_t>(PlaybackState::Paused), std::memory_order_relaxed);
    return Status::Ok;
}

Status PlaybackSession::stop() {
    std::lock_guard<std::mutex> guard(mLock);

    if (mDisconnected.load(std::memory_order_relaxed)) return Status::Disconnected;
    if (mStream == nullptr) return Status::InvalidState;

    const int32_t state = mState.load(std::memory_order_relaxed);
    if (state != static_cast<int32_t>(PlaybackState::Playing) &&
        state != static_cast<int32_t>(PlaybackState::Paused)) {
        return Status::InvalidState;
    }

    const oboe::Result result = mStream->requestStop();
    mLastResult.store(static_cast<int32_t>(result), std::memory_order_relaxed);
    mFramePosition.store(0, std::memory_order_relaxed);
    if (result != oboe::Result::OK) {
        mState.store(static_cast<int32_t>(PlaybackState::Error), std::memory_order_relaxed);
        return Status::StopFailed;
    }

    mState.store(static_cast<int32_t>(PlaybackState::Stopped), std::memory_order_relaxed);
    return Status::Ok;
}

void PlaybackSession::close() {
    std::lock_guard<std::mutex> guard(mLock);

    releaseStreamLocked();
    mReader.reset();
    mSampleRate = 0;
    mFrameCount = 0;
    mFramePosition.store(0, std::memory_order_relaxed);
    mState.store(static_cast<int32_t>(PlaybackState::Idle), std::memory_order_relaxed);
}

void PlaybackSession::releaseStreamLocked() {
    if (mStream != nullptr) {
        mStream->stop();   // best-effort; we're tearing down regardless of the result
        mStream->close();
        mStream.reset();
    }
    mDisconnected.store(false, std::memory_order_relaxed);
    mCallbackFormat.store(static_cast<int32_t>(oboe::AudioFormat::Invalid), std::memory_order_relaxed);
    mCallbackChannels.store(0, std::memory_order_relaxed);
}

void PlaybackSession::snapshot(int64_t *out, int32_t count) const {
    using Idx = PlaybackConfigIndex;
    if (out == nullptr || count < static_cast<int32_t>(Idx::kIdxCount)) return;
    for (int32_t i = 0; i < count; ++i) out[i] = 0;

    out[static_cast<size_t>(Idx::kIdxState)] = mState.load(std::memory_order_relaxed);
    out[static_cast<size_t>(Idx::kIdxSampleRate)] = mSampleRate;
    out[static_cast<size_t>(Idx::kIdxChannelCount)] = mCallbackChannels.load(std::memory_order_relaxed);
    out[static_cast<size_t>(Idx::kIdxFrameCount)] = mFrameCount;
    out[static_cast<size_t>(Idx::kIdxFramePosition)] = mFramePosition.load(std::memory_order_relaxed);
    out[static_cast<size_t>(Idx::kIdxLastResult)] = mLastResult.load(std::memory_order_relaxed);
}

const char *PlaybackSession::lastResultText() const {
    const auto result = static_cast<oboe::Result>(mLastResult.load(std::memory_order_relaxed));
    return oboe::convertToText(result);
}

oboe::DataCallbackResult PlaybackSession::onAudioReady(oboe::AudioStream * /*stream*/,
                                                        void *audioData,
                                                        int32_t numFrames) {
    // REAL-TIME THREAD. No allocation, locking, logging, file I/O or JNI here.
    // mReader's buffer was fully loaded on the JNI thread before this stream
    // was ever opened, so reading it here is just indexing an already-sized
    // vector — never reallocated for the life of this stream. The actual
    // copy/silence-fill/EOF logic is fillPlaybackBuffer() (src/PlaybackBuffer),
    // a pure function so it's host-testable independent of Oboe.
    const int32_t channels = mCallbackChannels.load(std::memory_order_relaxed);
    const bool isFloat = mCallbackFormat.load(std::memory_order_relaxed) ==
                         static_cast<int32_t>(oboe::AudioFormat::Float);
    const float *samples = mReader.samples();
    const int64_t total = mFrameCount;

    int64_t pos = mFramePosition.load(std::memory_order_relaxed);
    int32_t realFrames;

    if (isFloat) {
        // The common case: Oboe granted the format we asked for, so we can
        // write straight into audioData with no intermediate buffer.
        realFrames = fillPlaybackBuffer(static_cast<float *>(audioData), numFrames, channels,
                                        samples, total, &pos);
    } else {
        // Oboe granted I16 instead. Convert through a fixed-size stack
        // scratch buffer, in bounded chunks so it stays real-time safe
        // regardless of channel count or callback size.
        auto *i16Out = static_cast<int16_t *>(audioData);
        float scratch[kScratchFrames];
        const int32_t chunkCapacity = channels > 0 ? kScratchFrames / channels : 0;

        realFrames = 0;
        int32_t framesDone = 0;
        while (framesDone < numFrames && chunkCapacity > 0) {
            const int32_t chunk =
                (numFrames - framesDone) < chunkCapacity ? (numFrames - framesDone) : chunkCapacity;
            const int32_t chunkReal = fillPlaybackBuffer(scratch, chunk, channels, samples, total, &pos);
            for (int32_t i = 0; i < chunk * channels; ++i) {
                i16Out[framesDone * channels + i] = static_cast<int16_t>(scratch[i] * kInt16Scale);
            }
            realFrames += chunkReal;
            framesDone += chunk;
            if (chunkReal < chunk) break;  // hit the end of the loaded audio mid-chunk
        }
        // Any frames this callback didn't reach (only possible if playback
        // ended mid-chunk above) must still be silence in the output.
        for (int32_t f = framesDone; f < numFrames; ++f) {
            for (int32_t c = 0; c < channels; ++c) {
                i16Out[f * channels + c] = 0;
            }
        }
    }

    mFramePosition.store(pos, std::memory_order_relaxed);

    if (realFrames < numFrames) {
        // Reached the end of the loaded audio within this callback. Rewind
        // for the next play() and tell Oboe to stop the stream for us —
        // calling requestStop() from inside the callback itself is not safe.
        mFramePosition.store(0, std::memory_order_relaxed);
        mState.store(static_cast<int32_t>(PlaybackState::Stopped), std::memory_order_relaxed);
        return oboe::DataCallbackResult::Stop;
    }

    return oboe::DataCallbackResult::Continue;
}

void PlaybackSession::onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result result) {
    // Oboe-internal error thread — e.g. the output route changed (headphones
    // unplugged) and Oboe already closed the stream. Only publish flags here;
    // the next lifecycle call releases the stream pointer.
    mLastResult.store(static_cast<int32_t>(result), std::memory_order_relaxed);
    mDisconnected.store(true, std::memory_order_relaxed);
    mState.store(static_cast<int32_t>(PlaybackState::Error), std::memory_order_relaxed);
}

}  // namespace roxstar
