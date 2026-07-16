package app.independo.capacitorvoicerecorder.adapters;

import app.independo.capacitorvoicerecorder.core.CurrentRecordingStatus;
import app.independo.capacitorvoicerecorder.core.RecordOptions;
import app.independo.capacitorvoicerecorder.core.SegmentInfo;
import app.independo.capacitorvoicerecorder.platform.NotSupportedOsVersion;

import java.io.File;
import java.util.function.Consumer;

public interface RecorderAdapter {
    void setOnInterruptionBegan(Runnable callback);

    void setOnInterruptionEnded(Runnable callback);

    void startRecording();

    void stopRecording();

    boolean pauseRecording() throws NotSupportedOsVersion;

    boolean resumeRecording() throws NotSupportedOsVersion;

    CurrentRecordingStatus getCurrentStatus();

    double getCurrentAmplitude();

    File getOutputFile();

    RecordOptions getRecordOptions();

    boolean deleteOutputFile();

    void setOnSegmentReady(Consumer<SegmentInfo> callback);

    void flushCurrentSegment(boolean terminating, Consumer<SegmentInfo> completion);
}