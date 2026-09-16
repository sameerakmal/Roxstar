#include "ReverbEffect.h"

#include <algorithm>
#include <cmath>

namespace roxstar::effects {

void ReverbEffect::Comb::prepare(int32_t delaySamples) {
    buffer.assign(static_cast<size_t>(std::max(1, delaySamples)), 0.0f);
    pos = 0;
    filterStore = 0.0f;
}

float ReverbEffect::Comb::process(float input) {
    const float output = buffer[static_cast<size_t>(pos)];
    // One-pole lowpass in the feedback path damps high frequencies faster
    // than low ones on each pass, so the tail darkens as it decays instead
    // of ringing metallically.
    filterStore = output * (1.0f - kCombDamping) + filterStore * kCombDamping;
    buffer[static_cast<size_t>(pos)] = input + filterStore * kCombFeedback;
    if (++pos >= static_cast<int32_t>(buffer.size())) pos = 0;
    return output;
}

void ReverbEffect::Comb::reset() {
    std::fill(buffer.begin(), buffer.end(), 0.0f);
    pos = 0;
    filterStore = 0.0f;
}

void ReverbEffect::Allpass::prepare(int32_t delaySamples) {
    buffer.assign(static_cast<size_t>(std::max(1, delaySamples)), 0.0f);
    pos = 0;
}

float ReverbEffect::Allpass::process(float input) {
    const float bufOut = buffer[static_cast<size_t>(pos)];
    const float output = -kAllpassFeedback * input + bufOut;
    buffer[static_cast<size_t>(pos)] = input + kAllpassFeedback * bufOut;
    if (++pos >= static_cast<int32_t>(buffer.size())) pos = 0;
    return output;
}

void ReverbEffect::Allpass::reset() {
    std::fill(buffer.begin(), buffer.end(), 0.0f);
    pos = 0;
}

void ReverbEffect::prepare(int32_t sampleRate) {
    for (size_t i = 0; i < mCombs.size(); ++i) {
        mCombs[i].prepare(static_cast<int32_t>(std::lround(sampleRate * kCombDelaysMs[i] / 1000.0f)));
    }
    for (size_t i = 0; i < mAllpasses.size(); ++i) {
        mAllpasses[i].prepare(
            static_cast<int32_t>(std::lround(sampleRate * kAllpassDelaysMs[i] / 1000.0f)));
    }
}

void ReverbEffect::process(float *samples, int32_t numFrames) {
    if (mCombs[0].buffer.empty() || numFrames <= 0) return;  // prepare() not called; bypass.

    for (int32_t i = 0; i < numFrames; ++i) {
        const float dry = samples[i];

        float combSum = 0.0f;
        for (auto &comb : mCombs) {
            combSum += comb.process(dry);
        }
        combSum *= 0.25f;  // 4 parallel combs summed; scale back down.

        float wet = combSum;
        for (auto &allpass : mAllpasses) {
            wet = allpass.process(wet);
        }

        samples[i] = clampSample(dry * (1.0f - kWetMix) + wet * kWetMix);
    }
}

void ReverbEffect::reset() {
    for (auto &comb : mCombs) comb.reset();
    for (auto &allpass : mAllpasses) allpass.reset();
}

}  // namespace roxstar::effects
