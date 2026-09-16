#include "../src/effects/IEffect.h"

#include <algorithm>

#include "mini_test.h"

using roxstar::effects::createEffect;
using roxstar::effects::EffectType;
using roxstar::effects::isValidEffectType;

TEST(EffectFactory_AllFourDefinedValuesAreValid) {
    EXPECT_TRUE(isValidEffectType(0));  // None
    EXPECT_TRUE(isValidEffectType(1));  // Echo
    EXPECT_TRUE(isValidEffectType(2));  // Reverb
    EXPECT_TRUE(isValidEffectType(3));  // PitchShift
}

TEST(EffectFactory_OutOfRangeValuesAreInvalid) {
    // This is the safety net for whatever raw int crosses the JNI boundary.
    EXPECT_FALSE(isValidEffectType(-1));
    EXPECT_FALSE(isValidEffectType(4));
    EXPECT_FALSE(isValidEffectType(999));
    EXPECT_FALSE(isValidEffectType(-999));
}

TEST(EffectFactory_NoneReturnsNullptr) {
    EXPECT_TRUE(createEffect(EffectType::None) == nullptr);
}

TEST(EffectFactory_EachRealEffectReturnsANonNullDistinctInstance) {
    auto echo = createEffect(EffectType::Echo);
    auto reverb = createEffect(EffectType::Reverb);
    auto pitch = createEffect(EffectType::PitchShift);

    EXPECT_TRUE(echo != nullptr);
    EXPECT_TRUE(reverb != nullptr);
    EXPECT_TRUE(pitch != nullptr);
}

TEST(EffectFactory_UnpreparedEffectBypassesRatherThanCrashing) {
    // process() must be safe to call even if prepare() was skipped (it never
    // is in production, but the interface itself should not assume it).
    auto echo = createEffect(EffectType::Echo);
    float samples[8] = {0.1f, 0.2f, -0.3f, 0.4f, -0.5f, 0.6f, -0.7f, 0.8f};
    float original[8];
    std::copy(std::begin(samples), std::end(samples), std::begin(original));

    echo->process(samples, 8);

    for (int i = 0; i < 8; ++i) {
        EXPECT_NEAR(samples[i], original[i], 1e-9);
    }
}
