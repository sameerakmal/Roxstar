#include "../src/effects/ReverbEffect.h"

#include <algorithm>
#include <cmath>
#include <vector>

#include "mini_test.h"
#include "test_signal_utils.h"

using roxstar::effects::ReverbEffect;

TEST(Reverb_ImpulseResponseDiffersFromInputAndTrailsAfterIt) {
    ReverbEffect reverb;
    reverb.prepare(44100);

    constexpr int32_t kNumSamples = 30000;
    std::vector<float> signal(kNumSamples, 0.0f);
    signal[0] = 1.0f;
    reverb.process(signal.data(), kNumSamples);

    // A real reverb spreads the impulse's energy forward in time — later
    // samples should not all be exactly zero (that would mean bypass).
    bool foundTail = false;
    for (int32_t i = 100; i < kNumSamples; ++i) {
        if (std::abs(signal[i]) > 1e-6f) {
            foundTail = true;
            break;
        }
    }
    EXPECT_TRUE(foundTail);
}

TEST(Reverb_OutputStaysBoundedOverALongRun) {
    ReverbEffect reverb;
    reverb.prepare(44100);

    auto signal = testutil::generateSine(300.0f, 44100.0f, 300000, 0.9f);
    reverb.process(signal.data(), static_cast<int32_t>(signal.size()));

    float peak = 0.0f;
    for (float s : signal) {
        EXPECT_TRUE(std::isfinite(s));
        peak = std::max(peak, std::abs(s));
    }
    EXPECT_TRUE(peak <= 1.0f);  // clampSample() guarantees this structurally
}

TEST(Reverb_SilenceStaysSilent) {
    ReverbEffect reverb;
    reverb.prepare(16000);

    std::vector<float> silence(20000, 0.0f);
    reverb.process(silence.data(), static_cast<int32_t>(silence.size()));

    for (float s : silence) {
        EXPECT_NEAR(s, 0.0f, 1e-9);
    }
}

TEST(Reverb_ArbitraryBlockSizesMatchOneLargeBlock) {
    ReverbEffect wholeBlock;
    wholeBlock.prepare(22050);
    auto reference = testutil::generateSine(250.0f, 22050.0f, 8000);
    wholeBlock.process(reference.data(), static_cast<int32_t>(reference.size()));

    ReverbEffect chunked;
    chunked.prepare(22050);
    auto signal = testutil::generateSine(250.0f, 22050.0f, 8000);
    const int32_t sizes[] = {1, 2, 500, 3999, 3498};
    int32_t offset = 0;
    for (int32_t chunk : sizes) {
        chunked.process(signal.data() + offset, chunk);
        offset += chunk;
    }

    for (size_t i = 0; i < signal.size(); ++i) {
        EXPECT_NEAR(signal[i], reference[i], 1e-5);
    }
}

TEST(Reverb_ResetClearsFilterState) {
    ReverbEffect reverb;
    reverb.prepare(16000);

    std::vector<float> impulse(10000, 0.0f);
    impulse[0] = 1.0f;
    reverb.process(impulse.data(), static_cast<int32_t>(impulse.size()));

    reverb.reset();

    std::vector<float> secondImpulse(10000, 0.0f);
    secondImpulse[0] = 1.0f;
    reverb.process(secondImpulse.data(), static_cast<int32_t>(secondImpulse.size()));

    for (size_t i = 0; i < impulse.size(); ++i) {
        EXPECT_NEAR(impulse[i], secondImpulse[i], 1e-6);
    }
}

TEST(Reverb_ScalesCombDelaysWithSampleRate) {
    // Comb delays are specified in ms; at double the sample rate the delay
    // in samples should roughly double too (not stay fixed, which would
    // mean the ms->samples conversion was hardcoded to one rate).
    ReverbEffect lowRate;
    lowRate.prepare(22050);
    std::vector<float> lowSignal(5000, 0.0f);
    lowSignal[0] = 1.0f;
    lowRate.process(lowSignal.data(), static_cast<int32_t>(lowSignal.size()));

    ReverbEffect highRate;
    highRate.prepare(44100);
    std::vector<float> highSignal(5000, 0.0f);
    highSignal[0] = 1.0f;
    highRate.process(highSignal.data(), static_cast<int32_t>(highSignal.size()));

    // Find the first sample index (after the dry impulse) where each
    // produces non-trivial energy — the high-rate comb delay should be
    // roughly twice the low-rate one, not equal.
    auto firstTail = [](const std::vector<float> &signal) {
        for (size_t i = 1; i < signal.size(); ++i) {
            if (std::abs(signal[i]) > 1e-4f) return static_cast<int32_t>(i);
        }
        return -1;
    };
    const int32_t lowFirst = firstTail(lowSignal);
    const int32_t highFirst = firstTail(highSignal);

    EXPECT_TRUE(lowFirst > 0);
    EXPECT_TRUE(highFirst > 0);
    EXPECT_TRUE(highFirst > lowFirst);  // scaled up, not identical
}
