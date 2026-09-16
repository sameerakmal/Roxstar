#ifndef ROXSTAR_EFFECTS_ECHO_EFFECT_H
#define ROXSTAR_EFFECTS_ECHO_EFFECT_H

#include <cstdint>
#include <vector>

#include "IEffect.h"

namespace roxstar::effects {

/**
 * Single-tap feedback delay ("echo"). The classic feedback comb:
 *
 *   y[n]              = x[n] + wetMix * delayLine[readPos]
 *   delayLine[readPos] = x[n] + feedback * delayLine[readPos]
 *
 * feedback < 1 means the delayed energy decays geometrically on every pass,
 * so the output is bounded by construction — there is no feedback path that
 * can grow without limit.
 */
class EchoEffect final : public IEffect {
public:
    void prepare(int32_t sampleRate) override;
    void process(float *samples, int32_t numFrames) override;
    void reset() override;

private:
    static constexpr float kDelayMs = 300.0f;
    static constexpr float kFeedback = 0.4f;
    static constexpr float kWetMix = 0.35f;

    std::vector<float> mDelayLine;
    int32_t mWritePos = 0;
};

}  // namespace roxstar::effects

#endif  // ROXSTAR_EFFECTS_ECHO_EFFECT_H
