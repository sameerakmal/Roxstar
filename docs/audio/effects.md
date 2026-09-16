# Real-time audio effects

## Where they live

DSP implementations: `native-audio/src/effects/`

- `IEffect.h` / `IEffect.cpp` — the effect interface, `EffectType` enum, and the
  `createEffect()` factory + `isValidEffectType()` validator.
- `EchoEffect.h` / `.cpp`
- `ReverbEffect.h` / `.cpp`
- `PitchShiftEffect.h` / `.cpp`

`RecordingSession` (`native-audio/RecordingSession.h/.cpp`) owns the selected
effect for one recording: `setEffect()` records the choice, `start()`
constructs and `prepare()`s it once the real sample rate is known, and
`applyEffect()` runs it from the audio callback.

## Where they enter the pipeline

```
Oboe input -> AudioEngine::onAudioReady
           -> downmix to mono float
           -> RecordingSession::applyEffect   (selected effect, in place)
           -> peak level measured here        (post-effect while recording)
           -> RecordingSession::pushSamples   (ring buffer)
           -> writer thread -> PCM16 WAV
```

Peak level is measured on the *processed* signal while recording (what you
see is what got saved); outside a recording no effect is active, so it's the
raw input. This is the one behavior change to the existing pipeline —
everything else (ring buffer, writer thread, WAV format, recording lifecycle)
is untouched.

Effect selection is one JNI call, made before `startRecording()`, and is
fixed for that session — matching the existing `AudioStatus`-style API
(`AudioEngine::setEffect`, `NativeAudioBridge.nativeSetEffect` /
`AudioEngine.kt`'s `setEffect`). There is no per-buffer JNI traffic.

## Algorithms

**Echo** — single-tap feedback delay line. ~300ms delay, 0.4 feedback,
0.35 wet mix, sized from the actual sample rate at `prepare()`. Feedback < 1
means the delayed energy decays geometrically, so the output is bounded by
construction.

**Reverb** — classic Schroeder topology: 4 parallel damped comb filters into
2 series allpass filters (the same structure Freeverb popularized). Comb
delays are tuned in milliseconds and converted to samples per the actual
sample rate. All feedback coefficients are < 1 in magnitude, so it's stable
by construction; not studio-quality, but a real, working reverb.

**Pitch shift** — granular resample-in-grain + Hann-windowed overlap-add,
fixed at 1.5x upward. 1024-sample grains, 512-sample hop (50% overlap). Each
grain reads recent input history at 1.5 source-samples per output sample —
that fractional-rate read is what actually raises the pitch (the same
mechanism as speeding up tape), not a playback-speed change: grains restart
anchored to the current input position every hop, so grain-to-grain output
timing stays locked to real time even though the content within each grain
is played back at a different rate.

Limitations, stated plainly:
- Fixed one-grain (1024 samples: ~21ms at 48kHz, ~64ms at 16kHz) latency —
  every recording starts with that much silence before real content appears.
- Each grain looks back into history by up to `(grainSize-1) * ratio`
  samples, so the start of a grain is slightly stale relative to its end —
  audible as mild smearing on fast transients. Standard for a time-domain
  granular shifter; a phase vocoder avoids it at much higher CPU cost.
- No formant correction — shifted voices sound processed ("chipmunked"), not
  like a naturally higher voice.

## A bug this process caught

The pitch shifter's overlap-add bookkeeping had a real defect during
development: grain write positions were anchored to an absolute grain count
from the start of the stream rather than to the current input position, and
emission read from the accumulator based on a "region completeness" counter
that could become ready *after* emission had already passed the samples it
covered. For any callback spanning more than one grain period, this
permanently lost data and let energy accumulate unboundedly in the
overlap-add buffer. It was caught by a chunk-size-independence test (the
same total input fed through different call-size patterns must produce
identical output) before ever reaching a device, and fixed by anchoring both
the grain's read window and its write position to the current input sample
count, with output emitted at a fixed one-grain latency instead of a
separately-tracked "ready" region. See `PitchShiftEffect.h`'s class comment
for the full explanation, and `PitchShift_ArbitraryBlockSizesMatchOneLargeBlock`
/ `PitchShift_ImpulseEnergyAppearsAtTheDocumentedLatency` in
`native-audio/tests/test_pitch_shift_effect.cpp` for the tests that pin it
down (the latter specifically because periodic test signals like a sine wave
can mask a constant timing-offset bug that an impulse cannot).

## Real-time safety

Everything reachable from `AudioEngine::onAudioReady` — the downmix,
`applyEffect`, peak measurement, and `pushSamples` — runs with no heap
allocation, no mutex, no file I/O, no JNI calls, no logging, and no blocking
calls. Every effect's buffers (delay lines, comb/allpass buffers, the pitch
shifter's history/accumulator/window) are sized once in `prepare()`, which
only ever runs on the JNI thread inside `RecordingSession::start()`, before
the stream starts. `process()` only reads and writes already-sized
`std::vector`s by index — never `resize`, `assign`, `push_back`, `new`, or
`.at()` (which would add exception/bounds-check machinery). `NONE` is not a
dispatched no-op implementation; `createEffect(None)` returns `nullptr`, and
`RecordingSession::applyEffect` skips the call entirely when there's no
effect to run.
