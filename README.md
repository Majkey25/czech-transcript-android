# Transcript

A small Android app for Czech transcription of M4A recordings from Samsung Voice Recorder.

## How it works

1. Choose the recordings folder once.
2. The app lists M4A files newest first.
3. Missing local transcripts run sequentially on the device.
4. Open a finished item to read, select, or copy its text.

The first local transcript downloads a pinned multilingual Whisper small INT8 model (375,485,327 bytes). Later local transcripts work offline. Every downloaded model file is verified with SHA-256.

## Optional cloud versions

Advanced settings accepts API keys for:

- OpenAI `gpt-4o-transcribe`
- Google `gemini-3.7-flash`
- xAI Speech to Text (`/v1/stt`)
- Groq `whisper-large-v3`

Keys are encrypted with Android Keystore. Cloud transcription is never automatic: audio leaves the device only after the user chooses a configured provider from a recording detail. Cloud results remain separate from the local transcript.

## Privacy

- Local transcription sends nothing to a server.
- There is no account or analytics.
- The app reads only the folder granted through the Android system picker.
- Transcripts are UTF-8 text in private app storage and are excluded from backup.
- API keys never enter source code, logs, WorkManager input, or model prompts.

## Stack

- Kotlin + Jetpack Compose + Material 3
- Android Storage Access Framework
- WorkManager for durable sequential jobs
- Android MediaExtractor/MediaCodec for M4A
- [sherpa-onnx 1.13.4](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.4)
- [Whisper small multilingual INT8](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small)

## Build

Requires JDK 17 and Android SDK 36.

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Version 0.1.0 limits

- Direct `.m4a` files only; subfolders are not scanned.
- Transcription language is fixed to Czech (`cs`).
- Local transcripts include punctuation and paragraphs but no speaker diarization.
- Speech recognition is probabilistic and can contain spelling errors; verify important transcripts against the recording.
- No built-in audio player.
- Native builds include `arm64-v8a` and `x86_64`.
- Cloud uploads reject files above provider-safe direct-upload limits; automatic chunked billing is intentionally not implemented.

Source code is MIT licensed. sherpa-onnx and the model use their own Apache 2.0 terms.
