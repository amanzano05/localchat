# LocalChat

A minimal Android chat app that runs a language model **entirely on the device** — no server, no API
keys, no network calls once the model file is downloaded.

Built on [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) (Google AI Edge), Kotlin + Jetpack
Compose.

## Features

- Streaming responses (Kotlin `Flow` → Compose), with a stop button
- GPU backend with automatic CPU fallback (the header shows which one loaded)
- Model manager: download Gemma 4 E2B in-app (1.9 GB) or load any `.litertlm` file you already have
- Nothing leaves the phone

## Stack

- `com.google.ai.edge.litertlm:litertlm-android:0.17.0`
- Kotlin 2.4.20, AGP 8.7.3, Gradle 8.11.1, compileSdk 35, minSdk 28, `arm64-v8a` only
- Model: `litert-community/gemma-4-E2B-it-litert-lm` → `gemma-4-E2B-it-gpu.litertlm` (Apache-2.0, ungated)

## Build

Needs JDK 17 + Android SDK (build-tools 35.0.0, platform 35).

```bash
gradle assembleDebug     # or assembleRelease (reads keystore.properties)
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
