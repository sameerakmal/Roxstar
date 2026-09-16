#include "../src/effects/PitchShiftEffect.h"

#include <algorithm>
#include <cmath>
#include <iterator>
#include <vector>

#include "mini_test.h"
#include "test_signal_utils.h"

using roxstar::effects::PitchShiftEffect;

namespace {
constexpr float kSampleRate = 44100.0f;
constexpr float kPitchRatio = 1.5f;  // must match PitchShiftEffect::kPitchRatio
}  // namespace

TEST(PitchShift_SineWaveShiftsUpByExpectedRatio) {
    PitchShiftEffect shifter;
    shifter.prepare(static_cast<int32_t>(kSampleRate));

    const float inputFreq = 300.0f;
    auto signal = testutil::generateSine(inputFreq, kSampleRate, 176400);  // 4s
    shifter.process(signal.data(), static_cast<int32_t>(signal.size()));

    // Skip well past the documented startup latency (~1 grain) before
    // measuring, and measure over a long, steady-state window.
    constexpr int32_t kWindowStart = 20000;
    constexpr int32_t kWindowLength = 20000;

    const float measured = testutil::estimateFrequency(
        signal.data() + kWindowStart, kWindowLength, kSampleRate, 30, 300);

    const float expected = inputFreq * kPitchRatio;
    // Generous tolerance: this is a simple granular shifter, not claimed to
    // be exact, but it must clearly move in the right direction by roughly
    // the right amount, not just add delay or noise.
    EXPECT_TRUE(measured > expected * 0.85f);
    EXPECT_TRUE(measured < expected * 1.15f);
}

TEST(PitchShift_StartupIsSilentForOneFullGrain) {
    PitchShiftEffect shifter;
    shifter.prepare(static_cast<int32_t>(kSampleRate));

    auto signal = testutil::generateSine(300.0f, kSampleRate, 4000, 1.0f);
    shifter.process(signal.data(), static_cast<int32_t>(signal.size()));

    // Documented fixed latency: output position P is only emitted once
    // input has advanced kGrainSize (1024) samples past it, so nothing
    // reaches the output before then.
    for (int32_t i = 0; i < 1024; ++i) {
        EXPECT_NEAR(signal[static_cast<size_t>(i)], 0.0f, 1e-9);
    }
}

TEST(PitchShift_SilenceStaysSilent) {
    PitchShiftEffect shifter;
    shifter.prepare(16000);

    std::vector<float> silence(40000, 0.0f);
    shifter.process(silence.data(), static_cast<int32_t>(silence.size()));

    for (float s : silence) {
        EXPECT_NEAR(s, 0.0f, 1e-9);
    }
}

TEST(PitchShift_NoNaNOrInfOnASustainedSignal) {
    PitchShiftEffect shifter;
    shifter.prepare(static_cast<int32_t>(kSampleRate));

    auto signal = testutil::generateSine(440.0f, kSampleRate, 200000);
    shifter.process(signal.data(), static_cast<int32_t>(signal.size()));

    for (float s : signal) {
        EXPECT_TRUE(std::isfinite(s));
    }
}

TEST(PitchShift_OutputStaysBoundedOverALongRun) {
    PitchShiftEffect shifter;
    shifter.prepare(static_cast<int32_t>(kSampleRate));

    auto signal = testutil::generateSine(500.0f, kSampleRate, 300000, 0.95f);
    shifter.process(signal.data(), static_cast<int32_t>(signal.size()));

    float peak = 0.0f;
    for (float s : signal) {
        peak = std::max(peak, std::abs(s));
    }
    EXPECT_TRUE(peak <= 1.0f);  // clampSample() guarantees this structurally
}

TEST(PitchShift_ArbitraryBlockSizesMatchOneLargeBlock) {
    // This is the important correctness check for the ring-buffer bookkeeping
    // (history + overlap-add accumulator): the result must be identical
    // regardless of how the same total input is chopped into process() calls,
    // including chunks smaller than, larger than, and not aligned to the
    // grain (1024) or hop (512) sizes, across several accumulator "laps".
    constexpr int32_t kTotalSamples = 30000;

    PitchShiftEffect wholeBlock;
    wholeBlock.prepare(static_cast<int32_t>(kSampleRate));
    auto reference = testutil::generateSine(300.0f, kSampleRate, kTotalSamples);
    wholeBlock.process(reference.data(), kTotalSamples);

    PitchShiftEffect chunked;
    chunked.prepare(static_cast<int32_t>(kSampleRate));
    auto signal = testutil::generateSine(300.0f, kSampleRate, kTotalSamples);
    int32_t sizes[] = {1, 3, 511, 512, 513, 1023, 1024, 1025, 2000, 7, 9999, 5000};
    int32_t sizesSum = 0;
    for (int32_t s : sizes) sizesSum += s;
    sizes[std::size(sizes) - 1] += kTotalSamples - sizesSum;  // absorb the remainder into the last chunk

    int32_t offset = 0;
    for (int32_t chunk : sizes) {
        chunked.process(signal.data() + offset, chunk);
        offset += chunk;
    }
    EXPECT_EQ(offset, kTotalSamples);  // the block sizes above must sum to the total

    for (int32_t i = 0; i < kTotalSamples; ++i) {
        EXPECT_NEAR(signal[static_cast<size_t>(i)], reference[static_cast<size_t>(i)], 1e-5);
    }
}

TEST(PitchShift_SingleSampleAtATimeMatchesOneLargeBlock) {
    // The most extreme block-size case: one frame per process() call.
    constexpr int32_t kTotalSamples = 6000;

    PitchShiftEffect wholeBlock;
    wholeBlock.prepare(static_cast<int32_t>(kSampleRate));
    auto reference = testutil::generateSine(300.0f, kSampleRate, kTotalSamples);
    wholeBlock.process(reference.data(), kTotalSamples);

    PitchShiftEffect perSample;
    perSample.prepare(static_cast<int32_t>(kSampleRate));
    auto signal = testutil::generateSine(300.0f, kSampleRate, kTotalSamples);
    for (int32_t i = 0; i < kTotalSamples; ++i) {
        perSample.process(signal.data() + i, 1);
    }

    for (int32_t i = 0; i < kTotalSamples; ++i) {
        EXPECT_NEAR(signal[static_cast<size_t>(i)], reference[static_cast<size_t>(i)], 1e-5);
    }
}

TEST(PitchShift_ImpulseEnergyAppearsAtTheDocumentedLatency) {
    // A periodic sine can mask a constant grain-alignment offset (many
    // ring-buffer indexing bugs still "look" periodic on a periodic input).
    // A sparse impulse pins down exact timing: energy must appear centered
    // within one grain of (impulsePos + kGrainSize), the documented latency,
    // and nowhere else.
    constexpr int32_t kGrainSize = 1024;  // must match PitchShiftEffect::kGrainSize
    constexpr int32_t kImpulsePos = 5000;
    constexpr int32_t kTotalSamples = 12000;

    PitchShiftEffect shifter;
    shifter.prepare(static_cast<int32_t>(kSampleRate));
    std::vector<float> signal(kTotalSamples, 0.0f);
    signal[kImpulsePos] = 1.0f;
    shifter.process(signal.data(), kTotalSamples);

    int32_t peakIndex = 0;
    float peakValue = 0.0f;
    for (int32_t i = 0; i < kTotalSamples; ++i) {
        if (std::abs(signal[static_cast<size_t>(i)]) > peakValue) {
            peakValue = std::abs(signal[static_cast<size_t>(i)]);
            peakIndex = i;
        }
    }

    const int32_t expectedCenter = kImpulsePos + kGrainSize;
    EXPECT_TRUE(peakValue > 0.01f);
    EXPECT_TRUE(std::abs(peakIndex - expectedCenter) <= kGrainSize / 2);

    // Nothing before the impulse could possibly have arrived yet.
    for (int32_t i = 0; i < kImpulsePos; ++i) {
        EXPECT_NEAR(signal[static_cast<size_t>(i)], 0.0f, 1e-9);
    }
    // Nothing should still be ringing two full grains after the latency
    // window — a stray constant-offset bug tends to smear energy into
    // slots that should already be back to silence.
    for (int32_t i = expectedCenter + kGrainSize; i < kTotalSamples; ++i) {
        EXPECT_NEAR(signal[static_cast<size_t>(i)], 0.0f, 1e-4);
    }
}

TEST(PitchShift_ResetProducesTheSameOutputAsAFreshInstance) {
    PitchShiftEffect fresh;
    fresh.prepare(static_cast<int32_t>(kSampleRate));
    auto referenceSignal = testutil::generateSine(300.0f, kSampleRate, 10000);
    fresh.process(referenceSignal.data(), static_cast<int32_t>(referenceSignal.size()));

    PitchShiftEffect reused;
    reused.prepare(static_cast<int32_t>(kSampleRate));
    auto warmup = testutil::generateSine(700.0f, kSampleRate, 8000);
    reused.process(warmup.data(), static_cast<int32_t>(warmup.size()));  // leave it "dirty"
    reused.reset();

    auto signal = testutil::generateSine(300.0f, kSampleRate, 10000);
    reused.process(signal.data(), static_cast<int32_t>(signal.size()));

    for (size_t i = 0; i < signal.size(); ++i) {
        EXPECT_NEAR(signal[i], referenceSignal[i], 1e-5);
    }
}
