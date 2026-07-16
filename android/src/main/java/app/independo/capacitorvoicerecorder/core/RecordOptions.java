package app.independo.capacitorvoicerecorder.core;

public record RecordOptions(String directory, String subDirectory, Integer segmentDurationMs, String sessionId) {
    public RecordOptions(String directory, String subDirectory) {
        this(directory, subDirectory, null, null);
    }
}