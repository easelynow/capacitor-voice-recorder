package app.independo.capacitorvoicerecorder;

import android.Manifest;
import android.content.Intent;
import app.independo.capacitorvoicerecorder.adapters.PermissionChecker;
import app.independo.capacitorvoicerecorder.adapters.RecordDataMapper;
import app.independo.capacitorvoicerecorder.adapters.RecorderPlatform;
import app.independo.capacitorvoicerecorder.core.ErrorCodes;
import app.independo.capacitorvoicerecorder.core.Messages;
import app.independo.capacitorvoicerecorder.core.RecordData;
import app.independo.capacitorvoicerecorder.core.RecordOptions;
import app.independo.capacitorvoicerecorder.core.ResponseFormat;
import app.independo.capacitorvoicerecorder.core.ResponseGenerator;
import app.independo.capacitorvoicerecorder.core.SegmentInfo;
import app.independo.capacitorvoicerecorder.platform.DefaultRecorderPlatform;
import app.independo.capacitorvoicerecorder.platform.RecordingForegroundService;
import app.independo.capacitorvoicerecorder.service.VoiceRecorderService;
import app.independo.capacitorvoicerecorder.service.VoiceRecorderServiceException;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(
    name = "VoiceRecorder",
    permissions = { @Permission(alias = VoiceRecorder.RECORD_AUDIO_ALIAS, strings = { Manifest.permission.RECORD_AUDIO }) }
)
public class VoiceRecorder extends Plugin {

    static final String RECORD_AUDIO_ALIAS = "voice recording";
    private VoiceRecorderService service;
    private ResponseFormat responseFormat;

    @Override
    public void load() {
        super.load();
        responseFormat = ResponseFormat.fromConfig(getConfig());
        RecorderPlatform platform = new DefaultRecorderPlatform(getContext());
        PermissionChecker permissionChecker = this::doesUserGaveAudioRecordingPermission;
        service = new VoiceRecorderService(platform, permissionChecker);
        RecordingForegroundService.VoiceRecorderServiceHolder.getInstance().setService(service);
    }

    @PluginMethod
    public void canDeviceVoiceRecord(PluginCall call) {
        call.resolve(ResponseGenerator.fromBoolean(service.canDeviceVoiceRecord()));
    }

    @PluginMethod
    public void requestAudioRecordingPermission(PluginCall call) {
        if (service.hasAudioRecordingPermission()) {
            call.resolve(ResponseGenerator.successResponse());
        } else {
            requestPermissionForAlias(RECORD_AUDIO_ALIAS, call, "recordAudioPermissionCallback");
        }
    }

    @PermissionCallback
    private void recordAudioPermissionCallback(PluginCall call) {
        this.hasAudioRecordingPermission(call);
    }

    @PluginMethod
    public void hasAudioRecordingPermission(PluginCall call) {
        call.resolve(ResponseGenerator.fromBoolean(service.hasAudioRecordingPermission()));
    }

    @PluginMethod
    public void startRecording(PluginCall call) {
        try {
            String directory = call.getString("directory");
            String subDirectory = call.getString("subDirectory");
            Integer segmentDurationMs = call.getInt("segmentDurationMs", 0);
            if (segmentDurationMs != null && segmentDurationMs == 0) {
                segmentDurationMs = null;
            }
            String sessionId = call.getString("sessionId");

            RecordOptions options = new RecordOptions(directory, subDirectory, segmentDurationMs, sessionId);

            boolean isSegmented = segmentDurationMs != null && segmentDurationMs > 0
                && directory != null && sessionId != null && !sessionId.isEmpty();

            service.startRecording(
                options,
                () -> notifyListeners("voiceRecordingInterrupted", null),
                () -> notifyListeners("voiceRecordingInterruptionEnded", null),
                segmentInfo -> {
                    JSObject data = new JSObject();
                    data.put("sessionId", segmentInfo.sessionId());
                    data.put("index", segmentInfo.index());
                    data.put("uri", segmentInfo.uri());
                    data.put("fileName", segmentInfo.fileName());
                    data.put("msDuration", segmentInfo.msDuration());
                    data.put("mimeType", segmentInfo.mimeType());
                    notifyListeners("segmentReady", data);
                }
            );

            if (isSegmented) {
                startForegroundService();
            }

            call.resolve(ResponseGenerator.successResponse());
        } catch (VoiceRecorderServiceException exp) {
            call.reject(toLegacyMessage(exp.getCode()), exp.getCode(), exp);
        }
    }

    @PluginMethod
    public void stopRecording(PluginCall call) {
        try {
            RecordData recordData = service.stopRecording();

            stopForegroundService();

            if (responseFormat == ResponseFormat.NORMALIZED) {
                call.resolve(ResponseGenerator.dataResponse(RecordDataMapper.toNormalizedJSObject(recordData)));
            } else {
                call.resolve(ResponseGenerator.dataResponse(RecordDataMapper.toLegacyJSObject(recordData)));
            }
        } catch (VoiceRecorderServiceException exp) {
            stopForegroundService();
            call.reject(toLegacyMessage(exp.getCode()), exp.getCode(), exp);
        }
    }

    @PluginMethod
    public void pauseRecording(PluginCall call) {
        try {
            call.resolve(ResponseGenerator.fromBoolean(service.pauseRecording()));
        } catch (VoiceRecorderServiceException exception) {
            call.reject(toLegacyMessage(exception.getCode()), exception.getCode(), exception);
        }
    }

    @PluginMethod
    public void resumeRecording(PluginCall call) {
        try {
            call.resolve(ResponseGenerator.fromBoolean(service.resumeRecording()));
        } catch (VoiceRecorderServiceException exception) {
            call.reject(toLegacyMessage(exception.getCode()), exception.getCode(), exception);
        }
    }

    @PluginMethod
    public void getCurrentStatus(PluginCall call) {
        call.resolve(ResponseGenerator.statusResponse(service.getCurrentStatus()));
    }

    @PluginMethod
    public void getCurrentAmplitude(PluginCall call) {
        call.resolve(ResponseGenerator.dataResponse(service.getCurrentAmplitude()));
    }

    private boolean doesUserGaveAudioRecordingPermission() {
        return getPermissionState(VoiceRecorder.RECORD_AUDIO_ALIAS).equals(PermissionState.GRANTED);
    }

    private String toLegacyMessage(String canonicalCode) {
        if (ErrorCodes.DEVICE_CANNOT_VOICE_RECORD.equals(canonicalCode)) {
            return Messages.CANNOT_RECORD_ON_THIS_PHONE;
        }
        return canonicalCode;
    }

    private void startForegroundService() {
        Intent intent = new Intent(getContext(), RecordingForegroundService.class);
        getContext().startForegroundService(intent);
    }

    private void stopForegroundService() {
        Intent intent = new Intent(getContext(), RecordingForegroundService.class);
        getContext().stopService(intent);
    }
}