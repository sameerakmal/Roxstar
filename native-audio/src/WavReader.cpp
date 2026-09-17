#include "WavReader.h"

#include <cstdio>
#include <cstring>

namespace roxstar {

namespace {

constexpr float kInt16Scale = 32768.0f;

uint32_t readU32LE(const uint8_t *p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

uint16_t readU16LE(const uint8_t *p) {
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}

bool readWholeFile(const std::string &path, std::vector<uint8_t> *out) {
    std::FILE *f = std::fopen(path.c_str(), "rb");
    if (f == nullptr) return false;

    std::fseek(f, 0, SEEK_END);
    const long size = std::ftell(f);
    if (size < 0) {
        std::fclose(f);
        return false;
    }
    std::fseek(f, 0, SEEK_SET);

    out->resize(static_cast<size_t>(size));
    const bool ok = size == 0 || std::fread(out->data(), 1, out->size(), f) == out->size();
    std::fclose(f);
    return ok;
}

}  // namespace

void WavReader::reset() {
    mSamples.clear();
    mSamples.shrink_to_fit();
    mSampleRate = 0;
    mChannelCount = 0;
    mFrameCount = 0;
    mLoaded = false;
}

bool WavReader::load(const std::string &path) {
    reset();

    std::vector<uint8_t> raw;
    if (!readWholeFile(path, &raw)) return false;
    if (raw.size() < 12) return false;
    if (std::memcmp(raw.data(), "RIFF", 4) != 0) return false;
    if (std::memcmp(raw.data() + 8, "WAVE", 4) != 0) return false;

    bool haveFmt = false;
    uint16_t audioFormat = 0;
    uint16_t numChannels = 0;
    uint32_t sampleRate = 0;
    uint16_t bitsPerSample = 0;

    bool haveData = false;
    size_t dataOffset = 0;
    size_t dataSize = 0;

    size_t pos = 12;
    while (pos + 8 <= raw.size()) {
        const uint8_t *chunkId = raw.data() + pos;
        uint32_t chunkSize = readU32LE(raw.data() + pos + 4);
        const size_t chunkStart = pos + 8;

        if (chunkStart + chunkSize > raw.size()) {
            // A "data" chunk with a size that runs past EOF (e.g. a stream
            // that never got its placeholder size patched) is still usable —
            // just take what's actually there. Any other oversized chunk
            // means the file is malformed.
            if (std::memcmp(chunkId, "data", 4) == 0) {
                chunkSize = static_cast<uint32_t>(raw.size() - chunkStart);
            } else {
                return false;
            }
        }

        if (std::memcmp(chunkId, "fmt ", 4) == 0) {
            if (chunkSize < 16) return false;
            audioFormat = readU16LE(raw.data() + chunkStart + 0);
            numChannels = readU16LE(raw.data() + chunkStart + 2);
            sampleRate = readU32LE(raw.data() + chunkStart + 4);
            bitsPerSample = readU16LE(raw.data() + chunkStart + 14);
            haveFmt = true;
        } else if (std::memcmp(chunkId, "data", 4) == 0) {
            dataOffset = chunkStart;
            dataSize = chunkSize;
            haveData = true;
        }

        // RIFF chunks are word-aligned: a chunk with an odd size has one
        // padding byte after it that isn't part of the next chunk's header.
        pos = chunkStart + chunkSize + (chunkSize % 2);
    }

    if (!haveFmt || !haveData) return false;
    if (audioFormat != 1) return false;      // PCM only
    if (bitsPerSample != 16) return false;   // 16-bit only
    if (numChannels != 1) return false;      // mono only — matches what this app ever writes
    if (sampleRate == 0) return false;

    const int64_t frameCount = static_cast<int64_t>(dataSize / 2);
    mSamples.resize(static_cast<size_t>(frameCount));
    for (int64_t i = 0; i < frameCount; ++i) {
        const auto sample16 =
            static_cast<int16_t>(readU16LE(raw.data() + dataOffset + static_cast<size_t>(i) * 2));
        mSamples[static_cast<size_t>(i)] = static_cast<float>(sample16) / kInt16Scale;
    }

    mSampleRate = static_cast<int32_t>(sampleRate);
    mChannelCount = numChannels;
    mFrameCount = frameCount;
    mLoaded = true;
    return true;
}

}  // namespace roxstar
