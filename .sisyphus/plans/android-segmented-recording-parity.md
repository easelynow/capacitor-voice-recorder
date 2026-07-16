# Android Segmented Recording — iOS Parity Implementation Plan

**Repo**: `capacitor plugins/capacitor-voice-recorder`
**Goal**: Bring Android to feature parity with iOS for continuous segmented audit recording (30–90 min sessions, 5-min segment rotation, `segmentReady` events, background recording, termination resilience).
**Status**: PLANNED — not started
**Consumer**: `frontend/operation-app` (`composables/useAuditRecorder.ts`, `plugins/auditRecovery.client.ts`)

---

## 1. Current State (audited 2026-07-16)

### What Android already has (parity exists — DO NOT touch)
- All 9 bridge methods: `canDeviceVoiceRecord`, `requestAudioRecordingPermission`, `hasAudioRecordingPermission`, `startRecording`, `stopRecording`, `pauseRecording`, `resumeRecording`, `getCurrentStatus`, `getCurrentAmplitude`
- Interruption handling: audio-focus loss → `pause()` + `INTERRUPTED` status + `voiceRecordingInterrupted`/`voiceRecordingInterruptionEnded` events (`CustomMediaRecorder.onAudioFocusChange`)
- Pause/resume with `NOT_SUPPORTED_OS_VERSION` gating
- Amplitude metering normalized to [0,1]
- Directory/subDirectory file output + base64/uri response paths
- Layered architecture with DI seams for JUnit tests (`MediaRecorderFactory`, `DirectoryProvider`, `SdkIntProvider`, etc.)

### What Android is missing (vs iOS)
| Gap | iOS reference |
|---|---|
| `segmentDurationMs` + `sessionId` in `RecordOptions` | `Core/RecordOptions.swift` |
| Segment rotation + `segmentReady` emission | `Platform/CustomMediaRecorder.swift` (timer + rotateSegment) |
| `SegmentInfo` payload type | `Core/SegmentInfo.swift` |
| `onSegmentReady` callback in adapter contract | `Adapters/RecorderAdapter.swift` |
| `flushCurrentSegment(terminating:)` | `Platform/CustomMediaRecorder.swift` + `Bridge/VoiceRecorder.swift` (willTerminate) |
| Background recording keep-alive | iOS: `mixWithOthers` audio session; Android: **needs microphone foreground service** |
| Termination flush + `pending_flush_{sessionId}.json` marker | `Bridge/VoiceRecorder.swift` `handleAppWillTerminate` |

---

## 2. Architecture Decisions (Oracle-reviewed)

### D1 — Segment rotation: `MediaRecorder.setNextOutputFile()` (API 26+), bump minSdk 24 → 26
- stop/restart per segment causes 50–300 ms audio gaps — unacceptable for audit speech.
- `setNextOutputFile(File)` is gapless and purpose-built. Requires `OutputFormat.MPEG_4`.
- API 24/25 device share is negligible (<1% in 2026); dual-path fallback complexity not justified.
- Rotation protocol:
  1. 5-min timer fires → call `setNextOutputFile(nextFile)` (file pre-created).
  2. `OnInfoListener` receives `MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED` → previous file is **sealed**.
  3. From the info listener (NOT the timer): read duration via `MediaMetadataRetriever`, emit `segmentReady`, increment index, pre-create next+1 file, prime `setNextOutputFile` again.
- **`segmentReady` MUST be emitted from `OnInfoListener`, never from the timer callback** — the timer only requests rotation; the info event confirms the seal.

### D2 — Output format: MPEG_4 / `.m4a` / `audio/mp4` for segmented mode
- Forced by `setNextOutputFile` (ADTS unsupported), and independently correct:
  - App recovery regex is hardcoded: `/^audio_.+_(\d+)\.m4a$/` — `.aac` files would NEVER be recovered.
  - App/backend expect `AUDIT_AUDIO_MIME = "audio/mp4"`.
  - MPEG_4 `moov` atom → reliable duration via `MediaMetadataRetriever.METADATA_KEY_DURATION` (ADTS duration via `MediaPlayer.prepare()` is unreliable, can return -1).
- **Legacy (non-segmented) path keeps AAC_ADTS/`.aac`/`audio/aac`** — no breaking change for existing consumers.

### D3 — Background recording: plugin-owned foreground service (microphone type)
- **Mandatory** on API 31+: without FGS with `foregroundServiceType="microphone"`, OS revokes mic within seconds of screen-off/backgrounding.
- Plugin owns the FGS lifecycle (host app coordinating FGS with recorder state is a footgun):
  - `RecordingForegroundService` declared in plugin `AndroidManifest.xml`, `android:exported="false"`, `android:foregroundServiceType="microphone"`.
  - Plugin manifest declares `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` (both normal perms, auto-granted, merged into host manifest).
  - Started when `startRecording` runs in segmented mode; stopped on `stopRecording`.
  - FGS is a **thin lifecycle shell**: it elevates the process; the recorder object stays owned by `VoiceRecorderService` (same process). No recorder logic moves into the Service class.
  - Notification channel created idempotently at start, `IMPORTANCE_LOW` (no sound). Text: "Recording in progress".
- **POST_NOTIFICATIONS (API 33+)**: runtime permission owned by HOST APP. Plugin attempts to post; if denied, FGS still runs (notification suppressed). Document in README that host should request it for a visible indicator. `startRecording` MUST NOT fail on missing POST_NOTIFICATIONS.

### D4 — Termination flush: disk-scan recovery primary, `onTaskRemoved` best-effort
- MPEG_4 writes `moov` at `stop()` — a process kill mid-segment leaves the in-progress file **unplayable**. Sealed segments (info-event-confirmed) are always safe.
- `Service.onTaskRemoved` (swipe-kill, the common case): ~200–500 ms available → call `mediaRecorder.stop()` (seals `moov`), write `pending_flush_{sessionId}.json` marker next to segments (same JSON shape as iOS: `sessionId, segmentIndex, fileName, path, durationMs, mimeType`), best-effort emit `segmentReady`.
- OOM/SIGKILL: no callback — accepted loss of last partial segment; app's boot-time `reconcileAuditSegmentsOnDisk` handles sealed orphans.

### D5 — Rotation timer: `Handler.postDelayed` on a dedicated `HandlerThread`
- Under an active microphone FGS the process is foreground-bucket: Doze/App Standby don't throttle handlers. No `AlarmManager` needed.
- All recorder state mutations serialize onto this single `HandlerThread` looper (Android analogue of iOS `stateQueue`), avoiding the lock bugs iOS FIX 1–5 addressed.

---

## 3. Contract Constraints (from operation-app — MUST match exactly)

| Constraint | Value |
|---|---|
| Segment file name | `audio_{sessionId}_{index}.m4a` (0-based index) |
| MIME in `segmentReady` | `audio/mp4` |
| Storage root | `Directory.Library` + `subDirectory: audit/{sessionId}` → Android `getFilesDir()/audit/{sessionId}/` (existing `LIBRARY` mapping ✓) |
| `uri` field | `file://`-prefixed absolute path (app's `readFileAsFile` handles `file://` directly; the `/Library/`-relative branch is iOS-only and safely skipped) |
| `segmentReady` payload keys | `sessionId` (string), `index` (int), `uri` (string), `fileName` (string), `msDuration` (int), `mimeType` (string) |
| Final segment | Emitted on `stopRecording()`; app polls up to 1.5 s after stop resolves — emit promptly |
| Session-scoped events | App drops events whose `sessionId` mismatches — echo `sessionId` unmodified |
| Marker file | `pending_flush_{sessionId}.json`, keys: `sessionId, segmentIndex, fileName, path, durationMs, mimeType`; app deletes after processing |

---

## 4. Implementation Tasks

### Task 1 — Core types
**Files**: `core/RecordOptions.java`, new `core/SegmentInfo.java`
- [ ] `RecordOptions` record: add `Integer segmentDurationMs`, `String sessionId` (keep old 2-arg constructor delegating with nulls — existing tests/`canPhoneCreateMediaRecorderWhileHavingPermission` use it).
- [ ] New `SegmentInfo` record: `String sessionId, int index, String uri, String fileName, int msDuration, String mimeType` (mirror `SegmentInfo.swift`).

### Task 2 — Adapter contract
**Files**: `adapters/RecorderAdapter.java`
- [ ] Add `void setOnSegmentReady(Consumer<SegmentInfo> callback);`
- [ ] Add `void flushCurrentSegment(boolean terminating, Consumer<SegmentInfo> completion);` (completion called with `null` when nothing to flush — mirrors iOS optional).

### Task 3 — Platform recorder: segmented mode
**Files**: `platform/CustomMediaRecorder.java`
- [ ] Segmented mode activation rule (identical to iOS): `segmentDurationMs > 0 && directory != null && sessionId non-empty`.
- [ ] Segmented config: `OutputFormat.MPEG_4`, `AudioEncoder.AAC`, 96 kbps, 44.1 kHz, output `audio_{sessionId}_0.m4a` in resolved directory (reuse existing directory/subDirectory resolution; do NOT use `File.createTempFile` naming in segmented mode). Legacy path unchanged (AAC_ADTS temp files).
- [ ] `HandlerThread` + `Handler` rotation timer at `segmentDurationMs`; timer callback pre-creates `audio_{sessionId}_{n+1}.m4a` and calls `mediaRecorder.setNextOutputFile(file)`.
- [ ] `OnInfoListener`: on `MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED` → seal confirmed → duration via `MediaMetadataRetriever` → build `SegmentInfo` (uri = `Uri.fromFile(...).toString()`) → invoke `onSegmentReady` → advance index.
- [ ] `pauseRecording()`: also suspend rotation timer (remove callbacks; record elapsed-in-segment to reschedule remainder on resume). `resumeRecording()`: reschedule timer.
- [ ] Interruption (`onAudioFocusChange` loss): pause + suspend timer + `INTERRUPTED` (existing behavior extended with timer suspension). Focus regain: existing `onInterruptionEnded` fires; JS calls `resumeRecording()` (idempotent — return `true` if already `RECORDING`, matching iOS FIX 5).
- [ ] `stopRecording()` segmented: cancel timer → `mediaRecorder.stop()` (seals final file) → emit final `SegmentInfo` via `onSegmentReady` → release. No merge (matches iOS).
- [ ] `flushCurrentSegment(terminating, completion)`: guard `isSegmentedMode && status == RECORDING`; `stop()` seals file; emit `SegmentInfo`; if `terminating` → clean up and stay stopped; else create new recorder for next index and continue. All on the recorder `Handler` thread.
- [ ] `getCurrentAmplitude()` must keep working across rotations (`getMaxAmplitude` is per-recorder-instance — verify after `setNextOutputFile`, which does NOT recreate the instance, so it keeps working; confirm in device test).
- [ ] Duration helper: `MediaMetadataRetriever` for `.m4a`; keep `MediaPlayer` path for legacy `.aac` (or migrate both — decide in review; MMR is strictly better).
- [ ] New DI seams for testability, matching existing pattern: `InfoListenerRegistrar`, `MetadataRetrieverFactory`, `HandlerProvider` (or inject `Looper`).

### Task 4 — Service layer
**Files**: `service/VoiceRecorderService.java`
- [ ] `startRecording(...)`: accept + forward `Consumer<SegmentInfo> onSegmentReady` (default no-op overload keeps old signature for existing tests).
- [ ] `stopRecording()`: derive `mimeType`/`fileExtension` from output file extension (`.m4a` → `audio/mp4`/`m4a`; `.aac` → `audio/aac`/`aac`) instead of hardcoded `"audio/aac", "aac"` — mirrors `VoiceRecorderService.swift` L90-91.
- [ ] Add `flushCurrentSegment(boolean terminating, Consumer<SegmentInfo> completion)` passthrough (null recorder → `completion(null)`).

### Task 5 — Bridge
**Files**: `VoiceRecorder.java`
- [ ] `startRecording`: parse `segmentDurationMs` (`call.getInt`) and `sessionId` (`call.getString`); build 4-field `RecordOptions`; pass `onSegmentReady` lambda that does `notifyListeners("segmentReady", data)` with the exact payload keys from §3. `notifyListeners` from background thread is safe in Capacitor, but marshal via main handler for consistency with iOS `DispatchQueue.main`.
- [ ] Start/stop `RecordingForegroundService` around segmented sessions (start after `service.startRecording` succeeds; stop in `stopRecording` finally-path and on start failure).

### Task 6 — Foreground service + manifest
**Files**: new `platform/RecordingForegroundService.java`, `AndroidManifest.xml`, `build.gradle`
- [ ] Manifest: `<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>`, `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>`, `<service android:name=".platform.RecordingForegroundService" android:exported="false" android:foregroundServiceType="microphone"/>`.
- [ ] Service: `onStartCommand` → create channel (`IMPORTANCE_LOW`, id `voice_recorder_recording`) → `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE)` (typed overload on API 29+; API 34+ REQUIRES the typed call). `START_NOT_STICKY`.
- [ ] Wrap notification post so missing POST_NOTIFICATIONS never crashes/fails start.
- [ ] `onTaskRemoved`: call plugin flush hook (static/singleton reference to active `VoiceRecorderService`) → `flushCurrentSegment(terminating=true)` → write `pending_flush_{sessionId}.json` next to segments (JSON keys per §3) → `stopSelf()`.
- [ ] `build.gradle`: `minSdk 24 → 26`. Remove now-dead `NotSupportedOsVersion` gating? **NO** — keep API surface (`NOT_SUPPORTED_OS_VERSION` error stays documented); just note the branch is unreachable at minSdk 26. (Low-risk cleanup, separate PR.)

### Task 7 — Docs + TS
**Files**: `README.md`, `src/definitions.ts` (comments only)
- [ ] README: Android segmented mode section — minSdk 26 requirement, FGS behavior, notification, POST_NOTIFICATIONS host-app guidance, `audio/mp4` mime on Android segmented mode, termination-flush semantics (swipe-kill flush best-effort; SIGKILL loses last partial segment).
- [ ] `definitions.ts` JSDoc: update `SegmentReadyEvent.mimeType` comment (`iOS: audio/mp4` → `iOS/Android: audio/mp4`) + `segmentDurationMs` note about Android FGS. Run `pnpm docgen`.

### Task 8 — Tests
**Files**: `android/src/test/java/...`
- [ ] `RecordOptions` 4-field construction + backward-compat 2-arg constructor.
- [ ] `CustomMediaRecorderTest`: segmented-mode activation matrix (missing directory / zero duration / empty sessionId → legacy mode); rotation emits `SegmentInfo` with correct index/fileName on simulated `MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED`; pause suspends timer / resume reschedules; `flushCurrentSegment(terminating=true)` stops without restart; `flushCurrentSegment(terminating=false)` restarts at next index.
- [ ] `VoiceRecorderServiceStopTest`: mime derived from extension (m4a→audio/mp4, aac→audio/aac); segmented stop emits final segment.
- [ ] `VoiceRecorderTest` (bridge): `segmentReady` payload key/type contract; segment options parsed from call.
- [ ] Use existing DI-seam mock pattern (no Robolectric additions unless already present).

---

## 5. Verification / Acceptance

1. `pnpm test` (web/TS untouched — must stay green).
2. `cd android && ./gradlew test` — all new + existing JUnit green.
3. `pnpm verify:android` (Gradle build + tests; requires Java 21).
4. **Device test (manual, operation-app)** on Android 12+ physical device:
   - Start audit → screen off 15 min → ≥3 `segmentReady` events received, files playable, durations ≈ 5 min.
   - Phone call mid-session → `voiceRecordingInterrupted` fires → call ends → `voiceRecordingInterruptionEnded` → app resumes → next segments arrive.
   - Swipe-kill mid-segment → relaunch → `reconcileAuditSegmentsOnDisk` recovers sealed segments + marker segment; last partial either sealed (via onTaskRemoved) or discarded, never uploaded corrupt.
   - Stop recording → final partial segment `segmentReady` arrives within 1.5 s of `stopRecording()` resolving.
   - Amplitude meter returns non-zero values across segment rotations.
5. Cross-platform: same audit session code path works unmodified on iOS (no JS changes required — that is the definition of done).

## 6. Out of Scope
- Web `segmentReady` bridging (web timeslice stays internal).
- `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` continue-while-ducked optimization (Oracle: revisit later).
- `moov` repair for SIGKILL partial segments.
- npm publishing (currently `file:` linked).
- Consolidating operation-app's second plugin (`@capgo/capacitor-audio-recorder`).

## 7. Risks
| Risk | Mitigation |
|---|---|
| `setNextOutputFile` OEM quirks (some vendors delay info event) | Device test on ≥2 OEMs; timer-side watchdog: if info event not received within 10 s of rotation request, log + fall back to stop/recreate for next segment |
| minSdk bump breaks a consumer | ✅ VERIFIED: operation-app `android/variables.gradle` has `minSdkVersion = 26` — no consumer impact |
| FGS start denied (BFSL — background FGS launch restrictions API 31+) | `startRecording` is always user-initiated from foreground UI → allowed; document that segmented `startRecording` must be called while app is foregrounded |
| `getMaxAmplitude` behavior across `setNextOutputFile` | Verify on device; fallback: accept metering blip at rotation |
