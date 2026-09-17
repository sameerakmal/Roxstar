#ifndef ROXSTAR_WAV_READER_H
#define ROXSTAR_WAV_READER_H

#include <cstdint>
#include <string>
#include <vector>

namespace roxstar {

/**
 * Loads a mono 16-bit PCM WAV file fully into memory as float samples in
 * [-1, 1] — the inverse of WavFileWriter's conversion. Blocking file I/O;
 * intended for the JNI/UI thread only, never the audio callback.
 *
 * Rejects anything that isn't RIFF/WAVE PCM, 16-bit, mono. Chunks are walked
 * by ID rather than assumed at fixed offsets, so files with extra chunks
 * (e.g. a LIST chunk) between "fmt " and "data" still parse correctly.
 */
class WavReader {
public:
    /** Parses and loads `path`. Returns false and leaves this reader empty on any failure. */
    bool load(const std::string &path);

    /** Releases the loaded samples without needing a fresh WavReader instance. */
    void reset();

    bool isLoaded() const { return mLoaded; }
    int32_t sampleRate() const { return mSampleRate; }
    int32_t channelCount() const { return mChannelCount; }
    int64_t frameCount() const { return mFrameCount; }

    /** Mono samples, frameCount() entries. Valid only while isLoaded(). */
    const float *samples() const { return mSamples.data(); }

private:
    std::vector<float> mSamples;
    int32_t mSampleRate = 0;
    int32_t mChannelCount = 0;
    int64_t mFrameCount = 0;
    bool mLoaded = false;
};

}  // namespace roxstar

#endif  // ROXSTAR_WAV_READER_H
