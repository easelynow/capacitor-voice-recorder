package app.independo.capacitorvoicerecorder.service;

import app.independo.capacitorvoicerecorder.adapters.PermissionChecker;
import app.independo.capacitorvoicerecorder.adapters.RecorderAdapter;
import app.independo.capacitorvoicerecorder.adapters.RecorderPlatform;
import app.independo.capacitorvoicerecorder.core.CurrentRecordingStatus;
import app.independo.capacitorvoicerecorder.core.ErrorCodes;
import app.independo.capacitorvoicerecorder.core.RecordData;
import app.independo.capacitorvoicerecorder.core.RecordOptions;
import app.independo.capacitorvoicerecorder.core.SegmentInfo;
import app.independo.capacitorvoicerecorder.platform.NotSupportedOsVersion;

import java.io.File;
import java.util.function.Consumer;

public class VoiceRecorderService {

    private final RecorderPlatform platform;
    private final PermissionChecker permissionChecker;
    private RecorderAdapter recorder;

    public VoiceRecorderService(RecorderPlatform platform, PermissionChecker permissionChecker) {
        this.platform = platform;
        this.permissionChecker = permissionChecker;
    }

    public boolean canDeviceVoiceRecord() {
        return platform.canDeviceVoiceRecord();
    }

    public boolean hasAudioRecordingPermission() {
        return permissionChecker.hasAudioPermission();
    }

    public void startRecording(
        RecordOptions options,
        Runnable onInterruptionBegan,
        Runnable onInterruptionEnded
    ) throws VoiceRecorderServiceException {
        startRecording(options, onInterruptionBegan, onInterruptionEnded, segmentInfo -> {});
    }

    public void startRecording(
        RecordOptions options,
        Runnable onInterruptionBegan,
        Runnable onInterruptionEnded,
        Consumer<SegmentInfo> onSegmentReady
    ) throws VoiceRecorderServiceException {
        if (!platform.canDeviceVoiceRecord()) {
            throw new VoiceRecorderServiceException(ErrorCodes.DEVICE_CANNOT_VOICE_RECORD);
        }

        if (!permissionChecker.hasAudioPermission()) {
            throw new VoiceRecorderServiceException(ErrorCodes.MISSING_PERMISSION);
        }

        if (platform.isMicrophoneOccupied()) {
            throw new VoiceRecorderServiceException(ErrorCodes.MICROPHONE_BEING_USED);
        }

        if (recorder != null) {
            throw new VoiceRecorderServiceException(ErrorCodes.ALREADY_RECORDING);
        }

        try {
            recorder = platform.createRecorder(options);
            recorder.setOnInterruptionBegan(onInterruptionBegan);
            recorder.setOnInterruptionEnded(onInterruptionEnded);
            recorder.setOnSegmentReady(onSegmentReady);
            recorder.startRecording();
        } catch (Exception exp) {
            recorder = null;
            throw new VoiceRecorderServiceException(ErrorCodes.FAILED_TO_RECORD, exp);
        }
    }

    public RecordData stopRecording() throws VoiceRecorderServiceException {
        if (recorder == null) {
            throw new VoiceRecorderServiceException(ErrorCodes.RECORDING_HAS_NOT_STARTED);
        }

        RecordOptions options = recorder.getRecordOptions();

        try {
            recorder.stopRecording();
            File recordedFile = recorder.getOutputFile();
            if (recordedFile == null) {
                throw new VoiceRecorderServiceException(ErrorCodes.FAILED_TO_FETCH_RECORDING);
            }

            String recordDataBase64 = null;
            String uri = null;
            if (options.directory() != null) {
                uri = platform.toUri(recordedFile);
            } else {
                recordDataBase64 = platform.readFileAsBase64(recordedFile);
            }

            int duration = platform.getDurationMs(recordedFile);

            String mimeType;
            String fileExtension;
            String fileName = recordedFile.getName();
            int dotIndex = fileName.lastIndexOf('.');
            if (dotIndex > 0) {
                fileExtension = fileName.substring(dotIndex + 1);
                mimeType = switch (fileExtension) {
                    case "m4a", "mp4" -> "audio/mp4";
                    case "aac" -> "audio/aac";
                    default -> "audio/aac";
                };
            } else {
                mimeType = "audio/aac";
                fileExtension = "aac";
            }

            RecordData recordData = new RecordData(recordDataBase64, duration, mimeType, fileExtension, uri);
            if ((recordDataBase64 == null && uri == null) || recordData.getMsDuration() < 0) {
                throw new VoiceRecorderServiceException(ErrorCodes.EMPTY_RECORDING);
            }

            return recordData;
        } catch (VoiceRecorderServiceException exp) {
            throw exp;
        } catch (Exception exp) {
            throw new VoiceRecorderServiceException(ErrorCodes.FAILED_TO_FETCH_RECORDING, exp);
        } finally {
            if (options.directory() == null && recorder != null) {
                recorder.deleteOutputFile();
            }
            recorder = null;
        }
    }

    public boolean pauseRecording() throws VoiceRecorderServiceException {
        if (recorder == null) {
            throw new VoiceRecorderServiceException(ErrorCodes.RECORDING_HAS_NOT_STARTED);
        }
        try {
            return recorder.pauseRecording();
        } catch (NotSupportedOsVersion exception) {
            throw new VoiceRecorderServiceException(ErrorCodes.NOT_SUPPORTED_OS_VERSION, exception);
        }
    }

    public boolean resumeRecording() throws VoiceRecorderServiceException {
        if (recorder == null) {
            throw new VoiceRecorderServiceException(ErrorCodes.RECORDING_HAS_NOT_STARTED);
        }
        try {
            return recorder.resumeRecording();
        } catch (NotSupportedOsVersion exception) {
            throw new VoiceRecorderServiceException(ErrorCodes.NOT_SUPPORTED_OS_VERSION, exception);
        }
    }

    public CurrentRecordingStatus getCurrentStatus() {
        if (recorder == null) {
            return CurrentRecordingStatus.NONE;
        }
        return recorder.getCurrentStatus();
    }

    public double getCurrentAmplitude() {
        if (recorder == null) {
            return 0;
        }
        return recorder.getCurrentAmplitude();
    }

    public void flushCurrentSegment(boolean terminating, Consumer<SegmentInfo> completion) {
        if (recorder == null) {
            if (completion != null) {
                completion.accept(null);
            }
            return;
        }
        recorder.flushCurrentSegment(terminating, completion);
    }
}