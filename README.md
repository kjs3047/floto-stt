# Floto Android

Floto is an Android speech-to-text overlay app. It places a small floating STT button above other apps, records speech on tap, transcribes it with either OpenAI or local Whisper.cpp, and inserts the result into the currently focused text field through an AccessibilityService.

## What is implemented

- System overlay button with:
  - main `STT` / `REC` button,
  - small top-right `x` exit button,
  - small bottom-right `*` settings button.
- Push-to-talk flow:
  - tap `STT` to start recording,
  - tap `REC` to stop,
  - transcribed text is inserted into the focused text field.
- Settings screen:
  - OpenAI provider with `gpt-4o-transcribe`, `gpt-4o-mini-transcribe`, and `whisper-1`,
  - local Whisper.cpp provider with tiny/base/small model profiles,
  - system on-device provider using Android's built-in offline `SpeechRecognizer` when available,
  - in-app local model download,
  - local model file chooser/copy,
  - language code, prompt, and trailing-space option.
- Android permission helpers:
  - overlay permission,
  - microphone permission,
  - accessibility settings shortcut.

## Important Android constraint

Android does not allow a normal overlay app to type into arbitrary apps by itself. Floto uses an `AccessibilityService` to call `ACTION_SET_TEXT` on the currently focused editable node. If the target app blocks that action, Floto falls back to copying the transcript to the clipboard and attempts paste where possible.

An IME implementation would be another valid architecture, but for the requested floating-button behavior the AccessibilityService path is the practical system-wide approach.

## Model choices

OpenAI STT models are based on the current OpenAI speech-to-text guide:

- `gpt-4o-transcribe`: default quality option for file-based transcription.
- `gpt-4o-mini-transcribe`: lighter OpenAI option.
- `whisper-1`: legacy Whisper-backed API model.

Local Whisper.cpp STT uses GGML model files, but it is CPU-bound in this prototype and is not recommended for everyday dictation:

- `ggml-tiny.bin`: fastest Whisper.cpp option, still slower than OS on-device recognition.
- `ggml-base.bin`: more accurate than tiny, but usually too slow for live mobile dictation.
- `ggml-small.bin`: high latency on mobile CPU; keep for experiments only.

The local model profiles use the official Whisper.cpp model files hosted under the Whisper.cpp Hugging Face repo. The upstream Whisper.cpp Android README recommends tiny or base models for Android devices.

System on-device STT uses Android's built-in `SpeechRecognizer.createOnDeviceSpeechRecognizer` path. It requires Android 12 or newer and depends on the device firmware having an on-device recognition service configured. On supported Galaxy/Google devices this uses the OS-provided recognizer rather than the bundled Whisper.cpp model or OpenAI API.

Sources checked while implementing:

- [OpenAI Speech to text guide](https://developers.openai.com/api/docs/guides/speech-to-text)
- [Whisper.cpp Android example](https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android)
- [Whisper.cpp model files](https://huggingface.co/ggerganov/whisper.cpp/tree/main)

## Build

This workspace already has Android Studio, Android SDK 36, CMake 3.22.1, and NDK 28.2.13676358 installed. Build with Android Studio or from PowerShell:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :app:assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## First run

1. Install and launch Floto.
2. Grant microphone permission.
3. Open overlay permission and allow Floto to draw over other apps.
4. Open accessibility settings and enable `Floto text insertion`.
5. Pick a provider:
   - System on-device: recommended for fast local dictation; no API key or model download is needed, but the device must provide an on-device recognizer.
   - OpenAI: enter API key and choose model when accuracy matters more than offline operation.
   - Local Whisper.cpp: choose/download a GGML model only for offline experiments; it is slow on CPU.
6. Tap `Start floating button`.
7. Focus any text field in another app.
8. Tap `STT`, speak, tap `REC`, and wait for insertion.

## Security note

The prototype stores the OpenAI API key in app SharedPreferences. That is acceptable for a private local APK, but not for a distributed production app. For production, use a short-lived token flow or backend proxy so a long-lived OpenAI key is never embedded in or stored by the mobile client.

## Current limitations

- The recording interaction is push-to-talk toggle, not automatic silence detection.
- Text insertion depends on the target app exposing an editable accessibility node.
- Local Whisper.cpp loads the selected model on first use and runs on CPU in this prototype, so it can be too slow for practical dictation.
- Only `arm64-v8a` and `x86_64` ABIs are built. Galaxy S26 Ultra uses `arm64-v8a`; `x86_64` is included for emulator testing.
<!-- Orca automation test: Linear issue → PR -->
<!-- Orca automation test: Linear issue → PR #2 -->
<!-- Orca automation test: Linear issue → PR #3 -->
<!-- Orca automation test: Linear issue → PR #4 -->
<!-- Orca automation test: Linear issue → PR #9 -->
<!-- Orca automation test: Linear issue → PR #21 -->
