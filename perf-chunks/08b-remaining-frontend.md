# Section 8/10 frontend changes — implemented

This file previously listed remaining UI work. It is now implemented; keep it as the manual test
checklist.

## What changed
- `ui/AlarmScreen.kt`: minute ticker and `nextRingAlarm` live in the banner; drawer members are
  keyed LazyColumn items; fade and drawer share `AlarmMemberRow` (220 ms animation preserved).
- `ui/SettingsScreen.kt`: slider transient value lives in `SnapIntervalSetting`.
- `ai/EventRecurrencePlanner.kt`: one planner/validator shared by the calendar editor and the AI
  proposal editor; calendar requires non-empty weekly days, the AI editor also requires the
  event's weekday.
- `ui/CalendarScreen.kt`, `ui/AiProposalEditors.kt`: recurrence plan memoized by
  date/type/weekdays/until, and the same plan feeds the occurrence count and validation.
- `ui/AiScreen.kt`: `AiInputBar` owns its text and `canSend`; the ViewModel no longer holds the
  draft. The bar resets when the open conversation changes (`inputResetKey`).

## Manual UI checks
1. Calendar → add event → repeat: weekly with the event weekday deselected still blocks save;
   clearing all weekdays blocks save; until <= date shows the until error; a too-far daily range
   shows the size error; typing title/note does not change the series size line.
2. AI screen: type text → send enables; clear → disables; attach image with empty text → send
   enables; send clears the box; switching sessions/new chat clears the draft; empty-state example
   chips still send.
3. Alarm banner countdown refreshes at the minute boundary; drawer expands/collapses with member
   rows; settings slider drags smoothly and persists on release.
