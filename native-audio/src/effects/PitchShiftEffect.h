#ifndef ROXSTAR_EFFECTS_PITCH_SHIFT_EFFECT_H
#define ROXSTAR_EFFECTS_PITCH_SHIFT_EFFECT_H

#include <cstdint>
#include <vector>

#include "IEffect.h"

namespace roxstar::effects {

/**
 * Real-time granular pitch shifter: resample-in-grain + Hann-windowed
 * overlap-add, fixed at a 1.5x upward shift.
 *
 * How this actually changes pitch (not just speed): every kHopSize (512)
 * new input samples, a kGrainSize (1024) grain is synthesized by reading
 * recent input history at kPitchRatio source-samples per output sample —
 * that fractional-rate read is what raises the pitch, the same way speeding
 * up tape does. Both what a grain reads (its source window) and where it
 * writes (its slot in the overlap-add accumulator) are anchored to the
 * current input position, not to an absolute grain count from stream start
 * — that is what keeps grain-to-grain output timing locked to real time
 * (grain count = input length / hop, same as with no shift at all) even
 * though the content within each grain is genuinely played back at a
 * different rate. Grains are Hann-windowed and overlap-added at 50%
 * overlap, which sums to constant gain across the crossfade.
 *
 * Output lags input by a fixed kGrainSize samples: a sample is only emitted
 * once every grain able to contribute to it is guaranteed to have already
 * fired, which this fixed latency guarantees by construction (see
 * popOutputSample). That fixed lag, not an adaptive "ready" check, is
 * deliberate — an earlier version of this effect derived readiness from a
 * running counter and it was wrong: grains write their full span in one
 * shot when they fire, and a naive "region [k*hop,(k+1)*hop) is ready once
 * grain k fires" check emits positions before the grain that owns them has
 * actually run, permanently losing that data and leaking unbounded energy
 * into the ring buffer from then on. Fixed one-grain latency sidesteps the
 * bookkeeping entirely: by the time output position P is due, input has
 * necessarily advanced far enough that no grain still needs to write there.
 *
 * Honest limitations:
 *  - ~1 grain (21ms at 48kHz, 64ms at 16kHz) of silence at the very start of
 *    every recording before real content reaches the output.
 *  - Each grain looks back into history by up to (grainSize-1)*ratio
 *    samples, so the earliest part of a grain is slightly stale relative to
 *    the latest part — audible as mild smearing on fast transients. This is
 *    a known characteristic of simple time-domain granular shifters, not a
 *    bug; a phase vocoder would avoid it at far higher CPU cost.
 *  - No formant correction — voices sound processed ("chipmunked") rather
 *    than like a natural higher voice.
 */
class PitchShiftEffect final : public IEffect {
public:
    void prepare(int32_t sampleRate) override;
    void process(float *samples, int32_t numFrames) override;
    void reset() override;

private:
    static constexpr int32_t kGrainSize = 1024;
    static constexpr int32_t kHopSize = kGrainSize / 2;      // 50% overlap
    static constexpr int32_t kHistorySize = kGrainSize * 4;  // ample margin for the lookback below
    static constexpr float kPitchRatio = 1.5f;

    void synthesizeGrain();
    float readHistoryInterpolated(double absolutePos) const;
    void pushInputSample(float sample);
    float popOutputSample();

    std::vector<float> mHistory;
    std::vector<float> mHannWindow;
    std::vector<float> mAccum;

    int64_t mTotalInputWritten = 0;
    int64_t mSamplesUntilNextGrain = kHopSize;
};

}  // namespace roxstar::effects

#endif  // ROXSTAR_EFFECTS_PITCH_SHIFT_EFFECT_H
