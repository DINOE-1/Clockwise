# Performance plan execution status

Source: `clockwise-performance-optimization.md`. All code changes are in the working tree and
uncommitted.

| Plan section | Status |
| --- | --- |
| 1. Calendar visible range | done |
| 2. Batched series writes + scheduler off Main | done |
| 3. AI session persistence off Main | done |
| 4. AI request/response/context off Main | done |
| 5. Async cached chat thumbnails | done |
| 6. Coordinated reminder restoration | done |
| 7. Bulk backup writes + import byte limit | done |
| 8. Compose invalidation | done |
| 10. Shared recurrence/alarm-row logic | done |

## Code changes
- `data/EventDao.kt`, `AlarmDao.kt`, `TagDao.kt`: range Flow, bulk rows, id-scoped reference reads.
- `data/ScheduleWriter.kt`: in-memory series mutations applied with bulk statements.
- `data/BackupRepository.kt` + `data/BackupImport.kt` + `data/BackupExport.kt`: bulk import plan,
  streaming export, 16 MiB import bound.
- `ui/CalendarViewModel.kt`, `ui/CalendarScreen.kt`: visible-range derived `eventsByDay`, day-index
  helpers, memoized recurrence planning.
- `ai/AiChatSessionStore.kt`: session snapshot/codec/Room/retention owner.
- `ai/OpenAiChatClient.kt`, `ai/AiRepository.kt`, `ai/AiTools.kt`, `ai/AiContextBuilder.kt`,
  `ai/AiTokenBudget.kt`, `ai/AiToolParser.kt`, `ai/AiToolDataScope.kt`: JSON off Main, per-turn
  credentials, cached tool schema, bounded queries, token budgets.
- `ai/EventRecurrencePlanner.kt`: shared recurrence planner/validator.
- `alarm/ReminderRestorationCoordinator.kt`, `ClockwiseApp.kt`, `BootReceiver.kt`,
  `ReminderScheduler.kt`: single-flight coordinated restoration.
- `ui/ChatThumbnailCache.kt`, `ui/BoundedLruCache.kt`, `ui/AiScreen.kt`,
  `ui/AiChatViewModel.kt`: async thumbnails and input-bar-local draft state.
- `ui/AlarmScreen.kt`, `ui/SettingsScreen.kt`: banner ticker isolation, flattened drawer items,
  slider state boundary.

## Verification
- `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug`, `:app:assembleRelease`: green.
- Emulator backend checks passed: month range, 91-occurrence series create/update/delete, orphan
  cleanup, alarm drawer expansion, AI session restore, reboot restores 32 scheduled alarms/reminders,
  data intact after reboot.
- Frontend/UI acceptance is manual and belongs to the user.
