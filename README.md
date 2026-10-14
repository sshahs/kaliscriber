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

**Listening and talking**
- Read aloud: the meeting summary, any Ask AI answer, or a note.
- **Hands-free voice Q&A:** tap the voice button in *Ask AI*, ask your question out loud, and hear the answer. It listens again after each answer and stops when you go quiet.
- **Audio briefing:** a one-to-two minute spoken catch-up of the meeting, saved as an audio file you can play or share with someone who missed it.
- Voices: the phone's built-in offline voice, or ElevenLabs.

## Speech-to-text: on-device by default

Transcription runs **on the phone** with [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), so audio never leaves the device. Only text goes to the AI model you pick. Download a model once in **Settings → On-device speech**:

| Model | Languages | Download |
|---|---|---|
| Parakeet TDT 0.6B v3 (recommended) | 25 European languages incl. English | 465 MB |
| Parakeet TDT 0.6B v2 | English | 460 MB |
| Whisper Base / Tiny / Small | 99 languages | 198 / 111 / 610 MB |
| SenseVoice | Chinese, Cantonese, English, Japanese, Korean | 155 MB |

Silero VAD (1 MB) splits audio into utterances. The optional **speaker detection pack** (pyannote segmentation plus a 3D-Speaker embedding model, 45 MB) tells voices apart on-device when a meeting ends.

You can switch transcription to **ElevenLabs Scribe** (with speaker labels) or any of the cloud options below instead.

## Supported AI services (BYOK)

| Provider | Chat / insights | Transcription | Voice |
|---|---|---|---|
| **On-device (sherpa-onnx)** | | ✓ default, with speaker detection | phone voice |
| **Command Code** | ✓ with **zero data retention** switch | | |
| **ElevenLabs** | | ✓ Scribe with speaker labels | ✓ |
| OpenAI | ✓ | ✓ (`gpt-4o-transcribe-diarize` labels speakers) | |
| Anthropic Claude | ✓ | | |
| Google Gemini | ✓ (OpenAI-compatible endpoint) | | |
| **Ollama Cloud** (ollama.com, not local) | ✓ native `/api/chat` with your API key | | |
| Groq, Mistral | ✓ | ✓ | |
| OpenRouter, DeepSeek | ✓ | | |
| Deepgram, AssemblyAI | | ✓ with speaker labels | |
| Custom OpenAI-compatible server | ✓ | ✓ | |

**Command Code** uses the Provider API at `https://api.commandcode.ai/provider/v1`, which needs a plan with API access. Claude models go over the Anthropic Messages wire and all other models over Chat Completions. **ZDR is on by default**: every request carries `x-cmd-zdr: 1`, so Command Code only routes to upstreams that retain nothing, and a model without a ZDR route fails with a clear message rather than silently falling back. Turn it off per provider in Settings.

Pick the services for chat, transcription and voice in **Settings**. Every provider's base URL and model are editable. *List models* and *List voices* fetch what your key can use. API keys are encrypted with the Android Keystore and only sent to their own provider. Recordings, transcripts and notes stay on the device, and backups are disabled.

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

Requirements: JDK 17+ and the Android SDK (API 35). The first build downloads the sherpa-onnx AAR (50 MB, checksum-verified) into `app/libs/`.

```bash
./gradlew :core:test            # unit tests for the AI/pipeline layer
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions builds the debug APK on every push and uploads it as an artifact.

Minimum Android version: 8.0 (API 26).
