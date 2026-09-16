# LocalChat

A minimal Android chat app that runs a language model **entirely on the device** — no server, no API
keys, no network calls once the model file is downloaded.

Built on [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) (Google AI Edge), Kotlin + Jetpack
Compose.

## Features

- **Voice, fully offline** — tap the mic and talk: Whisper transcribes on the phone, the model
  answers, Piper reads the answer back. English and Spanish, no wake word, no always-on mic
- Streaming responses (Kotlin `Flow` → Compose), with a stop button
- GPU backend with automatic CPU fallback (the header shows which one loaded)
- Model manager: download Gemma 4 E2B in-app (1.9 GB) or load any `.litertlm` file you already have
- Nothing leaves the phone

## Stack

- `com.google.ai.edge.litertlm:litertlm-android:0.17.0`
- `sherpa-onnx` 1.13.8 (Apache-2.0) for speech: Whisper tiny/base int8 (STT) and Piper VITS voices
  (TTS), `arm64-v8a`
- Kotlin 2.4.20, AGP 8.7.3, Gradle 8.11.1, compileSdk 35, minSdk 28, `arm64-v8a` only
- Model: `litert-community/gemma-4-E2B-it-litert-lm` → `gemma-4-E2B-it-gpu.litertlm` (Apache-2.0, ungated)

## Build

Needs JDK 17 + Android SDK (build-tools 35.0.0, platform 35).

```bash
tools/fetch-voice-libs.sh   # once: pulls the 50 MB native speech library into app/libs/
gradle assembleDebug       # or assembleRelease (reads keystore.properties)
```

`keystore.properties` and `keys/` are **not** in this repo — release builds are signed with a local
keystore. Debug builds work without it.

## Install the model

Either tap **Download Gemma 4 E2B** in the app, or push a file:

```bash
adb push gemma-4-E2B-it-gpu.litertlm \
  /sdcard/Android/data/fyi.amago.localchat/files/models/
```

Google AI Edge Gallery's `.task` models are **not** compatible (different runtime); `.litertlm` ones are.

## Install the voice models

Open **Voice** in the top bar and tap **Download voice models** (~160 MB): Whisper tiny covers both
languages, plus the Piper voice for the selected language. Voices then live under

```
/sdcard/Android/data/fyi.amago.localchat/files/voice/
  stt/     tiny-encoder.int8.onnx  tiny-decoder.int8.onnx  tiny-tokens.txt
  tts/en/  en_US-amy-low.onnx  tokens.txt
  tts/es/  es_ES-sharvard-medium.onnx  tokens.txt
```

so they can also be pushed with `adb` instead. The phoneme data (espeak-ng) ships inside the APK.

See [docs/VOICE-ARCHITECTURE.md](docs/VOICE-ARCHITECTURE.md) for the pipeline, the model trade-offs,
the latency budget and what comes next.
