import Foundation
import AVFoundation

private let m4aFileExtension = "m4a"

protocol AudioSessionProtocol: AnyObject {
    var category: AVAudioSession.Category { get }
    func setCategory(_ category: AVAudioSession.Category) throws
    func setCategory(_ category: AVAudioSession.Category, mode: AVAudioSession.Mode, options: AVAudioSession.CategoryOptions) throws
    func setActive(_ active: Bool, options: AVAudioSession.SetActiveOptions) throws
}

protocol AudioRecorderProtocol: AnyObject {
    var isRecording: Bool { get }
    var isMeteringEnabled: Bool { get set }
    @discardableResult
    func record() -> Bool
    func stop()
    func pause()
    func updateMeters()
    func averagePower(forChannel channelNumber: Int) -> Float
}

typealias AudioRecorderFactory = (_ url: URL, _ settings: [String: Any]) throws -> AudioRecorderProtocol

extension AVAudioSession: AudioSessionProtocol {}
// AVAudioRecorder already provides `isRecording` natively — no witness needed.
extension AVAudioRecorder: AudioRecorderProtocol {}

/// AVAudioRecorder wrapper that supports interruptions and segment merging.
class CustomMediaRecorder: RecorderAdapter {

    private let audioSessionProvider: () -> AudioSessionProtocol
    private let audioRecorderFactory: AudioRecorderFactory

    /// Options provided by the service layer.
    public var options: RecordOptions?
    /// Active audio session for recording.
    private var recordingSession: AudioSessionProtocol!
    /// Active recorder instance for the current segment.
    private var audioRecorder: AudioRecorderProtocol!
    /// Base file path for the merged recording.
    private var baseAudioFilePath: URL!
    /// List of segment files created during interruptions.
    private var audioFileSegments: [URL] = []
    /// Audio session category before recording starts.
    private var originalRecordingSessionCategory: AVAudioSession.Category!
    /// Current recording status.
    private var status = CurrentRecordingStatus.NONE
    /// Notification observer for audio interruptions.
    private var interruptionObserver: NSObjectProtocol?
    /// Callback invoked when interruptions begin.
    var onInterruptionBegan: (() -> Void)?
    /// Callback invoked when interruptions end.
    var onInterruptionEnded: (() -> Void)?
    /// Callback invoked when a segment is ready.
    var onSegmentReady: ((SegmentInfo) -> Void)?

    // MARK: - Segmented Recording Properties

    /// Serial queue for thread-safe access to recording state.
    private let stateQueue = DispatchQueue(label: "com.easelynow.auditrecorder.state")

    /// Whether segmented recording mode is active.
    private var isSegmentedMode = false
    /// Timer for rotating audio segments.
    private var segmentTimer: DispatchSourceTimer?
    /// Tracks whether the timer is currently suspended (for balanced suspend/resume).
    private var isTimerSuspended = false
    /// Current segment index in segmented mode.
    private var currentSegmentIndex = 0
    /// Session ID for segmented recording.
    private var sessionId: String = ""
    /// Current segment file URL.
    private var currentSegmentURL: URL?

    /// Recorder settings used for all segments.
    private let settings: [String: Any] = [
        AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
        AVSampleRateKey: 44100,
        AVNumberOfChannelsKey: 1,
        AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
    ]

    init(
        audioSessionProvider: @escaping () -> AudioSessionProtocol = { AVAudioSession.sharedInstance() },
        audioRecorderFactory: @escaping AudioRecorderFactory = { url, settings in
            return try AVAudioRecorder(url: url, settings: settings)
        }
    ) {
        self.audioSessionProvider = audioSessionProvider
        self.audioRecorderFactory = audioRecorderFactory
    }

    /// Resolves the directory where audio files should be saved.
    private func getDirectoryToSaveAudioFile() -> URL {
        if options?.directory != nil,
           let directory = getDirectory(directory: options?.directory),
           var outputDirURL = FileManager.default.urls(for: directory, in: .userDomainMask).first {
            if let subDirectory = options?.subDirectory?.trimmingCharacters(in: CharacterSet(charactersIn: "/")) {
                outputDirURL = outputDirURL.appendingPathComponent(subDirectory, isDirectory: true)

                do {
                    if !FileManager.default.fileExists(atPath: outputDirURL.path) {
                        try FileManager.default.createDirectory(at: outputDirURL, withIntermediateDirectories: true)
                    }
                } catch {
                    print("Error creating directory: \(error)")
                }
            }

            return outputDirURL
        }

        return URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
    }

    /// Starts recording audio and prepares the session.
    public func startRecording(recordOptions: RecordOptions?) -> Bool {
        do {
            options = recordOptions
            recordingSession = audioSessionProvider()
            originalRecordingSessionCategory = recordingSession.category

            // Configure audio session with background-friendly options
            try recordingSession.setCategory(
                AVAudioSession.Category.playAndRecord,
                mode: .default,
                options: [.allowBluetooth, .defaultToSpeaker, .mixWithOthers]
            )
            try recordingSession.setActive(true, options: [])

            // Check if segmented mode should be enabled
            let segmentDurationMs = options?.segmentDurationMs ?? 0
            let hasDirectory = options?.directory != nil
            let sessionIdValue = options?.sessionId ?? ""

            isSegmentedMode = segmentDurationMs > 0 && hasDirectory && !sessionIdValue.isEmpty
            sessionId = sessionIdValue

            if isSegmentedMode {
                // Segmented mode: start first segment
                currentSegmentIndex = 0
                let directory = getDirectoryToSaveAudioFile()
                currentSegmentURL = directory.appendingPathComponent(
                    "audio_\(sessionId)_\(currentSegmentIndex).\(m4aFileExtension)"
                )
                baseAudioFilePath = currentSegmentURL
                audioFileSegments = [baseAudioFilePath]

                audioRecorder = try audioRecorderFactory(baseAudioFilePath!, settings)
                audioRecorder.isMeteringEnabled = true

                // Start segment timer
                startSegmentTimer(intervalMs: segmentDurationMs)
            } else {
                // Legacy mode: single file recording
                baseAudioFilePath = getDirectoryToSaveAudioFile().appendingPathComponent(
                    "recording-\(Int(Date().timeIntervalSince1970 * 1000)).\(m4aFileExtension)"
                )
                audioFileSegments = [baseAudioFilePath]
                audioRecorder = try audioRecorderFactory(baseAudioFilePath, settings)
                audioRecorder.isMeteringEnabled = true
            }

            setupInterruptionHandling()
            audioRecorder.record()
            status = CurrentRecordingStatus.RECORDING
            return true
        } catch {
            return false
        }
    }

    /// Starts the segment timer for segmented recording.
    /// FIX 2: Use stateQueue for timer so rotation fires on stateQueue
    private func startSegmentTimer(intervalMs: Int) {
        let interval = DispatchTimeInterval.milliseconds(intervalMs)
        segmentTimer = DispatchSource.makeTimerSource(queue: stateQueue)
        segmentTimer?.schedule(deadline: .now() + interval, repeating: interval)
        segmentTimer?.setEventHandler { [weak self] in
            self?.rotateSegment()
        }
        segmentTimer?.resume()
    }

    /// Rotates to a new audio segment.
    /// FIX 2 & 3: This runs on stateQueue - do NOT call stateQueue.sync here (deadlock risk)
    private func rotateSegment() {
        // FIX 2: Guard status at entry (runs on stateQueue)
        guard status == .RECORDING else { return }
        guard isSegmentedMode, let currentURL = currentSegmentURL else { return }

        // FIX 2: Check recorder isRecording before stopping
        guard audioRecorder.isRecording else { return }

        // Stop current recorder to finalize the file
        audioRecorder.stop()

        // Calculate duration of the completed segment
        let msDuration = getDurationMs(for: currentURL)

        // Emit segment ready event
        let segmentInfo = SegmentInfo(
            sessionId: sessionId,
            index: currentSegmentIndex,
            uri: currentURL.path,
            fileName: currentURL.lastPathComponent,
            msDuration: msDuration,
            mimeType: "audio/mp4"
        )
        onSegmentReady?(segmentInfo)

        // Move to next segment
        currentSegmentIndex += 1
        let directory = getDirectoryToSaveAudioFile()
        currentSegmentURL = directory.appendingPathComponent(
            "audio_\(sessionId)_\(currentSegmentIndex).\(m4aFileExtension)"
        )
        baseAudioFilePath = currentSegmentURL
        audioFileSegments.append(baseAudioFilePath!)

        // Create new recorder for next segment
        do {
            audioRecorder = try audioRecorderFactory(baseAudioFilePath!, settings)
            audioRecorder.isMeteringEnabled = true
            audioRecorder.record()
        } catch {
            // FIX 3: Handle recorder factory failure - cancel timer, set safe status, notify error
            print("Error creating new segment recorder: \(error)")
            // Cancel timer (respecting suspension state)
            if isTimerSuspended {
                segmentTimer?.resume()
            }
            segmentTimer?.cancel()
            segmentTimer = nil
            // Set status to interrupted so subsequent calls are safe
            status = CurrentRecordingStatus.INTERRUPTED
            // Notify JS that recording halted due to error
            onInterruptionBegan?()
        }
    }

    /// Gets duration in milliseconds for a file URL.
    private func getDurationMs(for url: URL) -> Int {
        let asset = AVURLAsset(url: url)
        let durationSeconds = CMTimeGetSeconds(asset.duration)
        if durationSeconds.isNaN || durationSeconds < 0 {
            return 0
        }
        return Int(durationSeconds * 1000)
    }

    /// Stops recording and merges segments if needed.
    /// FIX 1 & 2: Handle suspended timer, serialize access through stateQueue
    public func stopRecording(completion: @escaping (Bool) -> Void) {
        // FIX 2: serialize teardown + final segment emission with the rotation timer.
        let segmentedStopDone: Bool = stateQueue.sync {
            // FIX 1: resume a suspended timer before cancel (canceling suspended timer crashes)
            if isTimerSuspended {
                segmentTimer?.resume()
                isTimerSuspended = false
            }
            segmentTimer?.cancel()
            segmentTimer = nil

            removeInterruptionHandling()
            audioRecorder.stop()

            // In segmented mode, emit final segment and skip merge
            guard isSegmentedMode, let currentURL = currentSegmentURL else { return false }

            let msDuration = getDurationMs(for: currentURL)
            let segmentInfo = SegmentInfo(
                sessionId: sessionId,
                index: currentSegmentIndex,
                uri: currentURL.path,
                fileName: currentURL.lastPathComponent,
                msDuration: msDuration,
                mimeType: "audio/mp4"
            )
            onSegmentReady?(segmentInfo)

            // Clean up and complete without merging
            do {
                try recordingSession.setActive(false, options: [])
                try recordingSession.setCategory(originalRecordingSessionCategory)
            } catch {
            }

            originalRecordingSessionCategory = nil
            audioRecorder = nil
            recordingSession = nil
            status = CurrentRecordingStatus.NONE
            isSegmentedMode = false
            return true
        }

        if segmentedStopDone {
            completion(true)
            return
        }

        // Legacy mode: merge if needed
        let finalizeStop: (Bool) -> Void = { [weak self] success in
            guard let self = self else {
                completion(false)
                return
            }
            do {
                try self.recordingSession.setActive(false, options: [])
                try self.recordingSession.setCategory(self.originalRecordingSessionCategory)
            } catch {
            }
            self.originalRecordingSessionCategory = nil
            self.audioRecorder = nil
            self.recordingSession = nil
            self.status = CurrentRecordingStatus.NONE
            completion(success)
        }

        if audioFileSegments.count > 1 {
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                guard let self = self else {
                    completion(false)
                    return
                }
                self.mergeAudioSegments { success in
                    finalizeStop(success)
                }
            }
        } else {
            finalizeStop(true)
        }
    }

    /// Returns the output file for the recording.
    public func getOutputFile() -> URL {
        return baseAudioFilePath
    }

    /// Maps directory strings to FileManager search paths.
    public func getDirectory(directory: String?) -> FileManager.SearchPathDirectory? {
        if let directory = directory {
            switch directory {
            case "CACHE":
                return .cachesDirectory
            case "LIBRARY":
                return .libraryDirectory
            default:
                return .documentDirectory
            }
        }
        return nil
    }

    /// Pauses recording when currently active.
    /// FIX 1: Guard timer suspend with isTimerSuspended flag
    public func pauseRecording() -> Bool {
        // FIX 2: serialize with the rotation timer
        return stateQueue.sync {
            if status == CurrentRecordingStatus.RECORDING {
                audioRecorder.pause()
                // FIX 1: Only suspend if not already suspended
                if isSegmentedMode && !isTimerSuspended {
                    segmentTimer?.suspend()
                    isTimerSuspended = true
                }
                status = CurrentRecordingStatus.PAUSED
                return true
            } else {
                return false
            }
        }
    }

    /// Resumes recording after pause or interruption.
    /// FIX 1 & 5: Guard timer resume with isTimerSuspended flag, make idempotent
    public func resumeRecording() -> Bool {
        // FIX 2: serialize with the rotation timer
        return stateQueue.sync {
            resumeRecordingLocked()
        }
    }

    /// Resume implementation — must only be called on stateQueue.
    private func resumeRecordingLocked() -> Bool {
        // FIX 5: Idempotent - return true if already recording
        if status == CurrentRecordingStatus.RECORDING {
            return true
        }
        if status == CurrentRecordingStatus.PAUSED || status == CurrentRecordingStatus.INTERRUPTED {
            let wasInterrupted = status == CurrentRecordingStatus.INTERRUPTED
            do {
                try recordingSession.setActive(true, options: [])

                if status == CurrentRecordingStatus.INTERRUPTED && !isSegmentedMode {
                    // Legacy mode: create new segment on interruption resume
                    let directory = getDirectoryToSaveAudioFile()
                    let timestamp = Int(Date().timeIntervalSince1970 * 1000)
                    let segmentNumber = audioFileSegments.count
                    let segmentPath = directory.appendingPathComponent(
                        "recording-\(timestamp)-segment-\(segmentNumber).\(m4aFileExtension)"
                    )
                    audioRecorder = try audioRecorderFactory(segmentPath, settings)
                    audioRecorder.isMeteringEnabled = true
                    audioFileSegments.append(segmentPath)
                }

                audioRecorder.record()

                // FIX 1: Only resume if suspended
                if isSegmentedMode && isTimerSuspended {
                    segmentTimer?.resume()
                    isTimerSuspended = false
                }

                status = CurrentRecordingStatus.RECORDING
                return true
            } catch {
                if wasInterrupted {
                    try? recordingSession.setActive(false, options: [])
                }
                return false
            }
        }

        return false
    }

    /// Returns the current recording status.
    public func getCurrentStatus() -> CurrentRecordingStatus {
        return status
    }

    /// Returns the current input amplitude normalized to [0, 1].
    public func getCurrentAmplitude() -> Double {
        guard status == CurrentRecordingStatus.RECORDING, let audioRecorder = audioRecorder else {
            return 0
        }

        audioRecorder.updateMeters()
        let power = audioRecorder.averagePower(forChannel: 0)
        if power <= -160 {
            return 0
        }
        return clampAmplitude(pow(10, Double(power) / 20))
    }

    /// Clamps platform-specific amplitude calculations into the public range.
    private func clampAmplitude(_ value: Double) -> Double {
        if !value.isFinite {
            return 0
        }
        return min(1, max(0, value))
    }

    /// Registers for interruption notifications.
    private func setupInterruptionHandling() {
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: recordingSession,
            queue: .main
        ) { [weak self] notification in
            self?.handleInterruption(notification: notification)
        }
    }

    /// Removes interruption observers.
    private func removeInterruptionHandling() {
        if let observer = interruptionObserver {
            NotificationCenter.default.removeObserver(observer)
            interruptionObserver = nil
        }
    }

    /// Handles audio session interruptions.
    /// FIX 1 & 5: Guard timer suspend with isTimerSuspended, auto-resume on .ended with shouldResume
    private func handleInterruption(notification: Notification) {
        guard let userInfo = notification.userInfo,
              let interruptionTypeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let interruptionType = AVAudioSession.InterruptionType(rawValue: interruptionTypeValue) else {
            return
        }

        // FIX 2: serialize interruption state changes with the rotation timer
        stateQueue.sync {
            switch interruptionType {
            case .began:
                if status == CurrentRecordingStatus.RECORDING {
                    audioRecorder.stop()

                    // FIX 1: Guard timer suspend with flag
                    if isSegmentedMode && !isTimerSuspended {
                        segmentTimer?.suspend()
                        isTimerSuspended = true
                    }

                    status = CurrentRecordingStatus.INTERRUPTED
                    onInterruptionBegan?()
                }

            case .ended:
                let wasInterrupted = status == CurrentRecordingStatus.INTERRUPTED

                // FIX 5: native auto-resume fallback when the system says .shouldResume
                if wasInterrupted,
                   let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt,
                   AVAudioSession.InterruptionOptions(rawValue: optionsValue).contains(.shouldResume) {
                    do {
                        try recordingSession.setActive(true, options: [])
                        audioRecorder.record()
                        if isSegmentedMode && isTimerSuspended {
                            segmentTimer?.resume()
                            isTimerSuspended = false
                        }
                        status = CurrentRecordingStatus.RECORDING
                    } catch {
                        // Auto-resume failed — stay INTERRUPTED; JS retries via resumeRecording()
                    }
                }

                // ALWAYS notify JS when an interruption ends (even after successful native
                // auto-resume) so the store can sync. resumeRecording() is idempotent.
                if wasInterrupted {
                    onInterruptionEnded?()
                }

            @unknown default:
                break
            }
        }
    }

    /// Merges recorded segments into a single file when interruptions occur.
    private func mergeAudioSegments(completion: @escaping (Bool) -> Void) {
        if audioFileSegments.count <= 1 {
            completion(true)
            return
        }

        let basePathWithoutExtension = baseAudioFilePath.deletingPathExtension()
        let mergedFilePath = basePathWithoutExtension.appendingPathExtension(m4aFileExtension)
        let segmentURLs = audioFileSegments
        let keys = ["tracks", "duration"]
        let dispatchGroup = DispatchGroup()
        let syncQueue = DispatchQueue(label: "CustomMediaRecorder.assetSyncQueue")
        var loadedAssets = Array<AVURLAsset?>(repeating: nil, count: segmentURLs.count)
        var loadFailed = false

        for (index, segmentURL) in segmentURLs.enumerated() {
            let asset = AVURLAsset(url: segmentURL)
            dispatchGroup.enter()
            asset.loadValuesAsynchronously(forKeys: keys) {
                var assetIsValid = true
                for key in keys {
                    var error: NSError?
                    if asset.statusOfValue(forKey: key, error: &error) != .loaded {
                        assetIsValid = false
                        break
                    }
                }
                syncQueue.async {
                    if assetIsValid {
                        loadedAssets[index] = asset
                    } else {
                        loadFailed = true
                    }
                    dispatchGroup.leave()
                }
            }
        }

        dispatchGroup.notify(queue: DispatchQueue.global(qos: .userInitiated)) { [weak self] in
            guard let self = self else {
                completion(false)
                return
            }

            var assets: [AVURLAsset] = []
            var didFail = false
            syncQueue.sync {
                if loadFailed || loadedAssets.contains(where: { $0 == nil }) {
                    didFail = true
                } else {
                    assets = loadedAssets.compactMap { $0 }
                }
            }

            if didFail || assets.count != segmentURLs.count {
                completion(false)
                return
            }

            let composition = AVMutableComposition()
            guard let compositionAudioTrack = composition.addMutableTrack(
                withMediaType: .audio,
                preferredTrackID: kCMPersistentTrackID_Invalid
            ) else {
                completion(false)
                return
            }

            var insertTime = CMTime.zero

            for asset in assets {
                guard let assetTrack = asset.tracks(withMediaType: .audio).first else {
                    completion(false)
                    return
                }

                do {
                    let timeRange = CMTimeRange(start: .zero, duration: asset.duration)
                    try compositionAudioTrack.insertTimeRange(timeRange, of: assetTrack, at: insertTime)
                    insertTime = CMTimeAdd(insertTime, asset.duration)
                } catch {
                    completion(false)
                    return
                }
            }

            guard let exportSession = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetAppleM4A) else {
                completion(false)
                return
            }

            let tempDirectory = self.getDirectoryToSaveAudioFile()
            let tempPath = tempDirectory.appendingPathComponent(
                "temp-merged-\(Int(Date().timeIntervalSince1970 * 1000)).\(m4aFileExtension)"
            )

            exportSession.outputURL = tempPath
            exportSession.outputFileType = .m4a

            exportSession.exportAsynchronously {
                guard exportSession.status == .completed else {
                    completion(false)
                    return
                }

                if !FileManager.default.fileExists(atPath: tempPath.path) {
                    completion(false)
                    return
                }

                do {
                    if FileManager.default.fileExists(atPath: mergedFilePath.path) {
                        try FileManager.default.removeItem(at: mergedFilePath)
                    }
                    try FileManager.default.moveItem(at: tempPath, to: mergedFilePath)

                    for segmentURL in self.audioFileSegments {
                        if segmentURL != mergedFilePath && FileManager.default.fileExists(atPath: segmentURL.path) {
                            try? FileManager.default.removeItem(at: segmentURL)
                        }
                    }
                    self.baseAudioFilePath = mergedFilePath
                    completion(true)
                } catch {
                    if FileManager.default.fileExists(atPath: tempPath.path) {
                        try? FileManager.default.removeItem(at: tempPath)
                    }
                    completion(false)
                }
            }
        }
    }

    /// Flushes the current segment file to disk and emits segmentReady without stopping the session.
    /// FIX 4: Added terminating parameter - when true, stops recorder without restarting.
    /// Used on app termination so the in-progress segment is not lost.
    public func flushCurrentSegment(terminating: Bool = false, completion: @escaping (SegmentInfo?) -> Void) {
        // FIX 2: serialize with the rotation timer (completion is invoked synchronously inside)
        stateQueue.sync {
            guard isSegmentedMode, let currentURL = currentSegmentURL, status == .RECORDING else {
                completion(nil)
                return
            }

            // Stop recorder to flush file to disk
            audioRecorder.stop()
            let msDuration = getDurationMs(for: currentURL)
            let info = SegmentInfo(
                sessionId: sessionId,
                index: currentSegmentIndex,
                uri: currentURL.path,
                fileName: currentURL.lastPathComponent,
                msDuration: msDuration,
                mimeType: "audio/mp4"
            )
            onSegmentReady?(info)
            completion(info)

            // FIX 4: If terminating, do NOT restart recorder (app is dying)
            if terminating {
                // Clean up state for termination
                if isTimerSuspended {
                    segmentTimer?.resume()
                }
                segmentTimer?.cancel()
                segmentTimer = nil
                isTimerSuspended = false
                status = CurrentRecordingStatus.NONE
                return
            }

            // Restart recording into the next segment (normal flush behavior)
            currentSegmentIndex += 1
            let directory = getDirectoryToSaveAudioFile()
            currentSegmentURL = directory.appendingPathComponent(
                "audio_\(sessionId)_\(currentSegmentIndex).\(m4aFileExtension)"
            )
            baseAudioFilePath = currentSegmentURL
            audioFileSegments.append(baseAudioFilePath!)
            do {
                audioRecorder = try audioRecorderFactory(baseAudioFilePath!, settings)
                audioRecorder.isMeteringEnabled = true
                audioRecorder.record()
            } catch {
                print("Error restarting recorder after flush: \(error)")
            }
        }
    }

    deinit {
        // FIX 1: Resume timer before canceling if suspended (crashes otherwise)
        if isTimerSuspended {
            segmentTimer?.resume()
        }
        segmentTimer?.cancel()
        removeInterruptionHandling()
    }
}