package app.independo.capacitorvoicerecorder.core;

/**
 * Information about a completed audio segment in segmented recording mode.
 *
 * @param sessionId Unique session identifier
 * @param index Zero-based index of this segment within the session
 * @param uri Full file path URI to the segment
 * @param fileName File name of the segment
 * @param msDuration Duration of this segment in milliseconds
 * @param mimeType MIME type of the segment file
 */
public record SegmentInfo(
    String sessionId,
    int index,
    String uri,
    String fileName,
    int msDuration,
    String mimeType
) {}