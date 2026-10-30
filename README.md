# Clockwise

[English](README.md) | [繁體中文](README.zh-TW.md) | [简体中文](README.zh-CN.md)

Clockwise is a local-first calendar and alarm app for Android, built with Jetpack Compose and
Material 3. It combines a month/day calendar, exact alarms with full-screen ringing, colored tags,
and an optional AI assistant that can read and change your schedule after you approve a preview.

The UI is available in English and Traditional Chinese (zh-TW). It follows the system language by
default, and you can switch languages in **Settings → Language**. The source code and documentation
are in English.

## Screenshots

A quick look at the main screens. The app is available in English and Traditional Chinese;
screenshots for both languages are kept in [`images/en`](images/en) and
[`images/zh-TW`](images/zh-TW).

| Month view | Day view | Create event |
| :---: | :---: | :---: |
| <img src="images/en/Calendar_Month.png" width="230" alt="Calendar month view"> | <img src="images/en/Calendar_Day.png" width="230" alt="Calendar day timeline"> | <img src="images/en/Create_Event.png" width="230" alt="Creating an event"> |

| Alarms | AI assistant | Tags |
| :---: | :---: | :---: |
| <img src="images/en/Alarms.png" width="230" alt="Alarm list"> | <img src="images/en/AI.png" width="230" alt="AI assistant planning a workout week"> | <img src="images/en/Tags.png" width="230" alt="Colored tags"> |

| Settings |
| :---: |
| <img src="images/en/Settings.png" width="230" alt="Settings and appearance options"> |

## Features

### Calendar

- Month and day views, with the week starting on Monday.
- Create, edit, and delete events with title, note, start/end time, tags, and an optional alarm.
- Create an event directly from the day timeline; drag the draft block to fine-tune start and end
  times. The time snaps to a configurable interval (5–60 minutes, default 15).
- Recurring events: daily, weekly on selected weekdays, or until a chosen end date.
- Event reminders: pick a preset (5, 10, or 30 minutes) or enter any custom number of minutes up
  to seven days before the start.
- Edit or delete a single occurrence, this and all following occurrences, or the whole series.
  Series edits keep each occurrence's date and only sync shared fields.

### Alarms

- One-off alarms and alarms linked to calendar events.
- Exact alarms through `AlarmManager`, with a full-screen alarm screen on the lock screen.
- Stop or snooze from the ringing screen; snooze defaults to 10 minutes.
- Optional 10-second test alarm and a reliability checklist for notification, exact-alarm,
  full-screen-intent, and battery-optimization settings.
- OEM-specific guidance for vivo/OriginOS background restrictions.
- Enabled alarms are rescheduled after a reboot or an app update.

### AI assistant

- Bring your own API key for **DeepSeek** or **Command Code (GOAT)**. Both use the
  OpenAI-compatible Chat Completions API.
- The assistant can list alarms and events, create/update/delete both, create recurring events,
  and attach alarms to events.
- Every write is shown as a preview with the affected items before anything is saved. Nothing is
  changed until you confirm.
- Conversation history screen keeps the three most recent chats; inactive chats expire after
  24 hours. A usage panel reports input, output, cached, and reasoning tokens.
- Model list discovery, manual model IDs, connection testing, and reasoning-effort selection.

### Customization and data

- A first-run guide lets new users pick a language and theme, then swipes through a tour of each
  tab drawn live from the current theme, so it always matches the chosen look. Skipping or finishing
  it is remembered.
- Language: follow system, English, or Traditional Chinese.
- Themes: follow system, light, dark, and Gruvbox Material.
- Wheel or clock time pickers, configurable timeline snap interval, and two alarm-stack animations.
- Colored tags shared by events and alarms, with a per-item tag limit.
- Import and export calendar events as iCalendar (`.ics`) files in **Settings → Data**. Export keeps
  recurring events as `RRULE`; import adds events without replacing existing ones.
- All data is stored locally in Room. No account, analytics, or Clockwise backend is involved.
- Room schemas are exported to `app/schemas/` so database migrations can be reviewed and tested.

## Screens

| Tab | Purpose |
| --- | --- |
| 行事曆 / Calendar | Month grid, day timeline, event editor, recurrence scope |
| 鬧鐘 / Alarms | Alarm list, countdowns, editor, permission/reliability checks |
| AI | Chat, change previews, saved conversations, token usage |
| 標籤 / Tags | Create and manage colored tags |
| 設定 / Settings | Language, theme, time picker, snap interval, alarm animation, AI configuration |

## Requirements

- Android Studio with support for AGP 9.4, or a command-line Android SDK setup
- JDK 17 or newer (the project targets Java 17)
- Android SDK Platform 37 and the matching build tools
- An Android device or emulator running Android 8.0 (API 26) or newer

The app does not depend on Google Play services.

## Build

Using Android Studio, open the repository root and let Gradle sync. Set the Gradle JDK to 17 or
newer.

From the command line:

```bash
export JAVA_HOME=/path/to/jdk-17-or-newer
export ANDROID_HOME=/path/to/Android/Sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"

./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

If you prefer a `local.properties` file, point it at your SDK instead of exporting `ANDROID_HOME`:

```properties
sdk.dir=/path/to/Android/Sdk
```

`local.properties` is intentionally git-ignored.

### Release build

Release builds are minified and resource-shrunk with R8. They are signed only when all four signing
values are present; without them Gradle produces an unsigned APK so the build still works without
secrets.

```bash
export KEYSTORE_FILE=/absolute/path/to/release.keystore
export KEYSTORE_PASSWORD='...'
export KEY_ALIAS='...'
export KEY_PASSWORD='...'

./gradlew :app:assembleRelease
```

`KEYSTORE_FILE` may be absolute or relative to the repository root. The same names can be supplied
as Gradle properties instead, for example in `~/.gradle/gradle.properties` or with
`-PKEYSTORE_FILE=...`; the environment is used when a property is absent. A git-ignored
`keystore.properties` file at the repository root is the last fallback, which keeps a local checkout
signed without exporting variables for every build. Never commit a keystore or its passwords
(`.gitignore` excludes `*.jks`, `*.keystore`, `*.p12`, and `keystore.properties`).

When signing is configured the APK is written to
`app/build/outputs/apk/release/app-release.apk`; otherwise it is
`app/build/outputs/apk/release/app-release-unsigned.apk`. Debug builds are signed with the same key
when one is configured, so an APK built locally can update an installed beta.

### Beta builds

GitHub Actions publishes the installable APK as the `clockwise-beta` artifact. It is a signed release
build, and every run uses the same key, so a newer beta installs over an older one. The workflow
reads the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` repository secrets:

```bash
base64 -w0 release.jks   # paste the output into the KEYSTORE_BASE64 secret
```

Without those secrets the job still builds, tests, and lints, but uploads no installable APK. Debug
APKs are not published because each runner signs them with its own throwaway key, which is what makes
an update fail with a package conflict. Beta APKs published before this signing setup used such keys,
so uninstall the app once before installing the first consistently signed build.

## Run

With a device or emulator connected:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.dinoe.clockwise/.MainActivity
```

Release builds are minified with R8; see [Release build](#release-build) for signing. Debug builds
need no signing material and are the recommended way to test locally.

## Tests

JVM unit tests cover the AI tool parser, prompt/context building, token budgeting, model catalog,
session serialization, and the OpenAI-compatible client (using MockWebServer):

```bash
./gradlew :app:testDebugUnitTest
```

There are no instrumentation tests in the project yet.

## AI setup

1. Open **設定 / Settings → AI 助手 / AI assistant**.
2. Choose DeepSeek or Command Code and paste an API key.
3. Optionally refresh the model list, or enter a model ID manually.
4. Use **測試連線 / Test connection** to verify the key and model.
5. Return to the AI tab and start a conversation.

The assistant is optional. Without a key, the rest of the app works normally.

### Privacy and security

Clockwise is local-first. Calendar events, alarms, tags, settings, AI conversations, and usage data
are stored in a Room database and DataStore on the device. There is no Clockwise account, analytics
SDK, or backend server.

- API keys are encrypted at rest with an AES-GCM key held in the Android Keystore. The Keystore key
  is non-exportable; the plaintext key exists in memory only while a request is being made.
- When you use the assistant, the chat messages, prompts, and the schedule context needed to answer
  are sent directly to the provider you selected over HTTPS, with the key in the request's
  `Authorization` header. That data is governed by the provider's privacy policy. Clockwise does not
  proxy requests or keep a server-side copy.
- Conversations and usage data are stored only in the local Room database and are not uploaded.
- Cloud backup and device-to-device migration are disabled: `allowBackup=false`, legacy full backup
  is off, and the Android 12+ (API 31) extraction rules exclude every domain. The database and the
  encrypted key blobs therefore never leave the device through Android backup. Because the Keystore
  key is non-exportable, copied ciphertext could not be decrypted elsewhere anyway.
- Deleting a conversation removes it from the local database; it does not delete anything held by
  the provider. Uninstalling the app or clearing its data permanently deletes local data.
- Remote AI providers should use HTTPS so API keys and chat content are encrypted in transit.
  Clockwise does not override Android's network-security policy; local HTTP endpoints remain subject
  to the platform's cleartext rules, and a LAN provider may need an HTTPS reverse proxy.

## Permissions

| Permission | Why it is used |
| --- | --- |
| `POST_NOTIFICATIONS` | Alarm ringing notifications; runtime permission on Android 13+ |
| `SCHEDULE_EXACT_ALARM` | Exact alarm scheduling on Android 12 and below |
| `USE_EXACT_ALARM` | Exact alarm scheduling on Android 13+ |
| `USE_FULL_SCREEN_INTENT` | Full-screen alarm on the lock screen |
| `RECEIVE_BOOT_COMPLETED` | Restore enabled alarms after a reboot |
| `WAKE_LOCK`, `VIBRATE` | Wake the device and vibrate while ringing |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the alarm ringing service alive |
| `INTERNET` | Calls to the AI provider you configure |

The app does not request location, contacts, or calendar-provider access.

## Project structure

```
app/src/main/kotlin/com/dinoe/clockwise/
├── MainActivity.kt           # Single activity host
├── ClockwiseApp.kt           # Application wiring: database, writer, repository
├── data/                     # Room entities, DAOs, recurrence, ScheduleWriter
├── alarm/                    # AlarmManager scheduling, receiver, service, notifier
├── ai/                       # Providers, client, prompt, tools, parsing, key storage
└── ui/                       # Compose screens, view models, theme, pickers
```

- **`ScheduleWriter`** is the single write path for alarms and events, used by both the UI and the
  AI assistant. This keeps AI-created items consistent with manually created ones.
- **`AppDatabase`** is the Room database (currently schema version 9). Exported schemas live in
  `app/schemas/`; commit new schema files whenever the database changes.
- **`AlarmScheduler`** owns exact/inexact scheduling fallbacks and reboot rescheduling.
- **`AiTools` / `AiToolParser`** define and validate the function-calling tools. Write tools only
  produce proposals; `ScheduleWriter` runs after user confirmation.
- **`OpenAiChatClient`** talks to any OpenAI-compatible Chat Completions endpoint.

## Contributing

Issues and pull requests are welcome. Before opening a PR:

1. Keep Kotlin source, comments, and commit messages in English.
2. Run `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug`. CI
   (`.github/workflows/android.yml`) also runs `assembleDebug`, `testDebugUnitTest`, and `lintDebug`
   on pushes and pull requests.
3. Add a Room schema file if you change the database.
4. Do not commit API keys, signing keys, `local.properties`, or build output.

## Known limitations

- The calendar is local-only; it does not sync with Google Calendar, CalDAV, or the system calendar
  provider.
- There is no home-screen widget.
- AI features depend on an external provider and its availability, pricing, and data policy.
