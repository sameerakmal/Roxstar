#include "EchoEffect.h"

#include <algorithm>
#include <cmath>

namespace roxstar::effects {

void EchoEffect::prepare(int32_t sampleRate) {
    const int32_t delaySamples =
        std::max(1, static_cast<int32_t>(std::lround(sampleRate * kDelayMs / 1000.0f)));
    mDelayLine.assign(static_cast<size_t>(delaySamples), 0.0f);
    mWritePos = 0;
}

void EchoEffect::process(float *samples, int32_t numFrames) {
    if (mDelayLine.empty() || numFrames <= 0) return;  // prepare() not called; bypass.

    const auto size = static_cast<int32_t>(mDelayLine.size());
    for (int32_t i = 0; i < numFrames; ++i) {
        const float dry = samples[i];
        const float delayed = mDelayLine[static_cast<size_t>(mWritePos)];

        mDelayLine[static_cast<size_t>(mWritePos)] = dry + delayed * kFeedback;
        samples[i] = clampSample(dry + delayed * kWetMix);

        if (++mWritePos >= size) mWritePos = 0;
    }
}

void EchoEffect::reset() {
    std::fill(mDelayLine.begin(), mDelayLine.end(), 0.0f);
    mWritePos = 0;
}

}  // namespace roxstar::effects
