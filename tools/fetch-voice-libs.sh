#!/usr/bin/env bash
# One-time fetch of the native speech library LocalChat's voice mode links against.
# sherpa-onnx (Apache-2.0) bundles Whisper + Piper/VITS + Silero VAD + ONNX Runtime for arm64-v8a.
set -euo pipefail

VERSION="${1:-1.13.8}"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/libs"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/sherpa-onnx-${VERSION}.aar"

mkdir -p "$DEST"
if [ -f "$DEST/sherpa-onnx-${VERSION}.aar" ]; then
  echo "already present: $DEST/sherpa-onnx-${VERSION}.aar"
  exit 0
fi

echo "downloading sherpa-onnx ${VERSION} (~50 MB)…"
curl -fSL --progress-bar -o "$DEST/sherpa-onnx-${VERSION}.aar" "$URL"
ls -la "$DEST/sherpa-onnx-${VERSION}.aar"
