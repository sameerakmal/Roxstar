#ifndef ROXSTAR_EFFECTS_REVERB_EFFECT_H
#define ROXSTAR_EFFECTS_REVERB_EFFECT_H

#include <array>
#include <cstdint>
#include <vector>

#include "IEffect.h"

namespace roxstar::effects {

/**
 * Lightweight Schroeder reverb: 4 parallel damped comb filters feeding 2
 * series allpass filters — the classic 1962 Schroeder topology, as
 * popularized by Freeverb-style implementations. Not studio-quality; a
 * small, cheap, stable reverb appropriate for a mobile voice recorder.
 */
class ReverbEffect final : public IEffect {
public:
    void prepare(int32_t sampleRate) override;
    void process(float *samples, int32_t numFrames) override;
    void reset() override;

private:
    // Classic Schroeder/Freeverb-style tunings (ms), scaled to the actual
    // runtime sample rate in prepare() rather than assumed for 44.1 kHz.
    static constexpr float kCombDelaysMs[4] = {29.7f, 37.1f, 41.1f, 43.7f};
    static constexpr float kAllpassDelaysMs[2] = {5.0f, 1.7f};
    static constexpr float kCombFeedback = 0.77f;  // decay time
    static constexpr float kCombDamping = 0.2f;    // one-pole lowpass in the feedback path
    static constexpr float kAllpassFeedback = 0.5f;
    static constexpr float kWetMix = 0.28f;

    struct Comb {
        std::vector<float> buffer;
        int32_t pos = 0;
        float filterStore = 0.0f;

        void prepare(int32_t delaySamples);
        float process(float input);
        void reset();
    };

    struct Allpass {
        std::vector<float> buffer;
        int32_t pos = 0;

        void prepare(int32_t delaySamples);
        float process(float input);
        void reset();
    };

    std::array<Comb, 4> mCombs;
    std::array<Allpass, 2> mAllpasses;
};

}  // namespace roxstar::effects

#endif  // ROXSTAR_EFFECTS_REVERB_EFFECT_H
