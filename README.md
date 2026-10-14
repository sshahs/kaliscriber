# KaliScriber

An Android AI note taker, meeting transcriber and summariser that runs on **your own AI keys (BYOK)**.

## Features

**Meetings**
- Record meetings in a foreground service (keeps going with the screen off) with a **live transcript** that updates every few seconds.
- **Speaker detection.** When the app can't tell who is speaking, it gives that speaker a **random placeholder name** (e.g. *Amber Falcon*). You can rename speakers at any time, accept AI name suggestions ("AI thinks this is Priya, who introduced herself at 00:12"), merge two speakers, or move selected lines to another or a new speaker.
- When recording stops, the whole meeting is re-transcribed with a speaker-aware model (if your provider has one) for consistent speakers. If it doesn't, the AI model separates speakers from the text.
- **Insights:** summary, action items (with owner, due date and priority, which you can tick off), key points, decisions, highlights (tap to play that moment), topics, risks, next steps and tone.
- **Ask AI** about the meeting, or about a part of it: long-press lines in the transcript, then tap *Ask AI*. Answers cite timestamps.
- **Gaps & questions:** the AI finds what's missing (tasks without owners or deadlines, vague references, unanswered questions…) and asks *you* questions. Your answers are stored with the meeting and fed back into the summary and Ask AI.
- Play the recording with the transcript following along, edit lines, import existing audio files, share as Markdown, or save as a note.

**Notes**
- Type or dictate notes, then have AI tidy them up, summarise them, extract action items, find gaps and questions, suggest a title, or follow your own instruction.

## Supported AI services (BYOK)

| Provider | Chat / insights | Transcription | Speaker labels |
|---|---|---|---|
| OpenAI | ✓ | ✓ | ✓ with `gpt-4o-transcribe-diarize` |
| Anthropic Claude | ✓ | | |
| Google Gemini | ✓ (OpenAI-compatible endpoint) | | |
| **Ollama Cloud** (ollama.com, not local) | ✓ native `/api/chat` with your API key | | |
| Groq | ✓ | ✓ Whisper | AI separation |
| OpenRouter | ✓ | | |
| Mistral | ✓ | ✓ Voxtral | AI separation |
| DeepSeek | ✓ | | |
| Deepgram | | ✓ | ✓ |
| AssemblyAI | | ✓ | ✓ |
| Custom OpenAI-compatible server | ✓ | ✓ | depends on model |

Pick one service for chat and one for transcription in **Settings**. Every provider's base URL and model are editable, and *List models* fetches what your key can use. Ollama Cloud defaults to `https://ollama.com` with `gpt-oss:120b`. Point the base URL at any remote Ollama server to use that instead.

API keys are encrypted with the Android Keystore and are only sent to the provider they belong to. Recordings, transcripts and notes stay on the device (`files/`), and backups are disabled.

## How it works

```
mic ─► AudioRecord (16 kHz PCM) ─┬─► AAC encoder ─► recording.m4a ─► final diarization pass ─┐
                                 └─► 30 s WAV chunks ─► live transcription ──────────────────┤
                                                                                             ▼
             speaker placeholders ◄─ SpeakerAssigner ◄─ transcript segments ─► MeetingPipeline
                                                                                             │
             AI speaker separation · name suggestions · insights · gaps & questions · Q&A ◄─┘
```

- `core/`: a plain Kotlin/JVM module with all provider clients (OkHttp + kotlinx.serialization), prompts, speaker logic, the meeting pipeline and JSON file storage. It is unit-tested with MockWebServer.
- `app/`: the Android app in Jetpack Compose and Material 3: recording service and audio engine, Keystore secret storage, and the screens.

## Build

Requirements: JDK 17+ and the Android SDK (API 35).

```bash
./gradlew :core:test            # unit tests for the AI/pipeline layer
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions builds the debug APK on every push and uploads it as an artifact.

Minimum Android version: 8.0 (API 26).
