import Foundation

/// Optional output configuration for recordings.
struct RecordOptions {

    /// Directory name provided by the caller.
    public let directory: String?
    /// Subdirectory name provided by the caller.
    public let subDirectory: String?
    /// Duration of each audio segment in milliseconds (segmented recording mode).
    public let segmentDurationMs: Int?
    /// Unique session identifier for segmented recording.
    public let sessionId: String?

}
