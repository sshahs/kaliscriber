# KaliScriber

An Android AI note taker, meeting transcriber and summariser that runs on **your own AI keys (BYOK)**.

## Features

**Getting around**
- A **setup wizard** on first run: on-device or cloud transcription (with the model download), your AI provider and key with a test, permissions, and a test recording.
- **Bottom navigation** for Meetings, Notes, Tasks and People. Meetings are grouped by day and can be filtered by person, summary format or needing attention.
- **Undo instead of confirmations**: swipe left to delete a meeting or note; deleting a recording or merging speakers can be undone for a few seconds too.
- **Errors point to the fix** (the right Settings page, or Try again), and a notification says when a meeting's notes are ready.
- **Settings** opens on short pages with a search box.
- **Light or dark** (or follow the phone), with or without wallpaper colours (Settings → Appearance). Screen readers get labelled controls and headings.

**Meetings**
- Record meetings in a foreground service (keeps going with the screen off) with a **live transcript** that updates every few seconds.
- **Speaker detection.** When the app can't tell who is speaking, it gives that speaker a **random placeholder name** (e.g. *Amber Falcon*). You can rename speakers at any time, accept AI name suggestions ("AI thinks this is Priya, who introduced herself at 00:12"), merge two speakers, or move selected lines to another or a new speaker.
- When recording stops, the whole meeting is re-transcribed with a speaker-aware model (if your provider has one) for consistent speakers. If it doesn't, the AI model separates speakers from the text.
- **Insights:** summary, action items (with owner, due date and priority, which you can tick off), key points, decisions, highlights (tap to play that moment), topics, risks, next steps and tone.
- **Summary formats** for 1:1s, stand-ups, sales calls, interviews and lectures add sections that matter for that kind of meeting (e.g. done, next and blockers per person at a stand-up). The format is guessed from the meeting or calendar title and can be changed on the summary.
- **Your own summary formats** (Settings → Summary formats): name a format, say what kind of meeting it is for and list its sections, optionally starting from a built-in one. Give it title words ("tickets, support review") and matching meetings use it by themselves.
- **Agenda:** give a meeting an agenda (on its Summary tab, or in its schedule) and the notes follow it, marking each item covered or not with what was said about it.
- **Since last time:** a meeting with the same title (dates aside) or calendar event as an earlier one shows what that meeting decided and which of its action items are still open or done, with a link back to it.
- **Talk time:** each person's share of the talking, turns, questions and longest stretch.
- **Share a clip:** the share button on a highlight or bookmark, or *Share clip* on selected transcript lines, sends that stretch of audio (a small M4A) with what was said in it.
- **Ask AI** about the meeting, or about a part of it: long-press lines in the transcript, then tap *Ask AI*. Answers cite timestamps and appear as the AI writes them; *Stop* ends an answer early and keeps what arrived.
- **Find in transcript:** highlight matches, step through them, or show one speaker's lines.
- **Search all meetings:** the search box on the home screen (which also asks the AI when you type a question) looks through titles, people, summaries, action items and every transcript line, with word forms matched ("price" finds "pricing"). Tap a result to open the transcript at that line.
- **Ask all meetings:** ask a question about everything you've recorded ("What did we decide about pricing last month?"). The app finds the relevant meetings and passages, the AI answers from them, and each answer lists its sources. Tap a source to jump to that moment. With **smart search** (Settings, an optional 30 MB on-device model) it also finds passages by meaning, so a question about cost finds talk about prices. Filters narrow questions to the last week, month, three months or year, to one person and to one summary format.
- **Weekly recap** (the calendar button on the home screen): the AI's account of a week of meetings, written from their notes (what happened, decisions, themes, risks and what to follow up), with the week's open action items by person and what's due next week. Share it as Markdown, have it read aloud, or get it as a notification on Friday afternoons (Settings → Reminders).
- **Tasks:** every action item from every meeting in one list, grouped into overdue, due today, next 7 days, later and no date. Filter by person, tick items off, or edit the task, owner and due date. Edits and ticks are kept when a summary is regenerated. An optional daily reminder (Settings → Action item reminders) lists what's overdue or due that day.
- **People:** a page per person from your meetings (named speakers, action item owners and saved voices), with their open tasks, notable quotes, the meetings they were in and how long they spoke, plus one tap to ask the AI about them. First names are matched to full names ("Priya" → "Priya Sharma").
- **Glossary:** names, products and jargon that speech recognition gets wrong (Settings → Glossary, one per line). The AI spells them correctly in summaries and answers, and after each meeting it fixes misheard versions in the transcript (also available from a meeting's menu). With an on-device Parakeet model, the recogniser also listens out for them while you record.
- **Gaps & questions:** the AI finds what's missing (tasks without owners or deadlines, vague references, unanswered questions…) and asks *you* questions. Your answers are stored with the meeting and fed back into the summary and Ask AI.
- Play the recording with the transcript following along, edit lines, import existing audio files, share as Markdown, or save as a note.
- **Share to KaliScriber:** share a recording, voice note or video from another app (or open an audio file with KaliScriber) and it becomes a meeting, transcribed and summarised in the background. Only the sound of a video is kept.
- **Import transcripts** from Zoom, Teams, Google Meet, Otter and others (WebVTT, SRT, Word or text files) with the import button, Share or Open with. Speakers, times, title and date are read from the file, and the meeting gets the same summary, action items, search and Ask as a recording.
- **Meeting prep:** with a CalDAV calendar set up, Home shows the meeting in progress or coming up next, who is invited, what is still open with them and your last meeting with them, plus *Prepare* and *Record* buttons.
- **Processing details** (meeting menu): each processing step with how long it took, and how long the meeting took to be ready after recording stopped.
- **Quick Settings tile and home-screen widget** to start recording in one tap.
- **AI usage & cost** (Settings): tokens used this month by model and, once you enter prices, the cost; each meeting's processing details list what its requests used.
- **Storage** (Settings): what recordings, meetings, models and the search index take up, and *Keep recordings* (30 days to a year) to delete older recordings while keeping transcripts, summaries and notes. A meeting's menu can delete just its recording.
- **Backup & restore** (Settings): meetings, transcripts, notes, saved voices and settings (optionally recordings) in one password-encrypted file (AES-256-GCM). API keys are never included. Restoring only adds what the phone doesn't have.
- **Automatic backups** (Settings → Data & storage): the same encrypted backup made every day or week into a folder you choose (for example one synced with Google Drive or Nextcloud), keeping the newest few. Backups you make by hand are never deleted.

**Speakers**
- **Voice memory:** name a speaker once and the app recognises their voice in later meetings. Voiceprints are computed and stored only on the phone (Settings → Voice memory).
- **How many people?** Set the number of participants while recording or afterwards, then tap *Re-detect speakers* for more accurate speaker separation.

**During a meeting**
- *Ask AI about the meeting so far* from the recording screen, e.g. "Catch me up on the last five minutes" or "What should I clarify right now?". Recording continues in the background.
- **Speakers named as you go:** every ten minutes the live transcript is labelled with who is speaking, and saved voices are named straight away. Names you give during the meeting are kept.
- **Bookmarks:** tap *Mark* (or *Bookmark* in the notification, or press play/pause on your headphones) when something important is said, optionally with a note. Marked moments get extra weight in the notes and summary.
- **Schedule tab:** record meetings at set times, calendar or not. Each schedule has a name, a date and a start and end time; it can repeat every day, on weekdays, every week or on chosen days, and sets the notes format (built in or your own), how many people take part, an optional reminder beforehand and an agenda. Repeats can end on a date, schedules can be paused one at a time or all together until a date (for a holiday), and duplicated. The tab lists everything coming up in the week (calendar meetings too, when auto-record is on), each of which can be skipped. A home-screen widget shows the next recording with Skip and Record now.
- **Auto-record calendar meetings** (Settings → Recording & notes): recording starts when a meeting on your calendar begins and stops when it ends. It reads the phone's calendars (Google, Exchange and others) and/or a CalDAV calendar, records every timed event or only those with other people invited, and lets you skip the next one. Meetings joined late still start; back-to-back meetings each get their own recording. On Android 11 and later, recording can only start by itself in the background if KaliScriber may *display over other apps* (or, on the lock screen, show full-screen notifications); otherwise a notification asks you to tap *Record*.
- **Calendar:** a recording started during an event in your calendar (CalDAV or, if allowed, the phone's) takes its title and number of invitees, and the invitees' names help the AI name speakers.
- **Interruptions:** when a phone call or another app takes the microphone, the recording screen and notification say so, and the gap is marked in the transcript and noted for the AI, so nothing missing is guessed at.
- **Cleaner audio:** noise suppression and automatic volume (on by default), and a choice of microphone: the phone, a wired or USB headset, or a Bluetooth headset.

**Export** (the share icon on a meeting)
- **Email:** the notes as plain text to your default recipients.
- **Google Docs:** opens the notes in the Google Docs app as a Word file. One-tap upload to Drive is optional (see below).
- **Notion:** creates a page under a parent page you choose, with headings, bullets and to-dos.
- **AFFiNE:** creates a doc through the workspace MCP endpoint. If your server doesn't allow writing yet, the notes are copied and AFFiNE is opened so you can paste them.
- **Baikal / CalDAV:** adds the meeting as a calendar event and each action item as a task (with due date and priority). Exporting again updates the same items, and from then on tasks stay in step both ways: tick or edit an action item here and its task follows, tick or edit the task in your calendar or tasks app and the action item follows.
- **Markdown folder** (Settings → Export destinations): pick a folder, such as an Obsidian vault, and every finished meeting gets a Markdown note there with properties (date, people, format, open actions, tags), the notes and optionally the transcript. Notes follow the meeting: rewritten when it changes, renamed with it, removed when it's deleted.

**Notes**
- Type or dictate notes, then have AI tidy them up, summarise them, extract action items, find gaps and questions, suggest a title, or follow your own instruction.

**Listening and talking**
- Read aloud: the meeting summary, any Ask AI answer, or a note.
- **Hands-free voice Q&A:** tap the voice button in *Ask AI*, ask your question out loud, and hear the answer, spoken sentence by sentence as it is written. It listens again after each answer and stops when you go quiet.
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

Silero VAD (1 MB) splits audio into utterances. The **speaker detection pack** (pyannote segmentation plus the NeMo TitaNet small speaker embedding model, 47 MB) tells voices apart on-device. It downloads together with a speech model, or on demand when a meeting ends without it. Recordings are processed in 10-minute blocks; every speaker found gets a voiceprint, and voiceprints are merged across the meeting, which matches people between blocks and rejoins one person the diarizer split in two. Lines are split at the word where the speaker changes. Speaker detection and other long jobs run under a foreground service, so they finish with the app in the background.

You can switch transcription to **ElevenLabs Scribe** (with speaker labels) or any of the cloud options below instead.

## Speed

Most of the work happens while you are still recording, so results arrive soon after you stop:

- **Speaker detection during the meeting.** Each ten minutes of audio is diarized on the phone as soon as it is recorded; when you stop, only the last stretch is left, and the blocks are joined by voiceprint. TitaNet small computes voiceprints about a third faster than the ERes2Net model used before, with the same accuracy on our tests.
- **Streaming answers.** Ask and Ask all meetings show answers as they are written.
- **Running notes.** Every ten minutes the AI writes short notes on what was said. The summary of a long meeting starts from those notes plus the last few minutes of transcript, so it is ready much sooner. (Regenerating the summary later reads the full transcript.)
- **A quick model for background tasks** (Settings → Active services): running notes, speaker names, glossary fixes and search words can use a smaller, cheaper model while summaries and answers keep the main one.
- **Prompt caching.** Every request about a meeting starts with the same text (rules, glossary, meeting), with the task last, so providers that cache prompts (DeepSeek, OpenAI, Gemini, and Claude with explicit cache markers) answer follow-up requests faster and cheaper.
- **Light storage.** Transcripts stay on disk until a meeting is opened (only a short summary of each is kept in memory), and search and Ask all meetings read an on-device SQLite full-text index, so hundreds of meetings start and search quickly. Progress updates stay in memory instead of rewriting the meeting file.
- **Optimised release build** (R8).

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

## Download

Every push to `main` publishes signed APKs on the [Releases page](https://github.com/sshahs/kaliscriber/releases): `arm64-v8a` for almost every current phone (about 25 MB), `armeabi-v7a` for older 32-bit phones, `x86_64` for emulators, and a `universal` APK. Open one on your phone to install (Android 8.0+). Every release is signed with the same permanent key, so newer builds install as updates.

The key comes from the `KALISCRIBER_KEYSTORE_BASE64` (a base64-encoded keystore with alias `kaliscriber`) and `KALISCRIBER_KEYSTORE_PASSWORD` repository secrets, and CI checks each APK against the certificate in `.github/signing-cert-sha256` before publishing it. Releases can't be published without the secrets; plain builds without them use a temporary key and aren't published.

### Export setup

| Target | What you need |
|---|---|
| Notion | An internal integration secret (notion.so/profile/integrations), and the parent page connected to that integration |
| AFFiNE | Workspace id and a read-write MCP token (workspace settings → Integrations → MCP Server) |
| Baikal / CalDAV | Calendar URL such as `https://<server>/dav.php/calendars/<user>/<calendar>/`, plus username and password (Basic and Digest auth are supported) |
| Google Docs upload | Optional: an Android OAuth client for package `com.kaliscriber.app` with the SHA-1 shown in Settings, and the Drive API enabled. Without it, notes open in Google Docs as a Word file |

## Building on Gitea

`.gitea/workflows/android.yml` builds the app on a Gitea instance with [act_runner](https://docs.gitea.com/usage/actions/act-runner), for example on a pull mirror of this GitHub repository. Each mirror sync that brings new commits runs the tests and builds the APKs, which you can download from the run's artifacts. Gitea uses `.gitea/workflows` and ignores `.github/workflows` when that directory exists.

Setting it up:

1. **Enable Actions** on the Gitea repository (Settings → Repository → Actions), with Actions enabled on the instance.
2. **A runner** registered for the repository, owner or instance, with an `ubuntu-latest` label pointing at an image with Node.js, such as `ubuntu-latest:docker://gitea/runner-images:ubuntu-latest` (act_runner's default). The workflow installs Java 17 and the Android SDK itself. A build needs about 4 GB of memory and internet access to Maven Central, Google's Maven repository and GitHub.
3. **The same signing secrets as on GitHub**, `KALISCRIBER_KEYSTORE_BASE64` and `KALISCRIBER_KEYSTORE_PASSWORD`, added under the Gitea repository's Settings → Actions → Secrets. Without them builds are signed with a throwaway key and nothing is published; APKs signed with a different key wouldn't install as updates. Every APK is checked against `.github/signing-cert-sha256`.

**Releases on Gitea** are attached to the tags that arrive from GitHub: when the mirror brings a `v<version>` tag (an official release), the Gitea build of that tag publishes a release on it in the Gitea repository, with the APKs and a link to the Gitea commit and run. Official releases use the notes in `.github/release-notes/`. Gitea doesn't create tags of its own: on a pull mirror a tag made locally is deleted by the next sync. So a build started by hand, or from a branch, produces artifacts but no release.

## Build

Requirements: JDK 17+ and the Android SDK (API 35). The first build downloads the sherpa-onnx AAR (50 MB, checksum-verified) into `app/libs/`.

```bash
./gradlew :core:test            # unit tests for the AI/pipeline layer
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions builds the debug APK on every push and uploads it as an artifact.

Minimum Android version: 8.0 (API 26).
