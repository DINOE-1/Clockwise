# Clockwise Performance and Simplification Plan

## Objective

Reduce visible UI stalls, improve calendar and AI response latency, remove unnecessary background work, and simplify duplicated implementation without changing user-facing behavior. All CPU-intensive transformations will run off the main thread; the main thread will only update UI state and render Compose content.

## 1. Bound Calendar Work to the Visible Date Range

### Problem

`ui/CalendarScreen.kt:400-417` expands every event into every covered day and sorts each day's list during composition. `data/EventDao.kt:12-16` supplies the complete event table, so every event change rebuilds an index for the entire database, including dates unrelated to the visible month. Day-view filtering also scans the full list.

### Solution

- Add range-based observation to `data/EventDao.kt`, reusing the existing date-range query semantics and including multi-day events that overlap the requested range.
- Make `ui/CalendarViewModel.kt` own the displayed month and expose a visible-range query covering the complete month grid, including leading and trailing days.
- Derive `eventsByDay` from the range result with `flowOn(Dispatchers.Default)` and expose it as lifecycle-aware state.
- Sort each day once in the ViewModel using the existing all-day/start-time/title ordering.
- Remove the full-event expansion from `MonthView` and reuse the same derived state for the selected-day view.
- Preserve immediate selected-date updates while range data loads; retain the existing empty and loaded rendering behavior.

### Files

- `app/src/main/kotlin/com/dinoe/clockwise/data/EventDao.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ui/CalendarViewModel.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ui/CalendarScreen.kt`

## 2. Batch Series Mutations and Move Scheduler Effects Off Main

### Problem

`data/ScheduleWriter.kt:361-388,465-568` performs separate event, alarm, and tag statements for every occurrence. After the transaction, `ScheduleWriter.kt:603-626` executes scheduler effects serially on the caller's dispatcher, which is Main for calendar writes. A 180-occurrence series can therefore cause hundreds of SQL statements followed by a long sequence of main-thread `AlarmManager` operations.

### Solution

- Add bulk update/insert/delete operations for event rows, alarm rows, and tag references to the DAOs.
- Build the complete series mutation in memory, validate it, then apply it through bulk operations inside the existing Room transaction.
- Preserve the existing series-ID, orphan-alarm, enabled-alarm-count, and reminder behavior.
- Execute the complete write-and-scheduler pipeline on `Dispatchers.IO`; return only the final mutation result to the ViewModel's Main context.
- Keep permission-related scheduling outcomes and automatic disabling behavior unchanged.
- Avoid parallel scheduler calls because ordered effects and aggregate result accounting must remain deterministic.

### Files

- `app/src/main/kotlin/com/dinoe/clockwise/data/EventDao.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/data/AlarmDao.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/data/TagDao.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/data/ScheduleWriter.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ui/CalendarViewModel.kt`

## 3. Move AI Session Transformation and Codec Work Off Main

### Problem

`ui/AiChatViewModel.kt:538-609` snapshots and encodes the complete retained transcript before entering `Dispatchers.IO`. Restore similarly decodes after returning to Main. Each turn rewrites the entire payload, including retained Base64 thumbnails and duplicated recent text in UI messages and completed turns.

### Solution

- Capture an immutable persistence snapshot on Main.
- Move preview creation, DTO conversion, and `AiChatSessionCodec.encode/decode` to `Dispatchers.Default`.
- Restore decoded models off Main, then publish the resulting Compose state in one Main-thread update.
- Keep `sessionMutex` around the complete read-modify-write sequence to prevent stale sessions overwriting active state.
- Introduce a dedicated session persistence component that owns snapshot conversion, codec execution, Room writes, retention cleanup, and session switching.
- Retain the current payload schema during this optimization so existing sessions remain readable.

### Files

- `app/src/main/kotlin/com/dinoe/clockwise/ui/AiChatViewModel.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ai/AiChatSessionCodec.kt`
- New focused session-persistence component under `ai/` or `data/`

## 4. Move AI Request, Response, Context, and Tool Processing Off Main

### Problem

`ai/OpenAiChatClient.kt:102-152,247-283` builds request JSON and parses responses on the caller's dispatcher. `ui/AiChatViewModel.kt` performs context construction, tool parsing, result grouping, and JSON serialization primarily on Main. Image requests amplify allocation through Base64, data URLs, JSON strings, and request bytes.

### Solution

- Serialize completion/model requests and parse responses on `Dispatchers.Default` inside the AI client.
- Build dynamic prompts, token budgets, tool ID maps, parsed proposals, and tool-result JSON on `Dispatchers.Default`.
- Cache serialization and token estimation of immutable tool definitions instead of repeating them each model round.
- Fetch the active provider configuration and decrypted credential once per turn and reuse them across tool rounds; invalidate the cached credential when configuration changes.
- Keep Compose state mutations, user-visible status, and cancellation coordination on Main.
- Preserve request cancellation and existing timeout behavior.

### Files

- `app/src/main/kotlin/com/dinoe/clockwise/ai/OpenAiChatClient.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ai/AiContextBuilder.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ai/AiRepository.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ai/AiPreferences.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ui/AiChatViewModel.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ai/AiToolParser.kt`

## 5. Decode Chat Images Asynchronously and Cache Them

### Problem

`ui/AiScreen.kt:1469-1478` performs Base64 and bitmap decoding synchronously inside composition. Work repeats when messages re-enter composition or the AI tab is recreated.

### Solution

- Replace synchronous composition-time decoding with an asynchronous image state containing loading, decoded bitmap, and failure outcomes.
- Decode thumbnails on `Dispatchers.Default` and publish the completed image to Compose.
- Cache decoded thumbnails by immutable attachment identity so LazyColumn reuse and tab changes do not decode the same image repeatedly.
- Bound the cache and release entries when their backing chat/session data is removed.
- Keep pending-image and historical-image rendering behavior unchanged.

### Files

- `app/src/main/kotlin/com/dinoe/clockwise/ui/AiScreen.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ui/AiChatViewModel.kt`
- Focused thumbnail-cache component under `ai/` or `ui/`

## 6. Limit AI Database Work to Required Rows

### Problem

`ui/AiChatViewModel.kt:732-748,907-971` loads complete event, alarm, tag, and reference datasets even though read results are capped at 30. Write parsing scans full maps and series lists. The output cap limits tokens but not database or allocation cost.

### Solution

- Add DAO queries that apply ordering, date filtering, and result limits in SQL.
- Fetch tag references only for returned event/alarm IDs using chunked `IN` queries where necessary.
- Parse tool calls first to collect referenced IDs and series IDs, then load only the rows required for validation and conflict detection.
- Replace repeated linear series/alarm scans with indexes built only from the loaded subset.
- Preserve the existing 30-result output contract, deterministic ordering, validation errors, and confirmation requirements.
- Enforce the declared per-result and aggregate tool token budgets before appending tool payloads; truncate deterministically rather than failing the whole turn after expensive queries.

### Files

- `app/src/main/kotlin/com/dinoe/clockwise/data/EventDao.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/data/AlarmDao.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/data/TagDao.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ui/AiChatViewModel.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ai/AiToolParser.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ai/AiContextBuilder.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ai/AiTokenBudget.kt`

## 7. Coordinate Reminder Restoration

### Problem

`ClockwiseApp.kt:26-30` restores every upcoming reminder on each process creation. `alarm/BootReceiver.kt:28-39` independently performs the same restoration. A process created for a covered broadcast can run both full scans concurrently, and unrelated alarm delivery can trigger unnecessary restoration work.

### Solution

- Centralize reminder restoration in one application-scoped coordinator.
- Serialize restoration with a mutex and coalesce concurrent requests into one in-flight operation.
- Run full restoration only for boot, package replacement, time/date/time-zone/locale changes, and explicit recovery paths.
- Remove unconditional full reminder restoration from routine process creation while retaining orphaned-alarm repair behavior.
- Keep receiver `goAsync()` completion tied to the coordinated operation.

### Files

- `app/src/main/kotlin/com/dinoe/clockwise/ClockwiseApp.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/alarm/BootReceiver.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/alarm/ReminderScheduler.kt`

## 8. Reduce Compose Recomposition and Non-Lazy Layout Work

### Problem

- `AlarmScreen.kt:242-250` updates top-level time state every minute, invalidating the whole screen.
- `AlarmScreen.kt:425-447,727-746` composes every drawer member inside one LazyColumn item and animates a large Column.
- `CalendarScreen.kt:1534-1543` and `AiProposalEditors.kt:363-392` recompute recurrence while unrelated fields change.
- AI draft input lives in the top-level chat ViewModel, causing broad invalidation while typing.

### Solution

- Isolate the alarm ticker and next-ring calculation in the banner composable.
- Flatten drawer members into keyed LazyColumn items, reusing the existing fade-mode row structure and animation behavior.
- Memoize recurrence plans by date/rule inputs and share one plan between draft assembly and validation.
- Move AI draft text and `canSend` derivation into the input-bar state boundary; publish text to the ViewModel only when sending or attaching content requires it.
- Keep existing LazyColumn keys and visual behavior.
- Move the settings slider's transient value into `SnapIntervalSetting` so dragging does not invalidate the full settings page.

### Files

- `app/src/main/kotlin/com/dinoe/clockwise/ui/AlarmScreen.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ui/CalendarScreen.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ui/AiProposalEditors.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ui/AiScreen.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ui/AiChatViewModel.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ui/SettingsScreen.kt`

## 9. Batch Backup Writes and Bound Import Memory

### Problem

`data/BackupRepository.kt:170-253` inserts events one at a time and updates every event again to assign series IDs; tags and references also use individual statements. Import reads and parses the entire document before enforcing event limits, and export materializes rows, JSON nodes, a string, and bytes concurrently.

### Solution

- Use bulk inserts for tags, events, alarms, and references inside the existing transaction.
- Allocate imported IDs and series mappings before insertion so the second event-update pass is unnecessary.
- Reject oversized input by byte count before reading and parsing; retain existing row-count validation afterward.
- Preserve ID remapping, validation, transaction atomicity, and post-import alarm/reminder restoration.
- Execute post-import scheduling through the coordinated background restoration path.

### Files

- `app/src/main/kotlin/com/dinoe/clockwise/data/BackupRepository.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/data/EventDao.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/data/AlarmDao.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/data/TagDao.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ui/SettingsBackupSection.kt`

## 10. Simplify Duplicated Logic Without Broad UI Abstraction

### Problem

Calendar and AI editors duplicate recurrence planning and validation. The largest files mix screen layout, editor state, validation, persistence, and tool coordination, making performance fixes harder to isolate.

### Solution

- Extract one event-draft recurrence planner/validator shared by calendar and AI proposal editors.
- Extract AI session persistence and thumbnail decoding into focused components as described above.
- Share alarm-row rendering between fade and drawer modes while flattening their list items.
- Do not introduce generic composable wrappers solely to reduce line counts.
- Keep Room entities and schema versions unchanged because no persistence-schema change is required.

### Files

- `app/src/main/kotlin/com/dinoe/clockwise/ui/CalendarScreen.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ui/AiProposalEditors.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ui/AlarmScreen.kt`
- `app/src/main/kotlin/com/dinoe/clockwise/ai/AiDraftEditor.kt`
- New focused recurrence/editor and persistence components only where listed above

## Implementation Order

1. Calendar visible-range queries and background indexing.
2. Batched series writes and background scheduler effects.
3. AI session, API JSON, context, and tool-processing dispatcher fixes.
4. Asynchronous cached chat thumbnails.
5. Bounded AI queries and coordinated reminder restoration.
6. Compose invalidation and non-lazy drawer fixes.
7. Backup bulk writes and import byte limits.
8. Shared recurrence/editor and alarm-row simplification.

Each step will retain existing behavior and tests before the next step begins.

## Verification

### Automated

- Add DAO tests or focused unit coverage for range overlap, ordering, limits, and tag-reference loading.
- Extend ScheduleWriter tests for whole-series updates, tags, reminders, alarm counts, disabled alarms, and failure outcomes.
- Extend session codec and ViewModel tests for restore, switching, cancellation, stale-write prevention, and retention.
- Add tool tests confirming equivalent results, ordering, truncation, validation, and confirmation behavior.
- Add backup round-trip tests covering recurring series and maximum accepted input boundaries.
- Run `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug`, and `:app:assembleRelease`.

### Performance Validation

- Capture baseline and optimized Macrobenchmark/Compose tracing for cold calendar display, month changes, event writes, AI session restore, and opening long chats with thumbnails.
- Record recomposition counts for alarm ticking, AI typing, recurrence editing, and settings-slider dragging.
- Compare SQL statement counts for a 180-occurrence series update and a large backup import.
- Confirm no request/response JSON processing, bitmap decoding, recurrence planning, or scheduler-effect loop executes on Main.

### User Acceptance

After installing the debug build, verify:

- Opening and changing months remains responsive with multi-day and recurring events.
- Editing or deleting an entire series returns control immediately after completion and preserves alarms/reminders.
- Sending AI text/image messages and switching sessions does not freeze scrolling or input.
- Alarm banners, drawer expansion, AI typing, recurrence editing, and slider dragging remain smooth.
- Backup import/export produces equivalent restored data and schedules.
