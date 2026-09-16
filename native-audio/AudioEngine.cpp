#include "AudioEngine.h"

namespace roxstar {

namespace {
constexpr float kInt16Scale = 32768.0f;
constexpr float kPeakScale = 1000000.0f;
}  // namespace

AudioEngine::~AudioEngine() {
    // The stream holds a raw pointer to this object as its callback target, so it
    // must be closed before we finish destructing. Oboe::close() waits for any
    // in-flight callback to return.
    close();
}

oboe::Result AudioEngine::openWithSharingMode(oboe::SharingMode sharingMode) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
        ->setSharingMode(sharingMode)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setInputPreset(oboe::InputPreset::VoiceRecognition)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    // Format/channel conversion is deliberately left off so the values reported
    // back to the UI are what the device actually granted, not Oboe's conversion.
    return builder.openStream(mStream);
}

Status AudioEngine::open() {
    std::lock_guard<std::mutex> guard(mLock);

    if (mDisconnected.load(std::memory_order_relaxed)) {
        // Oboe already closed the underlying stream; drop our handle to it.
        releaseStreamLocked();
    }
    if (mStream != nullptr) {
        return Status::InvalidState;
    }

    // Exclusive gives the lowest latency but is not always grantable; fall back
    // to Shared. Note AAudio may also silently downgrade, which is why the
    // granted mode is read back in snapshot().
    oboe::Result result = openWithSharingMode(oboe::SharingMode::Exclusive);
    if (result != oboe::Result::OK) {
        mStream.reset();
        result = openWithSharingMode(oboe::SharingMode::Shared);
    }

    mLastResult.store(static_cast<int32_t>(result), std::memory_order_relaxed);
    if (result != oboe::Result::OK || mStream == nullptr) {
        mStream.reset();
        mState.store(static_cast<int32_t>(EngineState::Uninitialized), std::memory_order_relaxed);
        return Status::OpenFailed;
    }

    mCallbackFormat.store(static_cast<int32_t>(mStream->getFormat()), std::memory_order_relaxed);
    mCallbackChannels.store(mStream->getChannelCount(), std::memory_order_relaxed);
    mFramesRead.store(0, std::memory_order_relaxed);
    mPeakLevelMicros.store(0, std::memory_order_relaxed);
    mState.store(static_cast<int32_t>(EngineState::Open), std::memory_order_relaxed);
    return Status::Ok;
}

Status AudioEngine::start() {
    std::lock_guard<std::mutex> guard(mLock);

    if (mDisconnected.load(std::memory_order_relaxed)) {
        return Status::Disconnected;
    }
    if (mStream == nullptr) {
        return Status::InvalidState;
    }

    const int32_t state = mState.load(std::memory_order_relaxed);
    if (state != static_cast<int32_t>(EngineState::Open) &&
        state != static_cast<int32_t>(EngineState::Stopped)) {
        return Status::InvalidState;
    }

    const oboe::Result result = mStream->requestStart();
    mLastResult.store(static_cast<int32_t>(result), std::memory_order_relaxed);
    if (result != oboe::Result::OK) {
        return Status::StartFailed;
    }

    mState.store(static_cast<int32_t>(EngineState::Started), std::memory_order_relaxed);
    return Status::Ok;
}

Status AudioEngine::stop() {
    std::lock_guard<std::mutex> guard(mLock);

    if (mDisconnected.load(std::memory_order_relaxed)) {
        return Status::Disconnected;
    }
    if (mStream == nullptr) {
        return Status::InvalidState;
    }
    if (mState.load(std::memory_order_relaxed) != static_cast<int32_t>(EngineState::Started)) {
        return Status::InvalidState;
    }

    const oboe::Result result = mStream->requestStop();
    mLastResult.store(static_cast<int32_t>(result), std::memory_order_relaxed);
    if (result != oboe::Result::OK) {
        return Status::StopFailed;
    }

    mState.store(static_cast<int32_t>(EngineState::Stopped), std::memory_order_relaxed);
    return Status::Ok;
}

Status AudioEngine::close() {
    std::lock_guard<std::mutex> guard(mLock);

    if (mStream == nullptr) {
        // Never opened, or already closed.
        releaseStreamLocked();
        mState.store(static_cast<int32_t>(EngineState::Closed), std::memory_order_relaxed);
        return Status::Ok;
    }

    // close() stops the stream first if it is still running.
    const oboe::Result result = mStream->close();
    mLastResult.store(static_cast<int32_t>(result), std::memory_order_relaxed);
    releaseStreamLocked();
    mState.store(static_cast<int32_t>(EngineState::Closed), std::memory_order_relaxed);

    // ErrorClosed simply means Oboe had already closed it (the disconnect path).
    if (result != oboe::Result::OK && result != oboe::Result::ErrorClosed) {
        return Status::CloseFailed;
    }
    return Status::Ok;
}

void AudioEngine::releaseStreamLocked() {
    mStream.reset();
    mDisconnected.store(false, std::memory_order_relaxed);
    mCallbackFormat.store(static_cast<int32_t>(oboe::AudioFormat::Invalid), std::memory_order_relaxed);
    mCallbackChannels.store(0, std::memory_order_relaxed);
}

void AudioEngine::snapshot(int64_t *out, int32_t count) {
    if (out == nullptr || count < kIdxCount) {
        return;
    }
    for (int32_t i = 0; i < count; ++i) {
        out[i] = 0;
    }

    std::lock_guard<std::mutex> guard(mLock);

    out[kIdxState] = mState.load(std::memory_order_relaxed);
    out[kIdxLastResult] = mLastResult.load(std::memory_order_relaxed);
    out[kIdxFramesRead] = mFramesRead.load(std::memory_order_relaxed);
    out[kIdxPeakLevelMicros] = mPeakLevelMicros.load(std::memory_order_relaxed);

    if (mStream == nullptr) {
        out[kIdxFormat] = static_cast<int64_t>(oboe::AudioFormat::Invalid);
        return;
    }

    // These getters read cached stream properties and stay valid after a
    // disconnect, so the last known configuration remains visible.
    out[kIdxSampleRate] = mStream->getSampleRate();
    out[kIdxChannelCount] = mStream->getChannelCount();
    out[kIdxFormat] = static_cast<int64_t>(mStream->getFormat());
    out[kIdxSharingMode] = static_cast<int64_t>(mStream->getSharingMode());
    out[kIdxPerformanceMode] = static_cast<int64_t>(mStream->getPerformanceMode());
    out[kIdxFramesPerBurst] = mStream->getFramesPerBurst();
    out[kIdxBufferSizeInFrames] = mStream->getBufferSizeInFrames();
    out[kIdxBufferCapacityInFrames] = mStream->getBufferCapacityInFrames();
    out[kIdxDeviceId] = mStream->getDeviceId();
    out[kIdxInputPreset] = static_cast<int64_t>(mStream->getInputPreset());
    out[kIdxAudioApi] = static_cast<int64_t>(mStream->getAudioApi());
}

const char *AudioEngine::lastResultText() const {
    const auto result = static_cast<oboe::Result>(mLastResult.load(std::memory_order_relaxed));
    return oboe::convertToText(result);
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream * /*stream*/,
                                                   void *audioData,
                                                   int32_t numFrames) {
    // REAL-TIME THREAD. No allocation, locking, logging, file I/O or JNI here.
    // Phase 2 discards the audio and only publishes a peak level so the stream
    // can be shown to be live. The ring buffer lands in the next phase.
    const int32_t channels = mCallbackChannels.load(std::memory_order_relaxed);
    const int32_t sampleCount = numFrames * channels;
    float peak = 0.0f;

    if (mCallbackFormat.load(std::memory_order_relaxed) ==
        static_cast<int32_t>(oboe::AudioFormat::Float)) {
        const auto *samples = static_cast<const float *>(audioData);
        for (int32_t i = 0; i < sampleCount; ++i) {
            const float magnitude = samples[i] < 0.0f ? -samples[i] : samples[i];
            if (magnitude > peak) {
                peak = magnitude;
            }
        }
    } else {
        // The device did not grant Float; Oboe hands us I16 instead.
        const auto *samples = static_cast<const int16_t *>(audioData);
        for (int32_t i = 0; i < sampleCount; ++i) {
            const int32_t widened = static_cast<int32_t>(samples[i]);
            const int32_t magnitude = widened < 0 ? -widened : widened;
            const float scaled = static_cast<float>(magnitude) / kInt16Scale;
            if (scaled > peak) {
                peak = scaled;
            }
        }
    }

    mFramesRead.fetch_add(numFrames, std::memory_order_relaxed);
    mPeakLevelMicros.store(static_cast<int32_t>(peak * kPeakScale), std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result result) {
    // Oboe-internal error thread. Oboe has already closed the stream. Only publish
    // flags here — taking mLock could deadlock against a concurrent close(), and
    // the next lifecycle call releases the stream pointer for us.
    mLastResult.store(static_cast<int32_t>(result), std::memory_order_relaxed);
    mDisconnected.store(true, std::memory_order_relaxed);
    mState.store(static_cast<int32_t>(EngineState::Disconnected), std::memory_order_relaxed);
}

}  // namespace roxstar
