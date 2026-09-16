#include "WavWriter.h"

#include <algorithm>
#include <cstring>

namespace roxstar {

namespace {

constexpr uint16_t kBitsPerSample = 16;
constexpr uint16_t kPcmFormat = 1;
constexpr float kInt16Max = 32767.0f;
constexpr float kInt16Min = -32768.0f;

void putU32LE(uint8_t *dst, uint32_t v) {
    dst[0] = static_cast<uint8_t>(v & 0xFF);
    dst[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
    dst[2] = static_cast<uint8_t>((v >> 16) & 0xFF);
    dst[3] = static_cast<uint8_t>((v >> 24) & 0xFF);
}

void putU16LE(uint8_t *dst, uint16_t v) {
    dst[0] = static_cast<uint8_t>(v & 0xFF);
    dst[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
}

}  // namespace

WavHeaderBytes buildWavHeader(uint32_t sampleRate, uint16_t channelCount, uint32_t dataBytes) {
    WavHeaderBytes h{};

    const uint16_t blockAlign = static_cast<uint16_t>(channelCount * (kBitsPerSample / 8));
    const uint32_t byteRate = sampleRate * blockAlign;
    const uint32_t riffChunkSize = 36 + dataBytes;  // 36 = header size - 8 ("RIFF" + size field)

    std::memcpy(&h[0], "RIFF", 4);
    putU32LE(&h[4], riffChunkSize);
    std::memcpy(&h[8], "WAVE", 4);

    std::memcpy(&h[12], "fmt ", 4);
    putU32LE(&h[16], 16);  // fmt chunk size for PCM
    putU16LE(&h[20], kPcmFormat);
    putU16LE(&h[22], channelCount);
    putU32LE(&h[24], sampleRate);
    putU32LE(&h[28], byteRate);
    putU16LE(&h[32], blockAlign);
    putU16LE(&h[34], kBitsPerSample);

    std::memcpy(&h[36], "data", 4);
    putU32LE(&h[40], dataBytes);

    return h;
}

int16_t floatToInt16(float sample) {
    float scaled = sample * 32768.0f;
    if (scaled > kInt16Max) scaled = kInt16Max;
    if (scaled < kInt16Min) scaled = kInt16Min;
    return static_cast<int16_t>(scaled);
}

WavFileWriter::~WavFileWriter() {
    if (mFile != nullptr) {
        std::fclose(mFile);
        mFile = nullptr;
    }
}

bool WavFileWriter::open(const std::string &path, uint32_t sampleRate, uint16_t channelCount) {
    if (mFile != nullptr) {
        return false;  // already open
    }

    std::FILE *f = std::fopen(path.c_str(), "wb");
    if (f == nullptr) {
        return false;
    }

    // Placeholder header (dataBytes = 0) so the file is a structurally valid,
    // empty WAV even if the process dies before finalize() patches it.
    const WavHeaderBytes placeholder = buildWavHeader(sampleRate, channelCount, 0);
    if (std::fwrite(placeholder.data(), 1, placeholder.size(), f) != placeholder.size()) {
        std::fclose(f);
        std::remove(path.c_str());
        return false;
    }

    mFile = f;
    mPath = path;
    mSampleRate = sampleRate;
    mChannelCount = channelCount;
    mSampleCount = 0;
    return true;
}

bool WavFileWriter::appendFloatSamples(const float *samples, size_t count) {
    if (mFile == nullptr || count == 0) {
        return mFile != nullptr;
    }

    // Converted on this (writer) thread only — never the audio callback.
    static thread_local int16_t scratch[4096];
    size_t written = 0;
    while (written < count) {
        const size_t chunk = std::min(count - written, sizeof(scratch) / sizeof(scratch[0]));
        for (size_t i = 0; i < chunk; ++i) {
            scratch[i] = floatToInt16(samples[written + i]);
        }
        if (std::fwrite(scratch, sizeof(int16_t), chunk, mFile) != chunk) {
            return false;
        }
        written += chunk;
    }

    mSampleCount += count;
    return true;
}

bool WavFileWriter::finalize() {
    if (mFile == nullptr) {
        return false;
    }

    const uint32_t dataBytes = static_cast<uint32_t>(mSampleCount * sizeof(int16_t));
    const WavHeaderBytes header = buildWavHeader(mSampleRate, mChannelCount, dataBytes);

    bool ok = std::fflush(mFile) == 0;
    ok = ok && (std::fseek(mFile, 0, SEEK_SET) == 0);
    ok = ok && (std::fwrite(header.data(), 1, header.size(), mFile) == header.size());
    ok = ok && (std::fflush(mFile) == 0);

    std::fclose(mFile);
    mFile = nullptr;
    return ok;
}

void WavFileWriter::abortAndDelete() {
    if (mFile != nullptr) {
        std::fclose(mFile);
        mFile = nullptr;
    }
    if (!mPath.empty()) {
        std::remove(mPath.c_str());
    }
}

}  // namespace roxstar
