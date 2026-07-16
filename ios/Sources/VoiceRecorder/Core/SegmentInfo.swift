import Foundation

/// Information about a completed audio segment in segmented recording mode.
struct SegmentInfo {
    /// Unique session identifier.
    let sessionId: String
    /// Zero-based index of this segment within the session.
    let index: Int
    /// Full file path to the segment.
    let uri: String
    /// File name of the segment.
    let fileName: String
    /// Duration of this segment in milliseconds.
    let msDuration: Int
    /// MIME type of the segment file.
    let mimeType: String
}