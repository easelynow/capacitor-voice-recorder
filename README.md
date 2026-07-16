<p align="center">
  <img src="https://user-images.githubusercontent.com/236501/85893648-1c92e880-b7a8-11ea-926d-95355b8175c7.png" width="128" height="128" alt="CapacitorJS Logo" />
</p>
<h3 align="center">Capacitor Audit Recorder</h3>
<p align="center"><strong><code>@easelynow/capacitor-audit-recorder</code></strong></p>
<p align="center">Capacitor plugin for continuous background audit voice recording (segmented), plus general-purpose audio recording</p>

<p align="center">
  <img src="https://img.shields.io/maintenance/yes/2026" alt="Maintenance Badge: until 2026" />
  <a href="https://www.npmjs.com/package/@easelynow/capacitor-audit-recorder"><img src="https://img.shields.io/npm/l/@easelynow/capacitor-audit-recorder" alt="License Badge: MIT" /></a>
<br>
  <a href="https://www.npmjs.com/package/@easelynow/capacitor-audit-recorder"><img src="https://img.shields.io/npm/dw/@easelynow/capacitor-audit-recorder" alt="" role="presentation" /></a>
  <a href="https://www.npmjs.com/package/@easelynow/capacitor-audit-recorder"><img src="https://img.shields.io/npm/v/@easelynow/capacitor-audit-recorder" alt="" role="presentation" /></a>
  <a href="https://codecov.io/gh/easelynow/capacitor-voice-recorder/branch/dev"><img src="https://codecov.io/gh/easelynow/capacitor-voice-recorder/branch/dev/graph/badge.svg" alt="Coverage Badge: dev" /></a>
</p>

## Overview

The `@easelynow/capacitor-audit-recorder` plugin records audio on Android, iOS, and Web. It supports two modes:

- **General-purpose recording** — start/stop/pause/resume a single recording, returned as base64 or a filesystem
  URI. This is the original `capacitor-voice-recorder` feature set (permissions, interruption handling, amplitude
  metering).
- **Continuous segmented recording** (`RecordingOptions.segmentDurationMs`) — purpose-built for long (30-90 min)
  background audit recordings. The recorder auto-rotates into fixed-length segment files on disk and emits a
  `segmentReady` event per finalized segment, so callers can upload/process each segment as it completes instead of
  holding one large in-memory/on-disk blob for the whole session. iOS and Android both implement this mode with
  equivalent behavior (gapless rotation, phone-call/interruption handling, and best-effort recovery of the
  in-progress segment if the app is killed mid-session) — see [Platform behaviors](#platform-behaviors) below for the
  platform-specific mechanics (foreground service on Android, background audio session on iOS).

## Installation

```
pnpm add @easelynow/capacitor-audit-recorder
pnpm exec cap sync
```

> Within this monorepo, apps typically depend on this package via a `file:` reference to this directory (see
> `operation-app/package.json`) rather than a published npm version, since the plugin and its consuming apps are
> developed together. `pnpm exec cap sync` still applies after any native-side change.

### Configuration

#### Using with Android

Add the following to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
```

No further manifest changes are needed for continuous segmented recording — the plugin declares its own
`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` permissions and its foreground `Service`. See
[Android segmented recording](#android-segmented-recording-continuous-audit-recordings) for details and the
optional `POST_NOTIFICATIONS` recommendation on Android 13+.

#### Using with iOS

Add the following to your `Info.plist`:

```xml
<key>NSMicrophoneUsageDescription</key>
<string>This app uses the microphone to record audio.</string>
```

For continuous segmented recording to survive app backgrounding, also enable the `audio` background mode in
`Info.plist` (`UIBackgroundModes` → `audio`), matching the `mixWithOthers` background-friendly audio session the
plugin configures internally.

### Requirements

- Capacitor 8+
- iOS 15+
- Android minSdk 26+; builds require Java 21 (recommended). `pnpm verify:android` requires a Java version supported
  by the bundled Gradle wrapper (currently Java 21–24, with Java 21 recommended).

### iOS Package Manager Support

This plugin supports both CocoaPods and Swift Package Manager (SPM) on iOS.

- CocoaPods (default Capacitor iOS template): `npx cap sync ios`
- Swift Package Manager (SPM): migrate/create your iOS app to use SPM, then run `npx cap sync ios`

## Quick start

Minimal flow for starting and stopping a recording:

```typescript
import {VoiceRecorder} from '@easelynow/capacitor-audit-recorder';

export const startRecording = async () => {
    const permission = await VoiceRecorder.requestAudioRecordingPermission();
    if (!permission.value) {
        throw new Error('Microphone permission not granted');
    }

    await VoiceRecorder.startRecording();
};

export const stopRecording = async () => {
    const {value} = await VoiceRecorder.stopRecording();
    return value;
};
```

## API

Below is an index of all available methods. Run `pnpm docgen` after updating any JSDoc comments to refresh this
section.

<docgen-index>

* [`canDeviceVoiceRecord()`](#candevicevoicerecord)
* [`requestAudioRecordingPermission()`](#requestaudiorecordingpermission)
* [`hasAudioRecordingPermission()`](#hasaudiorecordingpermission)
* [`startRecording(...)`](#startrecording)
* [`stopRecording()`](#stoprecording)
* [`pauseRecording()`](#pauserecording)
* [`resumeRecording()`](#resumerecording)
* [`getCurrentStatus()`](#getcurrentstatus)
* [`getCurrentAmplitude()`](#getcurrentamplitude)
* [`addListener('voiceRecordingInterrupted', ...)`](#addlistenervoicerecordinginterrupted-)
* [`addListener('voiceRecordingInterruptionEnded', ...)`](#addlistenervoicerecordinginterruptionended-)
* [`addListener('segmentReady', ...)`](#addlistenersegmentready-)
* [`removeAllListeners()`](#removealllisteners)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)
* [Enums](#enums)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

Interface for the VoiceRecorderPlugin which provides methods to record audio.

### canDeviceVoiceRecord()

```typescript
canDeviceVoiceRecord() => Promise<GenericResponse>
```

Checks if the current device can record audio.
On mobile, this function will always resolve to `{ value: true }`.
In a browser, it will resolve to `{ value: true }` or `{ value: false }` based on the browser's ability to record.
This method does not take into account the permission status, only if the browser itself is capable of recording at all.

**Returns:** <code>Promise&lt;<a href="#genericresponse">GenericResponse</a>&gt;</code>

--------------------


### requestAudioRecordingPermission()

```typescript
requestAudioRecordingPermission() => Promise<GenericResponse>
```

Requests audio recording permission from the user.
If the permission has already been provided, the promise will resolve with `{ value: true }`.
Otherwise, the promise will resolve to `{ value: true }` or `{ value: false }` based on the user's response.

**Returns:** <code>Promise&lt;<a href="#genericresponse">GenericResponse</a>&gt;</code>

--------------------


### hasAudioRecordingPermission()

```typescript
hasAudioRecordingPermission() => Promise<GenericResponse>
```

Checks if audio recording permission has been granted.
Will resolve to `{ value: true }` or `{ value: false }` based on the status of the permission.
The web implementation of this plugin uses the Permissions API, which is not widespread.
If the status of the permission cannot be checked, the promise will reject with `COULD_NOT_QUERY_PERMISSION_STATUS`.
In that case, use `requestAudioRecordingPermission` or `startRecording` and capture any exception that is thrown.

**Returns:** <code>Promise&lt;<a href="#genericresponse">GenericResponse</a>&gt;</code>

--------------------


### startRecording(...)

```typescript
startRecording(options?: RecordingOptions | undefined) => Promise<GenericResponse>
```

Starts audio recording.
On success, the promise will resolve to { value: true }.
On error, the promise will reject with one of the following error codes:
"MISSING_PERMISSION", "ALREADY_RECORDING", "MICROPHONE_BEING_USED", "DEVICE_CANNOT_VOICE_RECORD", or "FAILED_TO_RECORD".

| Param         | Type                                                          | Description                    |
| ------------- | ------------------------------------------------------------- | ------------------------------ |
| **`options`** | <code><a href="#recordingoptions">RecordingOptions</a></code> | The options for the recording. |

**Returns:** <code>Promise&lt;<a href="#genericresponse">GenericResponse</a>&gt;</code>

--------------------


### stopRecording()

```typescript
stopRecording() => Promise<RecordingData>
```

Stops audio recording.
Will stop the recording that has been previously started.
If the function `startRecording` has not been called beforehand, the promise will reject with `RECORDING_HAS_NOT_STARTED`.
If the recording has been stopped immediately after it has been started, the promise will reject with `EMPTY_RECORDING`.
In a case of unknown error, the promise will reject with `FAILED_TO_FETCH_RECORDING`.
On iOS, if a recording interrupted by the system cannot be merged, the promise will reject with `FAILED_TO_MERGE_RECORDING`.
In case of success, the promise resolves to <a href="#recordingdata">RecordingData</a> containing the recording in base-64, the duration of the recording in milliseconds, and the MIME type.

**Returns:** <code>Promise&lt;<a href="#recordingdata">RecordingData</a>&gt;</code>

--------------------


### pauseRecording()

```typescript
pauseRecording() => Promise<GenericResponse>
```

Pauses the ongoing audio recording.
If the recording has not started yet, the promise will reject with an error code `RECORDING_HAS_NOT_STARTED`.
On success, the promise will resolve to { value: true } if the pause was successful or { value: false } if the recording is already paused.
On certain mobile OS versions, this function is not supported and will reject with `NOT_SUPPORTED_OS_VERSION`.

**Returns:** <code>Promise&lt;<a href="#genericresponse">GenericResponse</a>&gt;</code>

--------------------


### resumeRecording()

```typescript
resumeRecording() => Promise<GenericResponse>
```

Resumes a paused or interrupted audio recording.
If the recording has not started yet, the promise will reject with an error code `RECORDING_HAS_NOT_STARTED`.
On success, the promise will resolve to { value: true } if the resume was successful or { value: false } if the recording is already running.
On certain mobile OS versions, this function is not supported and will reject with `NOT_SUPPORTED_OS_VERSION`.

**Returns:** <code>Promise&lt;<a href="#genericresponse">GenericResponse</a>&gt;</code>

--------------------


### getCurrentStatus()

```typescript
getCurrentStatus() => Promise<CurrentRecordingStatus>
```

Gets the current status of the voice recorder.
Will resolve with one of the following values:
`{ status: "NONE" }` if the plugin is idle and waiting to start a new recording.
`{ status: "RECORDING" }` if the plugin is in the middle of recording.
`{ status: "PAUSED" }` if the recording is paused.
`{ status: "INTERRUPTED" }` if the recording was paused due to a system interruption.

**Returns:** <code>Promise&lt;<a href="#currentrecordingstatus">CurrentRecordingStatus</a>&gt;</code>

--------------------


### getCurrentAmplitude()

```typescript
getCurrentAmplitude() => Promise<CurrentAmplitude>
```

Gets the current input amplitude.

Returns `{ value: 0 }` when no recording is active. The value is normalized
to the `[0, 1]` range, but the underlying signal source differs by platform,
so consumers may need a platform-specific scaling curve for exact parity.

Intended for UI-rate polling. A `60-100ms` interval is a reasonable starting
point for meters or waveforms; avoid calling it in a tight loop because each
call crosses the JavaScript/native bridge.

**Returns:** <code>Promise&lt;<a href="#currentamplitude">CurrentAmplitude</a>&gt;</code>

--------------------


### addListener('voiceRecordingInterrupted', ...)

```typescript
addListener(eventName: 'voiceRecordingInterrupted', listenerFunc: (event: VoiceRecordingInterruptedEvent) => void) => Promise<PluginListenerHandle>
```

Listen for audio recording interruptions (e.g., phone calls, other apps using microphone).
Available on iOS and Android only.

| Param              | Type                                                                                                          | Description                                            |
| ------------------ | ------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------ |
| **`eventName`**    | <code>'voiceRecordingInterrupted'</code>                                                                      | The name of the event to listen for.                   |
| **`listenerFunc`** | <code>(event: <a href="#voicerecordinginterruptedevent">VoiceRecordingInterruptedEvent</a>) =&gt; void</code> | The callback function to invoke when the event occurs. |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('voiceRecordingInterruptionEnded', ...)

```typescript
addListener(eventName: 'voiceRecordingInterruptionEnded', listenerFunc: (event: VoiceRecordingInterruptionEndedEvent) => void) => Promise<PluginListenerHandle>
```

Listen for audio recording interruption end events.
Available on iOS and Android only.

| Param              | Type                                                                                                                      | Description                                            |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------ |
| **`eventName`**    | <code>'voiceRecordingInterruptionEnded'</code>                                                                            | The name of the event to listen for.                   |
| **`listenerFunc`** | <code>(event: <a href="#voicerecordinginterruptionendedevent">VoiceRecordingInterruptionEndedEvent</a>) =&gt; void</code> | The callback function to invoke when the event occurs. |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('segmentReady', ...)

```typescript
addListener(eventName: 'segmentReady', listenerFunc: (event: SegmentReadyEvent) => void) => Promise<PluginListenerHandle>
```

Listen for finalized recording segments in continuous segmented mode
(see <a href="#recordingoptions">`RecordingOptions.segmentDurationMs`</a>). Fires once per completed segment
plus once for the final (partial) segment on `stopRecording()`.
Available on iOS and Android only.

| Param              | Type                                                                                | Description                                    |
| ------------------ | ----------------------------------------------------------------------------------- | ---------------------------------------------- |
| **`eventName`**    | <code>'segmentReady'</code>                                                         | The name of the event to listen for.           |
| **`listenerFunc`** | <code>(event: <a href="#segmentreadyevent">SegmentReadyEvent</a>) =&gt; void</code> | The callback invoked with the segment details. |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### removeAllListeners()

```typescript
removeAllListeners() => Promise<void>
```

Remove all listeners for this plugin.

--------------------


### Interfaces


#### GenericResponse

Interface representing a generic response with a boolean value.

| Prop        | Type                 | Description                                     |
| ----------- | -------------------- | ----------------------------------------------- |
| **`value`** | <code>boolean</code> | The result of the operation as a boolean value. |


#### RecordingOptions

Can be used to specify options for the recording.

| Prop                         | Type                                            | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| ---------------------------- | ----------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`directory`**              | <code><a href="#directory">Directory</a></code> | The capacitor filesystem directory where the recording should be saved. If not specified, the recording will be stored in a base64 string and returned in the <a href="#recordingdata">`RecordingData`</a> object.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| **`subDirectory`**           | <code>string</code>                             | An optional subdirectory in the specified directory where the recording should be saved.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| **`requirePlaybackSupport`** | <code>boolean</code>                            | Whether the web implementation should require the selected recording MIME type to also be playable by the browser's native HTML `&lt;audio&gt;` element. Defaults to `true` on web to reduce cases where `MediaRecorder` reports support for a format but the recorded file cannot be played back in the same browser (observed on some Safari/iOS/WKWebView combinations). Native platforms ignore this option.                                                                                                                                                                                                                                                                                                                                                                                                  |
| **`segmentDurationMs`**      | <code>number</code>                             | When set to a positive number of milliseconds, native platforms record in CONTINUOUS SEGMENTED mode: the recorder auto-finalizes a segment file every `segmentDurationMs` and immediately starts the next one, emitting a `segmentReady` event per finalized segment. Requires `directory` to be set so segments are written to disk. Designed for long (30-90 min) background audit recordings without holding a large in-memory blob. If omitted or `0`, the recorder behaves as a single-file recording (legacy). Web honors this via `MediaRecorder` timeslice; interruption-based segmentation on iOS is independent of this value. Requires Android API 26+ (`MediaRecorder.setNextOutputFile`); on Android, segmented sessions automatically run a microphone foreground service for the session duration. |
| **`sessionId`**              | <code>string</code>                             | Correlation id stamped onto every `segmentReady` event for this recording session (e.g. the audit session UUID). Native echoes it back unmodified.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |


#### RecordingData

Interface representing the data of a recording.

| Prop        | Type                                                                                                                  | Description                                 |
| ----------- | --------------------------------------------------------------------------------------------------------------------- | ------------------------------------------- |
| **`value`** | <code>{ recordDataBase64: string; msDuration: number; mimeType: string; fileExtension: string; uri?: string; }</code> | The value containing the recording details. |


#### CurrentRecordingStatus

Interface representing the current status of the voice recorder.

| Prop         | Type                                                            | Description                                                                                                                 |
| ------------ | --------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| **`status`** | <code>'NONE' \| 'RECORDING' \| 'PAUSED' \| 'INTERRUPTED'</code> | The current status of the recorder, which can be one of the following values: 'RECORDING', 'PAUSED', 'INTERRUPTED', 'NONE'. |


#### CurrentAmplitude

Interface representing the current input amplitude.

| Prop        | Type                | Description                                                   |
| ----------- | ------------------- | ------------------------------------------------------------- |
| **`value`** | <code>number</code> | The current input amplitude normalized to the `[0, 1]` range. |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


#### SegmentReadyEvent

Event payload for the `segmentReady` event. Emitted each time a continuous
segmented recording finalizes a `segmentDurationMs`-long chunk to disk.

| Prop             | Type                | Description                                                                                           |
| ---------------- | ------------------- | ----------------------------------------------------------------------------------------------------- |
| **`sessionId`**  | <code>string</code> | Correlation id passed in <a href="#recordingoptions">`RecordingOptions.sessionId`</a> (may be empty). |
| **`index`**      | <code>number</code> | 0-based segment index — the canonical ordering key for stitching.                                     |
| **`uri`**        | <code>string</code> | Capacitor filesystem URI of the finalized segment file.                                               |
| **`fileName`**   | <code>string</code> | File name of the segment (e.g. `audio_{sessionId}_{index}.m4a`).                                      |
| **`msDuration`** | <code>number</code> | Audio content duration of this segment in milliseconds.                                               |
| **`mimeType`**   | <code>string</code> | MIME type of the segment file (iOS/Android: `audio/mp4`).                                             |


### Type Aliases


#### Base64String

Represents a Base64 encoded string.

<code>string</code>


#### VoiceRecordingInterruptedEvent

Event payload for voiceRecordingInterrupted event (empty - no data).

<code><a href="#record">Record</a>&lt;string, never&gt;</code>


#### Record

Construct a type with a set of properties K of type T

<code>{ [P in K]: T; }</code>


#### VoiceRecordingInterruptionEndedEvent

Event payload for voiceRecordingInterruptionEnded event (empty - no data).

<code><a href="#record">Record</a>&lt;string, never&gt;</code>


### Enums


#### Directory

| Members               | Value                           | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | Since |
| --------------------- | ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----- |
| **`Documents`**       | <code>"DOCUMENTS"</code>        | The Documents directory. On iOS it's the app's documents directory. Use this directory to store user-generated content. On Android it's the Public Documents folder, so it's accessible from other apps. It's not accessible on Android 10 unless the app enables legacy External Storage by adding `android:requestLegacyExternalStorage="true"` in the `application` tag in the `AndroidManifest.xml`. On Android 11 or newer the app can only access the files/folders the app created. | 1.0.0 |
| **`Data`**            | <code>"DATA"</code>             | The Data directory. On iOS it will use the Documents directory. On Android it's the directory holding application files. Files will be deleted when the application is uninstalled.                                                                                                                                                                                                                                                                                                        | 1.0.0 |
| **`Library`**         | <code>"LIBRARY"</code>          | The Library directory. On iOS it will use the Library directory. On Android it's the directory holding application files. Files will be deleted when the application is uninstalled.                                                                                                                                                                                                                                                                                                       | 1.1.0 |
| **`Cache`**           | <code>"CACHE"</code>            | The Cache directory. Can be deleted in cases of low memory, so use this directory to write app-specific files. that your app can re-create easily.                                                                                                                                                                                                                                                                                                                                         | 1.0.0 |
| **`External`**        | <code>"EXTERNAL"</code>         | The external directory. On iOS it will use the Documents directory. On Android it's the directory on the primary shared/external storage device where the application can place persistent files it owns. These files are internal to the applications, and not typically visible to the user as media. Files will be deleted when the application is uninstalled.                                                                                                                         | 1.0.0 |
| **`ExternalStorage`** | <code>"EXTERNAL_STORAGE"</code> | The external storage directory. On iOS it will use the Documents directory. On Android it's the primary shared/external storage directory. It's not accessible on Android 10 unless the app enables legacy External Storage by adding `android:requestLegacyExternalStorage="true"` in the `application` tag in the `AndroidManifest.xml`. It's not accessible on Android 11 or newer.                                                                                                     | 1.0.0 |
| **`ExternalCache`**   | <code>"EXTERNAL_CACHE"</code>   | The external cache directory. On iOS it will use the Documents directory. On Android it's the primary shared/external cache.                                                                                                                                                                                                                                                                                                                                                               | 7.1.0 |
| **`LibraryNoCloud`**  | <code>"LIBRARY_NO_CLOUD"</code> | The Library directory without cloud backup. Used in iOS. On Android it's the directory holding application files.                                                                                                                                                                                                                                                                                                                                                                          | 7.1.0 |
| **`Temporary`**       | <code>"TEMPORARY"</code>        | A temporary directory for iOS. On Android it's the directory holding the application cache.                                                                                                                                                                                                                                                                                                                                                                                                | 7.1.0 |

</docgen-api>

## Platform behaviors

### Interruption handling (iOS and Android)

On iOS and Android, the plugin listens for system audio interruptions (phone calls, other apps taking audio focus). When
an interruption begins, the recording is paused, the status becomes `INTERRUPTED`, and the `voiceRecordingInterrupted`
event fires. When the interruption ends, the `voiceRecordingInterruptionEnded` event fires, and the status stays
`INTERRUPTED` until you call `resumeRecording()` or `stopRecording()`. Web does not provide interruption handling.

If interruptions occur on iOS, recordings are segmented and merged when you stop. iOS recordings are normalized to an
M4A container with MIME type `audio/mp4` for consistent output across interrupted and non-interrupted sessions.

### Android segmented recording (continuous audit recordings)

When `RecordingOptions.segmentDurationMs` is set together with `directory` and `sessionId`, Android records in the
same continuous-segmented mode as iOS: segments rotate gaplessly (`MediaRecorder.setNextOutputFile`) into MPEG-4/`.m4a`
files named `audio_{sessionId}_{index}.m4a`, and each finalized segment fires `segmentReady` with MIME type `audio/mp4`.
This requires **Android API 26+** (`MediaRecorder.setNextOutputFile` is unavailable below API 26).

**Foreground service.** Segmented recording automatically starts a microphone foreground service
(`foregroundServiceType="microphone"`) for the duration of the session and stops it when `stopRecording()` resolves.
This is required on Android 12+, which otherwise revokes microphone access within seconds of the app leaving the
foreground. The plugin declares `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MICROPHONE` in its own manifest — no
host-app manifest changes are needed. `startRecording()` with `segmentDurationMs` set must be called while the app is
in the foreground (Android restricts background-started foreground services on API 31+).

**Notification.** The foreground service posts a low-importance, silent notification ("Recording in progress") while
active. On Android 13+ (API 33), posting a visible notification requires the runtime `POST_NOTIFICATIONS` permission;
if the host app has not requested/been granted it, the foreground service still runs (recording is unaffected), it
just may not show a visible notification on some OEMs. Request `POST_NOTIFICATIONS` from the host app if you want a
guaranteed visible indicator.

**Termination behavior.** If the app process is swipe-killed while a segmented session is active, the plugin makes a
best-effort attempt (`Service.onTaskRemoved`) to finalize the in-progress segment and write a
`pending_flush_{sessionId}.json` marker file next to the segment files (same shape as iOS's terminate-flush marker),
so a boot-time disk scan can recover it. If the process is killed outright (e.g. low-memory `SIGKILL`) with no
callback opportunity, the in-progress segment's MPEG-4 container is not finalized (no `moov` atom) and is not
recoverable — only the last partial segment is at risk; all previously sealed segments are unaffected.

### Web constraints

- `getUserMedia` requires a secure context (HTTPS or localhost).
- Most browsers require a user gesture to start recording; call `startRecording()` from a click/tap handler.
- The Permissions API is not consistently supported; `hasAudioRecordingPermission()` can reject with
  `COULD_NOT_QUERY_PERMISSION_STATUS`. In that case, use `requestAudioRecordingPermission()` or `startRecording()` and
  handle errors.
- By default, the web implementation picks a MIME type that is supported for both recording (`MediaRecorder`) and
  playback (`<audio>`). You can disable the playback probe with `RecordingOptions.requirePlaybackSupport = false` if
  you prefer recorder-only MIME selection.

## Recording options and storage

When you set `RecordingOptions.directory`, recordings are written to the Capacitor filesystem and `stopRecording()`
returns a `uri`. This avoids large base64 payloads and is recommended for long recordings. When `directory` is not set,
the data is returned in `recordDataBase64`.

When a `uri` is present, `recordDataBase64` may be empty or omitted, so prefer `uri` when available.

```typescript
import {Directory} from '@capacitor/filesystem';
import {VoiceRecorder} from '@easelynow/capacitor-audit-recorder';

await VoiceRecorder.startRecording({
    directory: Directory.Cache,
    subDirectory: 'voice',
});
```

## Format and MIME type

The plugin returns the recording in one of several possible formats. The actual MIME type depends on the platform and
browser capabilities.

- Android: `audio/aac` (legacy single-file recording); `audio/mp4` (M4A container) when using continuous segmented recording (`RecordingOptions.segmentDurationMs`)
- iOS: `audio/mp4` (M4A container)
- Web: first supported MIME type from the plugin's ordered list, with a default preference for formats that are
  reported as playable by the browser `<audio>` element (in addition to `MediaRecorder` support)

Because not all devices and browsers support the same formats, recordings may not be playable everywhere. If you need
consistent playback across targets, convert recordings to a single format outside this plugin. The plugin focuses on
recording only and does not perform format conversion.

## Playback

To play a recording, prefer `uri` when available. On native platforms, pass it through
`Capacitor.convertFileSrc` before using it in the web view.

```typescript
import {Capacitor} from '@capacitor/core';

const {recordDataBase64, mimeType, uri} = result.value;
const source = uri
    ? Capacitor.convertFileSrc(uri)
    : `data:${mimeType};base64,${recordDataBase64}`;

const audioRef = new Audio(source);
audioRef.oncanplaythrough = () => audioRef.play();
audioRef.load();
```

## Troubleshooting

### Common error codes

The plugin rejects with error codes; check `error.code` (native) or `error.message` (web). Not all codes apply to every
platform.

| Code                                | Platform(s)       | Typical cause                                                                   |
|-------------------------------------|-------------------|---------------------------------------------------------------------------------|
| `MISSING_PERMISSION`                | iOS, Android, Web | Microphone permission is not granted.                                           |
| `ALREADY_RECORDING`                 | iOS, Android, Web | `startRecording()` called while already recording.                              |
| `DEVICE_CANNOT_VOICE_RECORD`        | iOS, Android, Web | The device or browser cannot record audio.                                      |
| `FAILED_TO_RECORD`                  | iOS, Android, Web | Recording failed to start or continue.                                          |
| `RECORDING_HAS_NOT_STARTED`         | iOS, Android, Web | `stopRecording()`, `pauseRecording()`, or `resumeRecording()` called too early. |
| `EMPTY_RECORDING`                   | iOS, Android, Web | Recording stopped too quickly or produced no data.                              |
| `FAILED_TO_FETCH_RECORDING`         | iOS, Android, Web | The recording could not be read back.                                           |
| `FAILED_TO_MERGE_RECORDING`         | iOS               | Interrupted recording segments failed to merge.                                 |
| `MICROPHONE_BEING_USED`             | Android           | The microphone is busy or held by another app.                                  |
| `NOT_SUPPORTED_OS_VERSION`          | Android           | Pause/resume is not supported on the current OS version.                        |
| `COULD_NOT_QUERY_PERMISSION_STATUS` | Web               | Permissions API is unavailable.                                                 |

## Origins and credit

This codebase originated as a fork of [
`tchvu3/capacitor-voice-recorder`](https://github.com/tchvu3/capacitor-voice-recorder) (thanks to Avihu Harush for
the original implementation), which [Independo GmbH](https://www.independo.app/) later re-architected for improved
performance, reliability, and testability (service/adapters split, contract tests, a normalized response path) and
published as `@independo/capacitor-voice-recorder`.

**This repository is now an independently owned codebase**, maintained as `@easelynow/capacitor-audit-recorder`. It
is not tracked as a fork, does not pull upstream changes, and has no dependency on either predecessor project going
forward — all future development (Android/iOS parity, the continuous segmented audit-recording mode described in
[Overview](#overview) and [Platform behaviors](#platform-behaviors), and everything after) happens here
independently. The history above is credited for provenance only.
