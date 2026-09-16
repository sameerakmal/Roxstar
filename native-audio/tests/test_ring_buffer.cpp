#include "../RingBuffer.h"

#include "mini_test.h"

using roxstar::RingBuffer;

TEST(RingBuffer_CapacityRoundsUpToPowerOfTwo) {
    RingBuffer rb(100);
    EXPECT_EQ(rb.capacity(), static_cast<size_t>(128));

    RingBuffer exact(128);
    EXPECT_EQ(exact.capacity(), static_cast<size_t>(128));
}

TEST(RingBuffer_PushPopRoundTripPreservesOrder) {
    RingBuffer rb(16);
    float in[5] = {0.1f, 0.2f, 0.3f, 0.4f, 0.5f};
    const size_t written = rb.push(in, 5);
    EXPECT_EQ(written, static_cast<size_t>(5));
    EXPECT_EQ(rb.availableToRead(), static_cast<size_t>(5));

    float out[5] = {};
    const size_t read = rb.pop(out, 5);
    EXPECT_EQ(read, static_cast<size_t>(5));
    for (int i = 0; i < 5; ++i) {
        EXPECT_NEAR(out[i], in[i], 1e-9);
    }
    EXPECT_EQ(rb.availableToRead(), static_cast<size_t>(0));
}

TEST(RingBuffer_PopOnEmptyReturnsZero) {
    RingBuffer rb(16);
    float out[4] = {};
    EXPECT_EQ(rb.pop(out, 4), static_cast<size_t>(0));
}

TEST(RingBuffer_OverrunReturnsFewerThanRequested) {
    // Capacity rounds up to 8. Fill it completely, then try to push more
    // without draining — this is exactly the real-time-callback overrun case.
    RingBuffer rb(8);
    float fill[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    EXPECT_EQ(rb.push(fill, 8), static_cast<size_t>(8));

    float overflow[4] = {9, 10, 11, 12};
    const size_t written = rb.push(overflow, 4);
    EXPECT_TRUE(written < 4);  // explicit overrun: caller can compute the drop count
    EXPECT_EQ(written, static_cast<size_t>(0));  // buffer was completely full
    EXPECT_EQ(rb.availableToRead(), static_cast<size_t>(8));  // nothing corrupted
}

TEST(RingBuffer_PartialOverrunWritesWhatFitsAndReportsTheRest) {
    RingBuffer rb(8);
    float fill[6] = {1, 2, 3, 4, 5, 6};
    rb.push(fill, 6);  // 2 slots free

    float overflow[5] = {7, 8, 9, 10, 11};
    const size_t written = rb.push(overflow, 5);
    EXPECT_EQ(written, static_cast<size_t>(2));  // only room for 2; 3 dropped

    float out[8] = {};
    const size_t read = rb.pop(out, 8);
    EXPECT_EQ(read, static_cast<size_t>(8));
    // The 2 that fit (7, 8) must follow the original 6 in order.
    EXPECT_NEAR(out[6], 7.0f, 1e-9);
    EXPECT_NEAR(out[7], 8.0f, 1e-9);
}

TEST(RingBuffer_WrapAroundAfterDrainContinuesCorrectly) {
    RingBuffer rb(8);
    float a[6] = {1, 2, 3, 4, 5, 6};
    rb.push(a, 6);
    float drained[4] = {};
    rb.pop(drained, 4);  // tail advances past 4; 2 remain (5, 6)

    // Push enough to wrap the ring around the end of the backing array.
    float b[6] = {7, 8, 9, 10, 11, 12};
    const size_t written = rb.push(b, 6);
    EXPECT_EQ(written, static_cast<size_t>(6));  // 2 remaining + 6 new = 8, exactly fits

    float out[8] = {};
    const size_t read = rb.pop(out, 8);
    EXPECT_EQ(read, static_cast<size_t>(8));
    const float expected[8] = {5, 6, 7, 8, 9, 10, 11, 12};
    for (int i = 0; i < 8; ++i) {
        EXPECT_NEAR(out[i], expected[i], 1e-9);
    }
}

TEST(RingBuffer_ManySmallChunksAccumulateCorrectly) {
    RingBuffer rb(4096);
    constexpr int kChunks = 500;
    for (int i = 0; i < kChunks; ++i) {
        float v = static_cast<float>(i);
        EXPECT_EQ(rb.push(&v, 1), static_cast<size_t>(1));
    }
    EXPECT_EQ(rb.availableToRead(), static_cast<size_t>(kChunks));

    for (int i = 0; i < kChunks; ++i) {
        float out = -1.0f;
        EXPECT_EQ(rb.pop(&out, 1), static_cast<size_t>(1));
        EXPECT_NEAR(out, static_cast<float>(i), 1e-9);
    }
}
