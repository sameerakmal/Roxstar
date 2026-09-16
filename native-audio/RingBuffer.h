#ifndef ROXSTAR_RING_BUFFER_H
#define ROXSTAR_RING_BUFFER_H

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>

namespace roxstar {

/**
 * Lock-free single-producer/single-consumer ring buffer of float samples.
 *
 * Portable, standard C++17 — no Android/Oboe/JNI dependency, so it can be
 * unit-tested on the host as well as compiled into the Android build.
 *
 * Contract:
 *  - push() is called only by the producer thread (the Oboe audio callback).
 *  - pop() is called only by the consumer thread (the WAV writer thread).
 *  - Capacity is fixed at construction (the only allocation); push/pop never
 *    allocate, lock, block or throw.
 *  - Overrun (push() writing fewer samples than requested because the buffer
 *    is full) is reported via the return value, never hidden.
 *
 * Implementation: classic SPSC ring with monotonically increasing head/tail
 * counters (not wrapped), masked into the backing array with a power-of-two
 * capacity. mHead is written only by the producer, mTail only by the
 * consumer; each side does a relaxed load of its own counter and an acquire
 * load of the other's, and a release store of its own after touching the
 * data — the standard SPSC handoff.
 */
class RingBuffer {
public:
    explicit RingBuffer(size_t minCapacity)
        : mCapacity(nextPowerOfTwo(minCapacity)),
          mMask(mCapacity - 1),
          mData(std::make_unique<float[]>(mCapacity)) {}

    RingBuffer(const RingBuffer &) = delete;
    RingBuffer &operator=(const RingBuffer &) = delete;

    size_t capacity() const { return mCapacity; }

    /** PRODUCER ONLY. Returns the number of samples actually written (may be
     *  less than count if the buffer is full — that shortfall is an overrun,
     *  which the caller is expected to count). */
    size_t push(const float *src, size_t count) {
        const size_t head = mHead.load(std::memory_order_relaxed);
        const size_t tail = mTail.load(std::memory_order_acquire);
        const size_t free = mCapacity - (head - tail);
        const size_t toWrite = count < free ? count : free;

        for (size_t i = 0; i < toWrite; ++i) {
            mData[(head + i) & mMask] = src[i];
        }
        mHead.store(head + toWrite, std::memory_order_release);
        return toWrite;
    }

    /** CONSUMER ONLY. Returns the number of samples actually read. */
    size_t pop(float *dst, size_t maxCount) {
        const size_t tail = mTail.load(std::memory_order_relaxed);
        const size_t head = mHead.load(std::memory_order_acquire);
        const size_t available = head - tail;
        const size_t toRead = maxCount < available ? maxCount : available;

        for (size_t i = 0; i < toRead; ++i) {
            dst[i] = mData[(tail + i) & mMask];
        }
        mTail.store(tail + toRead, std::memory_order_release);
        return toRead;
    }

    /** Approximate — safe to call from either thread for diagnostics/tests only. */
    size_t availableToRead() const {
        return mHead.load(std::memory_order_acquire) - mTail.load(std::memory_order_acquire);
    }

    size_t availableToWrite() const { return mCapacity - availableToRead(); }

private:
    static size_t nextPowerOfTwo(size_t v) {
        size_t p = 1;
        while (p < v) {
            p <<= 1;
        }
        return p;
    }

    const size_t mCapacity;
    const size_t mMask;
    std::unique_ptr<float[]> mData;

    // Monotonically increasing; wraparound of size_t itself is not a concern
    // at audio sample rates within any realistic recording session length.
    std::atomic<size_t> mHead{0};
    std::atomic<size_t> mTail{0};
};

}  // namespace roxstar

#endif  // ROXSTAR_RING_BUFFER_H
