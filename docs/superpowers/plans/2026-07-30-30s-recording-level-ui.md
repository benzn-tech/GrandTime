# 30s Segments + Recording-Level UI Implementation Plan

**Goal:** (1) Change rolling segment length to 30s. (2) Make the Files page and Home upload status
show ONE entry per *recording* (grouped by session) instead of one per 30s segment — the segments
still exist on disk and still upload individually (backend rolling processing unchanged), the user
just no longer sees the clutter. **UI grouping only — no on-device file merge.**

## Global constraints

- Backend contract unchanged: still ~30s chunks per recording, each uploaded on finalize, keyed by
  `_sid{32hex}_c{NNNN}`. This plan does NOT merge files or change the upload payload.
- A "recording" = all `CaptureRecord` rows sharing the same `(kind, sessionId)`. Video + audio are
  segmented (group them). Photos are single (leave each as its own tile).
- No new deps. Build/test: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` then
  `./gradlew testProdDebugUnitTest` / `assembleDevDebug`. Dropbox first-run lock transient → re-run.

---

### Task 1: 30s segment length (settings in seconds)

**Files:** `core/SettingsStore.kt`, `ui/SettingsScreen.kt`, `capture/CaptureManager.kt`, `capture/AudioSegmentation.kt`.

- `SettingsStore`: replace `segmentMinutes: Int = 5` with `segmentSeconds: Int = 30`; `SEGMENT_OPTIONS = listOf(30, 60, 120, 300)`; key `intPreferencesKey("video_segment_seconds")`; `setSegmentSeconds(value)` (validate in options). Read default 30.
- `SettingsScreen`: the SEGMENT RadioDialog uses the new options; `label = { s -> if (s < 60) "${s}s" else "${s / 60} min" }`; `selected = settings.segmentSeconds`; `onSelect = setSegmentSeconds`. The SettingRow value likewise formats seconds.
- `CaptureManager`: `startSegmentTimer(seconds)` → `delay(seconds * 1000L)`; call site `startSegmentTimer(settings.segmentSeconds)`. Both `segmentBytesFor(...)` call sites pass `settings.segmentSeconds`.
- `AudioSegmentation.segmentBytesFor`: change the unit from minutes to seconds — `fun segmentBytesFor(seconds: Int, sampleRate: Int = 16000, bytesPerSample: Int = 2): Long = seconds.toLong() * sampleRate * bytesPerSample` (drop the `* 60`). Update its test in `AudioSegmentationTest` accordingly.

### Task 2: Files page — one tile per recording

**File:** `ui/FilesScreen.kt` (+ a small DAO helper if needed).

- Where the day's `dayItems` are currently mapped one-`MediaCell`-per-record, first collapse them into "recording" units:
  - Photos (`kind == "photo"`): stay one unit per record.
  - Video/audio: group by `(kind, sessionId)`; each group → ONE unit represented by its EARLIEST segment (min `segmentIndex`, fallback min `startedAt`) for the thumbnail/time; compute `totalDurationMs = sum(durationMs)` and `segmentCount = group.size`.
  - Order units within a day by the representative `startedAt` (desc, matching current).
- `MediaCell` for a recording unit: same thumbnail (earliest segment), duration overlay = `mmss(totalDurationMs)`, and if `segmentCount > 1` a small badge (e.g. "×N" or "N段") in a corner so it's clear it's a multi-part recording.
- **Tap** a unit: single-segment (photo, or a 1-segment recording) → current behavior (`playingAudio` for audio, `openFile` for video/photo). Multi-segment recording → open a `ModalBottomSheet` "recording detail" listing the segments in order (`c0000..`), each row showing its index + duration and tappable to play that segment (audio → in-app player, video → `openFile`). (No merge/playlist — segment-by-segment playback is acceptable for this slice.)
- **Long-press** menu (Re-upload / Delete) must act on the WHOLE recording when it's a group: Re-upload enqueues every segment id in the group; Delete removes every segment's file + row (reuse the existing per-record delete over the group's ids, and the existing confirm dialog). For single units, unchanged.

### Task 3: Home "Today's uploads" — count recordings, not segments

**Files:** `ui/HomeScreen.kt`, `db/CaptureRecordDao.kt` (add an observe if needed).

- Replace the per-segment status counts with per-recording counts. A recording (`kind,sessionId` group, within today) is: **uploaded** if every segment `uploadStatus == "uploaded"`; **failed** if any segment failed (and not all uploaded); **waiting** otherwise (in-progress/pending).
- Simplest impl: add `dao.observeSince(sinceMs): Flow<List<CaptureRecord>>` (`SELECT * ... WHERE createdAt >= :sinceMs`), collect it in HomeScreen, group by `(kind, sessionId)`, derive each recording's status by the rule above, and produce the same `UploadSummary` shape (`total`, `uploaded`, `inProgress`, `failed`, `allDone`) counting RECORDINGS. Keep the existing card UI + the "Retrying N uploads" action (now "N recordings").
- Keep `summarizeUploads` for any other caller, but Home now feeds it recording-level numbers (or add a `summarizeRecordingUploads`). The card text ("Uploaded N", "Waiting N", "Failed N", "All uploaded") stays; N is now recordings.

## Device acceptance (controller-run)

Set segment length to 30s; record a video ~70s (→ 2–3 segments) → Files shows ONE video tile with total duration + "×N" badge; Home shows "Uploaded 1 recording" (not 2–3). Tap the tile → segment list; each plays. Delete the recording → all its segments gone. Repeat for audio. Photos still show individually.

## Notes / out of scope
- No on-device file merge (user chose UI grouping). A true single-file export could be a later slice.
- Seamless single-player playback of a whole recording (ExoPlayer playlist) is out of scope; segment-by-segment playback via the detail sheet is the slice's playback story.
