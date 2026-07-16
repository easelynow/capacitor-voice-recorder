package app.independo.capacitorvoicerecorder.platform;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import app.independo.capacitorvoicerecorder.adapters.RecorderAdapter;
import app.independo.capacitorvoicerecorder.core.CurrentRecordingStatus;
import app.independo.capacitorvoicerecorder.core.RecordOptions;
import app.independo.capacitorvoicerecorder.core.SegmentInfo;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MediaRecorder wrapper that manages audio focus, interruptions, and (in segmented mode) gapless segment rotation.
 *
 * <p>Segmented-mode state (mediaRecorder, outputFile, nextOutputFile, currentSegmentIndex,
 * rotationPending, segmentStartTimeMs, remainingSegmentTimeMs, currentRecordingStatus) is only ever
 * mutated on {@link #rotationHandlerThread}. The MediaRecorder instance used in segmented mode is
 * created on that same thread so that {@code OnInfoListener} callbacks (which Android binds to the
 * thread that constructed the MediaRecorder) are also serialized onto it. Public methods called from
 * other threads (the Capacitor bridge thread) dispatch onto the rotation thread and block for the
 * result via {@link #runOnRotationThread(Callable)} — the Android analogue of iOS's
 * {@code stateQueue.sync}. Legacy (non-segmented) mode never creates a rotation thread, so all legacy
 * calls execute synchronously on the caller's thread exactly as before this class supported
 * segmentation. */
public class CustomMediaRecorder implements AudioManager.OnAudioFocusChangeListener, RecorderAdapter {

    private static final double MAX_MEDIA_RECORDER_AMPLITUDE = 32767.0;
    private static final String MIME_TYPE_MP4 = "audio/mp4";
    private static final String EXTENSION_MP4 = ".m4a";
    private static final long ROTATION_THREAD_TIMEOUT_SECONDS = 5;

    interface MediaRecorderFactory {
        MediaRecorder create();
    }

    interface AudioManagerProvider {
        AudioManager getAudioManager(Context context);
    }

    interface DirectoryProvider {
        File getDocumentsDirectory();
        File getFilesDir(Context context);
        File getCacheDir(Context context);
        File getExternalFilesDir(Context context);
        File getExternalStorageDirectory();
    }

    interface SdkIntProvider {
        int getSdkInt();
    }

    interface AudioFocusRequestFactory {
        AudioFocusRequest create(AudioManager.OnAudioFocusChangeListener listener);
    }

    interface MetadataRetrieverFactory {
        MediaMetadataRetriever create();
    }

    interface HandlerProvider {
        Handler createHandler(HandlerThread thread);
    }

    private static final class DefaultMediaRecorderFactory implements MediaRecorderFactory {
        @Override
        public MediaRecorder create() {
            return new MediaRecorder();
        }
    }

    private static final class DefaultAudioManagerProvider implements AudioManagerProvider {
        @Override
        public AudioManager getAudioManager(Context context) {
            return (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        }
    }

    private static final class DefaultDirectoryProvider implements DirectoryProvider {
        @Override
        public File getDocumentsDirectory() {
            return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        }

        @Override
        public File getFilesDir(Context context) {
            return context.getFilesDir();
        }

        @Override
        public File getCacheDir(Context context) {
            return context.getCacheDir();
        }

        @Override
        public File getExternalFilesDir(Context context) {
            return context.getExternalFilesDir(null);
        }

        @Override
        public File getExternalStorageDirectory() {
            return Environment.getExternalStorageDirectory();
        }
    }

    private static final class DefaultSdkIntProvider implements SdkIntProvider {
        @Override
        public int getSdkInt() {
            return Build.VERSION.SDK_INT;
        }
    }

    private static final class DefaultAudioFocusRequestFactory implements AudioFocusRequestFactory {
        @Override
        public AudioFocusRequest create(AudioManager.OnAudioFocusChangeListener listener) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

            return new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(listener)
                .build();
        }
    }

    private static final class DefaultMetadataRetrieverFactory implements MetadataRetrieverFactory {
        @Override
        public MediaMetadataRetriever create() {
            return new MediaMetadataRetriever();
        }
    }

    private static final class DefaultHandlerProvider implements HandlerProvider {
        @Override
        public Handler createHandler(HandlerThread thread) {
            return new Handler(thread.getLooper());
        }
    }

    private final Context context;
    private final RecordOptions options;
    private final MediaRecorderFactory mediaRecorderFactory;
    private final DirectoryProvider directoryProvider;
    private final SdkIntProvider sdkIntProvider;
    private final AudioFocusRequestFactory audioFocusRequestFactory;
    private final MetadataRetrieverFactory metadataRetrieverFactory;
    private final HandlerProvider handlerProvider;
    private MediaRecorder mediaRecorder;
    private File outputFile;
    private volatile CurrentRecordingStatus currentRecordingStatus = CurrentRecordingStatus.NONE;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private Runnable onInterruptionBegan;
    private Runnable onInterruptionEnded;
    private Consumer<SegmentInfo> onSegmentReady;

    /** True for the lifetime of this instance when constructed with valid segmentDurationMs/sessionId/directory. */
    private final boolean isSegmentedMode;
    /** Dedicated thread that owns all segmented-mode state. The MediaRecorder used in segmented mode
     *  is created on this thread so MediaRecorder.OnInfoListener callbacks land here too. */
    private HandlerThread rotationHandlerThread;
    private Handler rotationHandler;
    private int currentSegmentIndex;
    private File nextOutputFile;
    private boolean rotationPending;
    private Runnable rotationRunnable;
    /** Wall-clock (elapsedRealtime) at which the current segment's rotation window started. */
    private long segmentStartTimeMs;
    /** Remaining time in the current segment's rotation window, captured on pause/interruption so
     *  resume continues the same window instead of restarting a full segmentDurationMs window. */
    private long remainingSegmentTimeMs;

    public CustomMediaRecorder(Context context, RecordOptions options) throws IOException {
        this(
            context,
            options,
            new DefaultMediaRecorderFactory(),
            new DefaultAudioManagerProvider(),
            new DefaultDirectoryProvider(),
            new DefaultSdkIntProvider(),
            new DefaultAudioFocusRequestFactory(),
            new DefaultMetadataRetrieverFactory(),
            new DefaultHandlerProvider()
        );
    }

    CustomMediaRecorder(
        Context context,
        RecordOptions options,
        MediaRecorderFactory mediaRecorderFactory,
        AudioManagerProvider audioManagerProvider,
        DirectoryProvider directoryProvider,
        SdkIntProvider sdkIntProvider,
        AudioFocusRequestFactory audioFocusRequestFactory,
        MetadataRetrieverFactory metadataRetrieverFactory,
        HandlerProvider handlerProvider
    ) throws IOException {
        this.context = context;
        this.options = options;
        this.mediaRecorderFactory = mediaRecorderFactory;
        this.directoryProvider = directoryProvider;
        this.sdkIntProvider = sdkIntProvider;
        this.audioFocusRequestFactory = audioFocusRequestFactory;
        this.metadataRetrieverFactory = metadataRetrieverFactory;
        this.handlerProvider = handlerProvider;
        this.audioManager = audioManagerProvider.getAudioManager(context);

        this.isSegmentedMode = isSegmentedRecording();

        if (isSegmentedMode) {
            rotationHandlerThread = new HandlerThread("SegmentRotationThread");
            rotationHandlerThread.start();
            rotationHandler = handlerProvider.createHandler(rotationHandlerThread);
            try {
                // Created ON rotationHandlerThread so MediaRecorder binds its OnInfoListener
                // callback thread to it (Android posts info callbacks to the thread that
                // constructed the MediaRecorder, when that thread has a Looper).
                runOnRotationThread(() -> {
                    initializeSegmentedRecorder();
                    return null;
                });
            } catch (RuntimeException e) {
                rotationHandlerThread.quitSafely();
                rotationHandlerThread = null;
                rotationHandler = null;
                if (e.getCause() instanceof IOException) {
                    throw (IOException) e.getCause();
                }
                throw e;
            }
        } else {
            generateMediaRecorder();
        }
    }

    /** Backward-compat 7-arg constructor used by legacy (pre-segmentation) test doubles.
     *  Always constructs in legacy (non-segmented) mode semantics via the delegating options. */
    CustomMediaRecorder(
        Context context,
        RecordOptions options,
        MediaRecorderFactory mediaRecorderFactory,
        AudioManagerProvider audioManagerProvider,
        DirectoryProvider directoryProvider,
        SdkIntProvider sdkIntProvider,
        AudioFocusRequestFactory audioFocusRequestFactory
    ) throws IOException {
        this(
            context,
            options,
            mediaRecorderFactory,
            audioManagerProvider,
            directoryProvider,
            sdkIntProvider,
            audioFocusRequestFactory,
            new DefaultMetadataRetrieverFactory(),
            new DefaultHandlerProvider()
        );
    }

    /** Dispatches {@code action} onto {@link #rotationHandlerThread} and blocks for its result.
     *  In legacy (non-segmented) mode, or when already running on the rotation thread, executes
     *  {@code action} directly on the caller's thread — a no-op passthrough. This is the single
     *  synchronization point for all segmented-mode state mutation (the Android analogue of
     *  iOS's {@code stateQueue.sync}). */
    private <T> T runOnRotationThread(Callable<T> action) {
        if (!isSegmentedMode || rotationHandler == null || Thread.currentThread() == rotationHandlerThread) {
            try {
                return action.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        CountDownLatch latch = new CountDownLatch(1);
        Object[] resultHolder = new Object[1];
        Throwable[] errorHolder = new Throwable[1];
        rotationHandler.post(() -> {
            try {
                resultHolder[0] = action.call();
            } catch (Throwable t) {
                errorHolder[0] = t;
            } finally {
                latch.countDown();
            }
        });

        try {
            if (!latch.await(ROTATION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new RuntimeException("Timed out waiting for the recorder rotation thread");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        if (errorHolder[0] != null) {
            Throwable error = errorHolder[0];
            if (error instanceof RuntimeException) {
                throw (RuntimeException) error;
            }
            throw new RuntimeException(error);
        }

        @SuppressWarnings("unchecked")
        T result = (T) resultHolder[0];
        return result;
    }

    private boolean isSegmentedRecording() {
        Integer segmentDurationMs = options.segmentDurationMs();
        String sessionId = options.sessionId();
        String directory = options.directory();

        return segmentDurationMs != null
            && segmentDurationMs > 0
            && directory != null
            && sessionId != null
            && !sessionId.isEmpty();
    }

    /** Runs on {@link #rotationHandlerThread}. Creates the MediaRecorder for segment 0 and arms
     *  the OnInfoListener that seals rotated segments. */
    private void initializeSegmentedRecorder() throws IOException {
        mediaRecorder = mediaRecorderFactory.create();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setAudioEncodingBitRate(96000);
        mediaRecorder.setAudioSamplingRate(44100);

        File outputDir = resolveOutputDirectory();
        String sessionId = options.sessionId();
        currentSegmentIndex = 0;
        outputFile = new File(outputDir, String.format("audio_%s_0%s", sessionId, EXTENSION_MP4));

        mediaRecorder.setOutputFile(outputFile.getAbsolutePath());
        mediaRecorder.setOnInfoListener((mr, what, extra) -> {
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED) {
                // We are already on rotationHandlerThread here (see class javadoc); no dispatch needed.
                handleSegmentRotationComplete();
            }
        });

        mediaRecorder.prepare();
    }

    private File resolveOutputDirectory() throws IOException {
        String directory = options.directory();
        String subDirectory = options.subDirectory();

        File outputDir = getDirectory(directory);

        if (subDirectory != null) {
            Pattern pattern = Pattern.compile("^/?(.+[^/])/?$");
            Matcher matcher = pattern.matcher(subDirectory);
            if (matcher.matches()) {
                outputDir = new File(outputDir, matcher.group(1));
                if (!outputDir.exists()) {
                    outputDir.mkdirs();
                }
            }
        }

        return outputDir;
    }

    /** Runs on rotationHandlerThread. Requests a gapless rotation to the next segment file.
     *  Broad catch: MediaRecorder.setNextOutputFile can throw unchecked IllegalStateException on
     *  some OEMs/states; an uncaught exception here would crash the HandlerThread (and the app). */
    private void requestSegmentRotation() {
        if (mediaRecorder == null || currentRecordingStatus != CurrentRecordingStatus.RECORDING) {
            return;
        }

        try {
            String sessionId = options.sessionId();
            int nextIndex = currentSegmentIndex + 1;
            File outputDir = outputFile.getParentFile();
            nextOutputFile = new File(outputDir, String.format("audio_%s_%d%s", sessionId, nextIndex, EXTENSION_MP4));

            nextOutputFile.createNewFile();
            mediaRecorder.setNextOutputFile(nextOutputFile);
            rotationPending = true;
        } catch (Exception e) {
            // Matches iOS rotateSegment() failure handling: cancel the timer, mark INTERRUPTED so
            // subsequent calls are safe, and notify JS rather than crashing the rotation thread.
            rotationPending = false;
            cancelRotationTimer();
            currentRecordingStatus = CurrentRecordingStatus.INTERRUPTED;
            if (onInterruptionBegan != null) {
                onInterruptionBegan.run();
            }
        }
    }

    /** Runs on rotationHandlerThread (MediaRecorder.OnInfoListener callback thread). */
    private void handleSegmentRotationComplete() {
        if (!rotationPending) {
            return;
        }
        rotationPending = false;

        int durationMs = getSegmentDuration(outputFile);

        if (onSegmentReady != null) {
            SegmentInfo segmentInfo = new SegmentInfo(
                options.sessionId(),
                currentSegmentIndex,
                Uri.fromFile(outputFile).toString(),
                outputFile.getName(),
                durationMs,
                MIME_TYPE_MP4
            );
            onSegmentReady.accept(segmentInfo);
        }

        currentSegmentIndex++;
        outputFile = nextOutputFile;
        nextOutputFile = null;

        scheduleFullRotation();
    }

    private int getSegmentDuration(File file) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = metadataRetrieverFactory.create();
            retriever.setDataSource(file.getAbsolutePath());
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                return (int) Long.parseLong(durationStr);
            }
        } catch (Exception e) {
            // fall through to 0
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (IOException ignored) {
                }
            }
        }
        return 0;
    }

    /** Schedules a fresh full-duration rotation window (used at recording start and after every
     *  completed rotation). Resets both the remaining-time bookkeeping and the timer baseline. */
    private void scheduleFullRotation() {
        if (rotationHandler == null || rotationRunnable == null) {
            return;
        }

        Integer segmentDurationMs = options.segmentDurationMs();
        if (segmentDurationMs == null || segmentDurationMs <= 0) {
            return;
        }

        remainingSegmentTimeMs = segmentDurationMs;
        segmentStartTimeMs = SystemClock.elapsedRealtime();
        rotationHandler.postDelayed(rotationRunnable, remainingSegmentTimeMs);
    }

    /** Resumes the rotation window using previously captured {@link #remainingSegmentTimeMs}
     *  (set by {@link #captureRemainingSegmentTime()} on pause/interruption) instead of resetting
     *  to a full segmentDurationMs window. Mirrors iOS's DispatchSourceTimer suspend/resume, which
     *  natively preserves the remaining countdown; Handler.postDelayed has no such primitive, so
     *  the remaining time is tracked manually here. */
    private void scheduleRemainingRotation() {
        if (rotationHandler == null || rotationRunnable == null) {
            return;
        }
        segmentStartTimeMs = SystemClock.elapsedRealtime();
        rotationHandler.postDelayed(rotationRunnable, Math.max(remainingSegmentTimeMs, 0));
    }

    /** Captures how much of the current rotation window remains, based on elapsed time since it
     *  was last (re)scheduled. Called before cancelling the timer on pause/interruption. */
    private void captureRemainingSegmentTime() {
        Integer segmentDurationMs = options.segmentDurationMs();
        if (segmentDurationMs == null) {
            remainingSegmentTimeMs = 0;
            return;
        }
        long elapsed = SystemClock.elapsedRealtime() - segmentStartTimeMs;
        remainingSegmentTimeMs = Math.max(segmentDurationMs - elapsed, 0);
    }

    private void cancelRotationTimer() {
        if (rotationHandler != null && rotationRunnable != null) {
            rotationHandler.removeCallbacks(rotationRunnable);
        }
    }

    private void generateMediaRecorder() throws IOException {
        mediaRecorder = mediaRecorderFactory.create();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setAudioEncodingBitRate(96000);
        mediaRecorder.setAudioSamplingRate(44100);
        setRecorderOutputFile();
        mediaRecorder.prepare();
    }

    private void setRecorderOutputFile() throws IOException {
        File outputDir = directoryProvider.getCacheDir(context);

        String directory = options.directory();
        String subDirectory = options.subDirectory();

        if (directory != null) {
            outputDir = getDirectory(directory);
            if (subDirectory != null) {
                Pattern pattern = Pattern.compile("^/?(.+[^/])/?$");
                Matcher matcher = pattern.matcher(subDirectory);
                if (matcher.matches()) {
                    outputDir = new File(outputDir, matcher.group(1));
                    if (!outputDir.exists()) {
                        outputDir.mkdirs();
                    }
                }
            }
        }

        outputFile = File.createTempFile(String.format("recording-%d", System.currentTimeMillis()), ".aac", outputDir);

        if (directory == null) {
            outputFile.deleteOnExit();
        }

        mediaRecorder.setOutputFile(outputFile.getAbsolutePath());
    }

    private File getDirectory(String directory) {
        return switch (directory) {
            case "DOCUMENTS" -> directoryProvider.getDocumentsDirectory();
            case "DATA", "LIBRARY" -> directoryProvider.getFilesDir(context);
            case "CACHE" -> directoryProvider.getCacheDir(context);
            case "EXTERNAL" -> directoryProvider.getExternalFilesDir(context);
            case "EXTERNAL_STORAGE" -> directoryProvider.getExternalStorageDirectory();
            default -> null;
        };
    }

    @Override
    public void startRecording() {
        runOnRotationThread(() -> {
            startRecordingLocked();
            return null;
        });
    }

    private void startRecordingLocked() {
        requestAudioFocus();
        mediaRecorder.start();
        currentRecordingStatus = CurrentRecordingStatus.RECORDING;

        if (isSegmentedMode) {
            rotationRunnable = this::requestSegmentRotation;
            scheduleFullRotation();
        }
    }

    @Override
    public void stopRecording() {
        runOnRotationThread(() -> {
            stopRecordingLocked();
            return null;
        });
    }

    private void stopRecordingLocked() {
        if (isSegmentedMode) {
            cancelRotationTimer();
        }

        if (mediaRecorder == null) {
            abandonAudioFocus();
            currentRecordingStatus = CurrentRecordingStatus.NONE;
            return;
        }

        try {
            if (currentRecordingStatus == CurrentRecordingStatus.RECORDING
                || currentRecordingStatus == CurrentRecordingStatus.PAUSED
                || currentRecordingStatus == CurrentRecordingStatus.INTERRUPTED) {
                mediaRecorder.stop();

                if (isSegmentedMode && onSegmentReady != null) {
                    int durationMs = getSegmentDuration(outputFile);
                    SegmentInfo segmentInfo = new SegmentInfo(
                        options.sessionId(),
                        currentSegmentIndex,
                        Uri.fromFile(outputFile).toString(),
                        outputFile.getName(),
                        durationMs,
                        MIME_TYPE_MP4
                    );
                    onSegmentReady.accept(segmentInfo);
                }
            }
        } catch (IllegalStateException ignore) {
        } finally {
            mediaRecorder.release();
            mediaRecorder = null;
            abandonAudioFocus();
            currentRecordingStatus = CurrentRecordingStatus.NONE;

            if (rotationHandlerThread != null) {
                rotationHandlerThread.quitSafely();
                rotationHandlerThread = null;
                rotationHandler = null;
            }
        }
    }

    @Override
    public File getOutputFile() {
        return outputFile;
    }

    @Override
    public RecordOptions getRecordOptions() {
        return options;
    }

    @Override
    public boolean pauseRecording() throws NotSupportedOsVersion {
        if (sdkIntProvider.getSdkInt() < Build.VERSION_CODES.N) {
            throw new NotSupportedOsVersion();
        }
        return runOnRotationThread(this::pauseRecordingLocked);
    }

    private boolean pauseRecordingLocked() {
        if (currentRecordingStatus == CurrentRecordingStatus.RECORDING) {
            mediaRecorder.pause();

            if (isSegmentedMode) {
                captureRemainingSegmentTime();
                cancelRotationTimer();
            }

            currentRecordingStatus = CurrentRecordingStatus.PAUSED;
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean resumeRecording() throws NotSupportedOsVersion {
        if (sdkIntProvider.getSdkInt() < Build.VERSION_CODES.N) {
            throw new NotSupportedOsVersion();
        }
        return runOnRotationThread(this::resumeRecordingLocked);
    }

    private boolean resumeRecordingLocked() {
        if (currentRecordingStatus == CurrentRecordingStatus.PAUSED || currentRecordingStatus == CurrentRecordingStatus.INTERRUPTED) {
            requestAudioFocus();
            mediaRecorder.resume();
            currentRecordingStatus = CurrentRecordingStatus.RECORDING;

            if (isSegmentedMode) {
                scheduleRemainingRotation();
            }

            return true;
        } else {
            return false;
        }
    }

    @Override
    public CurrentRecordingStatus getCurrentStatus() {
        return currentRecordingStatus;
    }

    @Override
    public double getCurrentAmplitude() {
        if (currentRecordingStatus != CurrentRecordingStatus.RECORDING || mediaRecorder == null) {
            return 0;
        }

        try {
            return clampAmplitude(mediaRecorder.getMaxAmplitude() / MAX_MEDIA_RECORDER_AMPLITUDE);
        } catch (RuntimeException ignore) {
            return 0;
        }
    }

    @Override
    public boolean deleteOutputFile() {
        return outputFile != null && outputFile.delete();
    }

    public static boolean canPhoneCreateMediaRecorder(Context context) {
        return true;
    }

    private static boolean canPhoneCreateMediaRecorderWhileHavingPermission(Context context) {
        CustomMediaRecorder tempMediaRecorder = null;
        try {
            tempMediaRecorder = new CustomMediaRecorder(context, new RecordOptions(null, null));
            tempMediaRecorder.startRecording();
            tempMediaRecorder.stopRecording();
            return true;
        } catch (Exception exp) {
            return exp.getMessage().startsWith("stop failed");
        } finally {
            if (tempMediaRecorder != null) tempMediaRecorder.deleteOutputFile();
        }
    }

    private void requestAudioFocus() {
        if (audioManager == null) {
            return;
        }

        if (sdkIntProvider.getSdkInt() >= Build.VERSION_CODES.O) {
            audioFocusRequest = audioFocusRequestFactory.create(this);
            audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) {
            return;
        }

        if (sdkIntProvider.getSdkInt() >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
            audioFocusRequest = null;
        } else {
            audioManager.abandonAudioFocus(this);
        }
    }

    private static double clampAmplitude(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0;
        }
        return Math.min(1, Math.max(0, value));
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                runOnRotationThread(() -> {
                    handleAudioFocusLossLocked();
                    return null;
                });
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                runOnRotationThread(() -> {
                    handleAudioFocusGainLocked();
                    return null;
                });
                break;
            default:
                break;
        }
    }

    private void handleAudioFocusLossLocked() {
        // For voice recording, ducking still degrades captured audio, so treat all loss types as interruptions.
        if (currentRecordingStatus == CurrentRecordingStatus.RECORDING) {
            try {
                if (sdkIntProvider.getSdkInt() >= Build.VERSION_CODES.N) {
                    mediaRecorder.pause();
                    currentRecordingStatus = CurrentRecordingStatus.INTERRUPTED;

                    if (isSegmentedMode) {
                        captureRemainingSegmentTime();
                        cancelRotationTimer();
                    }

                    if (onInterruptionBegan != null) {
                        onInterruptionBegan.run();
                    }
                }
            } catch (Exception ignore) {
            }
        }
    }

    private void handleAudioFocusGainLocked() {
        if (currentRecordingStatus == CurrentRecordingStatus.INTERRUPTED) {
            if (onInterruptionEnded != null) {
                onInterruptionEnded.run();
            }
        }
    }

    public void setOnInterruptionBegan(Runnable callback) {
        this.onInterruptionBegan = callback;
    }

    public void setOnInterruptionEnded(Runnable callback) {
        this.onInterruptionEnded = callback;
    }

    @Override
    public void setOnSegmentReady(Consumer<SegmentInfo> callback) {
        this.onSegmentReady = callback;
    }

    @Override
    public void flushCurrentSegment(boolean terminating, Consumer<SegmentInfo> completion) {
        runOnRotationThread(() -> {
            flushCurrentSegmentLocked(terminating, completion);
            return null;
        });
    }

    private void flushCurrentSegmentLocked(boolean terminating, Consumer<SegmentInfo> completion) {
        if (!isSegmentedMode || currentRecordingStatus != CurrentRecordingStatus.RECORDING) {
            if (completion != null) {
                completion.accept(null);
            }
            return;
        }

        try {
            mediaRecorder.stop();

            int durationMs = getSegmentDuration(outputFile);
            SegmentInfo segmentInfo = new SegmentInfo(
                options.sessionId(),
                currentSegmentIndex,
                Uri.fromFile(outputFile).toString(),
                outputFile.getName(),
                durationMs,
                MIME_TYPE_MP4
            );
            if (onSegmentReady != null) {
                onSegmentReady.accept(segmentInfo);
            }

            if (terminating) {
                mediaRecorder.release();
                mediaRecorder = null;
                currentRecordingStatus = CurrentRecordingStatus.NONE;
                cancelRotationTimer();

                if (rotationHandlerThread != null) {
                    rotationHandlerThread.quitSafely();
                    rotationHandlerThread = null;
                    rotationHandler = null;
                }

                if (completion != null) {
                    completion.accept(segmentInfo);
                }
            } else {
                currentSegmentIndex++;

                String sessionId = options.sessionId();
                File outputDir = outputFile.getParentFile();
                outputFile = new File(outputDir, String.format("audio_%s_%d%s", sessionId, currentSegmentIndex, EXTENSION_MP4));

                mediaRecorder = mediaRecorderFactory.create();
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mediaRecorder.setAudioEncodingBitRate(96000);
                mediaRecorder.setAudioSamplingRate(44100);
                mediaRecorder.setOutputFile(outputFile.getAbsolutePath());
                mediaRecorder.setOnInfoListener((mr, what, extra) -> {
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED) {
                        handleSegmentRotationComplete();
                    }
                });

                mediaRecorder.prepare();
                mediaRecorder.start();

                scheduleFullRotation();

                if (completion != null) {
                    completion.accept(segmentInfo);
                }
            }
        } catch (Exception e) {
            if (completion != null) {
                completion.accept(null);
            }
        }
    }
}
