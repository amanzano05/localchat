# Voice architecture — LocalChat 0.5.0

Everything here runs on the phone. No request leaves the device, there is no API key anywhere in
the voice path, and the app works in airplane mode. That is not a privacy slogan; it is a
constraint that decided most of the choices below.

```
   mic (AudioRecord, 16 kHz mono PCM16)
        │
        ▼
   VoiceRecorder ──────────────► RMS level ──► the eight-bar meter in the composer
        │  float32 [-1, 1]
        ▼
   SttEngine  (sherpa-onnx · Whisper tiny/base, int8, CPU)
        │  text
        ▼
   LlmEngine  (LiteRT-LM · Gemma, on-device, streaming)
        │  tokens
        ├──────────────► the chat transcript
        ▼
   TtsEngine  (sherpa-onnx · Piper VITS, espeak-ng phonemes)
        │  PCM16, streamed chunk by chunk into an AudioTrack
        ▼
   speaker
```

## Why sherpa-onnx and not whisper.cpp + piper wired by hand

`sherpa-onnx` is one Apache-2.0 AAR (~50 MB, arm64-v8a) that contains Whisper, Piper/VITS, Silero
VAD and ONNX Runtime. Its Kotlin API is the same three calls upstream whisper.cpp and Piper would
give us — `acceptWaveform` → `decode` → `getResult`, and `generateWithCallback` — but with the JNI
layer, the ONNX Runtime build, the feature extraction and the phonemiser already done and tested.

Writing that glue by hand means: a CMake build of whisper.cpp for four ABIs, a separate ONNX Runtime
Android integration for Piper, and an espeak-ng NDK build for phonemes — the three most likely
places to lose a week, for zero behavioural difference. The models are the same models either way:
Whisper weights, Piper voices, MIT/Apache licences.

## Components

| File | Responsibility |
| --- | --- |
| `voice/VoiceRepository.kt` | Model catalog, download (resumable per file, sequential), install checks, and the espeak-ng data copy out of assets. |
| `voice/VoiceRecorder.kt` | `AudioRecord` capture, `VOICE_RECOGNITION` source, RMS level, 60 s ceiling, float32 output. |
| `voice/SttEngine.kt` | Whisper through sherpa-onnx. Lazy load, stays warm, one utterance in → one line out. |
| `voice/TtsEngine.kt` | Piper through sherpa-onnx via `generate()` (never the callback variant). Sentence chunks, a writer thread that owns the `AudioTrack`, `stop()` as the barge-in, everything logged. |
| `voice/VoiceController.kt` | The conductor: state machine, language, downloads, speak-replies preference, markdown→speech cleanup. |
| `ChatViewModel.kt` | Owns the controller, sends the transcript as a message, speaks finished answers. |
| `ChatScreen.kt` | Mic button, level meter, status strip, `Voice` sheet (language, speak replies, downloads). |

## Models, and the trade we made

| Piece | Model | Size | Why |
| --- | --- | --- | --- |
| Speech → text | Whisper **tiny**, multilingual, int8 | 99 MB | One pair of files covers **both** English and Spanish — the language is a parameter, not a second download. |
| (upgrade path) | Whisper **base**, int8 | 153 MB | Better with names and accents; drop-in, same code path. |
| Voice (en) | `en_US-amy-low` Piper | 60 MB | Low-quality tier is the right trade for a phone: faster than real time on CPU. |
| Voice (es) | `es_ES-sharvard-medium` Piper | 73 MB | Medium tier, because a Spanish voice is what the family will actually use. |
| Phonemes | espeak-ng-data | 18 MB | Ships in the APK (assets, copied to files on first use): no download, no failure mode. |

Downloads are **plain HTTPS files** from Hugging Face — no tarballs, so the app needs no
decompression code and no extra dependency. If a download is interrupted, each file that is already
complete is skipped on the next attempt.

## Threading and memory

- Capture: one daemon thread, `ArrayList<Short>` guarded by a lock; the UI reads only the level.
- Whisper: `numThreads = 4`, called from `Dispatchers.IO`. The recogniser and the voice are loaded
  once and kept warm, so consecutive turns pay inference time only, not model load.
- Piper: `numThreads = 2`, streams into a `MODE_STREAM` `AudioTrack` sized ~200 ms, so speech starts
  as soon as the first chunk exists instead of after the last word is synthesised.
- Peak resident cost: Gemma (~1.9 GB, already the app's baseline) + Whisper tiny (~350 MB working)
  + Piper (~200 MB). Releasing the recorder and the `AudioTrack` after every turn keeps the
  steady state there.

## Latency budget (S25 Ultra class, CPU)

| Stage | Expected |
| --- | --- |
| Mic → end of take | user-controlled |
| Whisper tiny, 5 s utterance | 0.3–0.8 s |
| Gemma: first token | 0.3–1 s |
| Gemma: ~120-token answer | 6–12 s (streamed to the screen as it goes) |
| Piper: first audio | 0.2–0.4 s after the answer completes |
| **Perceived turn** | text appears in ~1 s, voice follows the finished answer |

Speech is deliberately **not** started mid-stream: sentence-by-sentence playback sounds like a
stutter when the model pauses to think, and it makes barge-in ambiguous. The answer completes, then
it is spoken.

## The one that cost a phone crash: never use `generateWithCallback`

First shipped version used `OfflineTts.generateWithCallback()` and streamed the chunks straight into
an `AudioTrack`. On the S25 the app **died on every attempt**, and the on-device log pinned it:

```
15:47:18  loading voice en: model=60MB dataDir=…/voice/espeak-ng-data
15:47:19  LOADED voice en @ 16000Hz (en_US-amy-low.onnx, 60MB)
15:47:19  generating 26 chars          <- last line; process gone
```

The engine was healthy — espeak, the model and the data directory were all fine (the same files
synthesise correctly on the server). What died was the *callback*: that variant makes the native
synthesiser call back up into the JVM for every chunk, and on this device that up-call takes the
process down, with no Java exception to catch and no stack trace.

The fix is structural, not defensive: **`generate()` — the variant that returns the audio instead of
pushing it** — so synthesis stays entirely inside native code and the only cross-language traffic is
a return value. Responsiveness is preserved by splitting the answer into sentence-sized chunks and
generating the next one on the IO thread while the previous one is still playing. Same latency
behaviour, no up-calls.

Two supporting rules came out of the same night:

- **Audio work never happens inside a native callback.** The writer thread owns the `AudioTrack`, and
  it is `join`ed before the track is released, so no write can ever land on a dead track.
- **Every step is logged before it is taken** (to `files/voice/voice.log`, shown in the Voice sheet).
  A crash that names its own location is worth more than any amount of theorising: this log line is
  what turned "the app dies" into a one-line fix.

## Behaviour rules that are not styling

- **A silent take never sends.** Under 0.25 s of audio, or an empty transcript, produces
  "Didn't catch that" — an invented sentence would be worse than an honest pause.
- **Talking interrupts.** Tapping the mic stops playback before recording starts; sending a message
  stops playback too. The assistant is not allowed to talk over the user.
- **Markdown is stripped before it is spoken.** Code fences, links, emoji, bullets and heading
  marks become plain speech, so the voice never reads "asterisk asterisk".
- **No wake word, no always-on listening.** The mic opens on a tap and closes on a tap. A recogniser
  that runs continuously would flatten the battery and is not what was asked for.

## Next, in order of value

1. **Silero VAD endpointing** — auto-stop the take when the user stops speaking, so the tap ends the
   *turn*, not the *sentence*. The model is already available through the same AAR.
2. **Sentence-level streaming with a speech queue** — start speaking sentence 1 while the model
   writes sentence 2, with a single stop handle. Removes the wait after long answers.
3. **Hands-free mode** — VAD in, VAD out, no taps, for driving. This is the real "voice chat" mode.
4. **A better English voice** — `en_US-amy-medium` or a `libritts` voice, if amy-low sounds thin.
5. **Echo suppression** — `AcousticEchoCanceler` around the recorder so the phone can keep listening
   while it speaks without recognising itself.
