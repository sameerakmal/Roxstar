#include "PitchShiftEffect.h"

#include <algorithm>
#include <cmath>

namespace roxstar::effects {

namespace {
constexpr float kTwoPi = 6.283185307f;
}  // namespace

void PitchShiftEffect::prepare(int32_t /*sampleRate*/) {
    // Grain/hop are fixed in samples, not scaled by sample rate: at typical
    // voice sample rates (16-48kHz) 1024 samples is 21-64ms, a reasonable
    // grain length across that whole range for this technique.
    mHistory.assign(kHistorySize, 0.0f);
    mAccum.assign(kGrainSize, 0.0f);

    // Periodic form (denominator kGrainSize, not kGrainSize-1): this is what
    // makes two 50%-overlapped windows sum to a constant — the symmetric
    // "FIR design" Hann window does not satisfy that exactly.
    mHannWindow.resize(kGrainSize);
    for (int32_t i = 0; i < kGrainSize; ++i) {
        mHannWindow[static_cast<size_t>(i)] =
            0.5f - 0.5f * std::cos(kTwoPi * static_cast<float>(i) / kGrainSize);
    }

    reset();
}

void PitchShiftEffect::reset() {
    std::fill(mHistory.begin(), mHistory.end(), 0.0f);
    std::fill(mAccum.begin(), mAccum.end(), 0.0f);
    mTotalInputWritten = 0;
    mSamplesUntilNextGrain = kHopSize;
}

float PitchShiftEffect::readHistoryInterpolated(double absolutePos) const {
    if (absolutePos < 0.0) return 0.0f;

    const auto i0 = static_cast<int64_t>(std::floor(absolutePos));
    if (i0 >= mTotalInputWritten) return 0.0f;               // not written yet
    if (mTotalInputWritten - i0 > kHistorySize) return 0.0f;  // already overwritten

    const int64_t i1 = i0 + 1;
    const float frac = static_cast<float>(absolutePos - static_cast<double>(i0));
    const float s0 = mHistory[static_cast<size_t>(i0 % kHistorySize)];

    float s1 = s0;
    if (i1 < mTotalInputWritten && mTotalInputWritten - i1 <= kHistorySize) {
        s1 = mHistory[static_cast<size_t>(i1 % kHistorySize)];
    }
    return s0 + (s1 - s0) * frac;
}

void PitchShiftEffect::synthesizeGrain() {
    // Both anchors track mTotalInputWritten ("now") directly, rather than an
    // absolute grain count from stream start — see the class comment for why
    // that matters: it is what keeps a grain's write window from landing on
    // output positions already emitted by the time the grain fires.
    const double grainStart = static_cast<double>(mTotalInputWritten - 1) -
                               static_cast<double>(kGrainSize - 1) * kPitchRatio;
    const int64_t writeBase = mTotalInputWritten - kGrainSize;

    for (int32_t i = 0; i < kGrainSize; ++i) {
        const double sourcePos = grainStart + static_cast<double>(i) * kPitchRatio;
        const float sample =
            readHistoryInterpolated(sourcePos) * mHannWindow[static_cast<size_t>(i)];
        // writeBase can be negative for the first couple of grains; floor-mod
        // (not C++'s truncating %) keeps the ring index non-negative.
        const int64_t absoluteIndex = writeBase + i;
        const auto accumIndex = static_cast<size_t>(
            ((absoluteIndex % kGrainSize) + kGrainSize) % kGrainSize);
        mAccum[accumIndex] += sample;
    }
}

void PitchShiftEffect::pushInputSample(float sample) {
    mHistory[static_cast<size_t>(mTotalInputWritten % kHistorySize)] = sample;
    ++mTotalInputWritten;

    if (--mSamplesUntilNextGrain == 0) {
        synthesizeGrain();
        mSamplesUntilNextGrain = kHopSize;
    }
}

float PitchShiftEffect::popOutputSample() {
    // Fixed one-grain output latency: the sample just pushed (absolute index
    // mTotalInputWritten - 1) is only emitted once every grain able to
    // contribute to it is guaranteed to have fired, which is true once input
    // has advanced a further kGrainSize samples past it. No separate
    // "readiness" bookkeeping is needed — see the class comment.
    const int64_t outputPos = (mTotalInputWritten - 1) - kGrainSize;
    if (outputPos < 0) {
        return 0.0f;  // still priming — startup latency, documented above.
    }
    const auto index = static_cast<size_t>(outputPos % kGrainSize);
    const float value = mAccum[index];
    mAccum[index] = 0.0f;  // this ring slot is fully consumed; clear it for its next lap.
    return value;
}

void PitchShiftEffect::process(float *samples, int32_t numFrames) {
    if (mHistory.empty() || numFrames <= 0) return;  // prepare() not called; bypass.

    // Push then pop per sample (not two separate passes over the whole
    // buffer): popOutputSample's latency check depends on mTotalInputWritten
    // reflecting exactly this sample's push, not the end of a large batch —
    // batching pushes ahead of pops lets several grains fire before any
    // emission/clearing happens, which corrupts the overlap-add accumulator
    // for callbacks larger than one hop.
    for (int32_t i = 0; i < numFrames; ++i) {
        pushInputSample(samples[i]);
        samples[i] = clampSample(popOutputSample());
    }
}

}  // namespace roxstar::effects
