package app.independo.capacitorvoicerecorder.platform;

import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import app.independo.capacitorvoicerecorder.core.CurrentRecordingStatus;
import app.independo.capacitorvoicerecorder.core.RecordOptions;
import app.independo.capacitorvoicerecorder.core.SegmentInfo;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests for segmented-recording mode (gapless rotation via setNextOutputFile,
 *  pause/resume timer bookkeeping, flushCurrentSegment). Legacy single-file recording
 *  is covered by {@link CustomMediaRecorderTest}.
 *
 * <p>The rotation Handler is faked so no real android.os.Looper is ever touched:
 * {@code post} runs its Runnable synchronously on the calling (test) thread, and
 * {@code postDelayed} captures its Runnable for the test to invoke explicitly, giving
 * deterministic control over "the rotation timer firing" without real wall-clock waits.
 * {@code TimeProvider} is likewise faked (a fixed value) so no real
 * android.os.SystemClock.elapsedRealtime() call reaches the AGP unit-test stub jar, which
 * throws "not mocked" without Robolectric. */
public class CustomMediaRecorderSegmentedTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static final String SESSION_ID = "sess1";
    private static final int SEGMENT_DURATION_MS = 60000;

    /** Fake Handler + the last Runnable captured via postDelayed (the pending rotation), so
     *  tests can simulate the timer elapsing by invoking it directly. */
    private static final class FakeRotationHandler {
        final Handler handler = mock(Handler.class);
        final AtomicReference<Runnable> pendingRotation = new AtomicReference<>();

        FakeRotationHandler() {
            when(handler.post(any(Runnable.class))).thenAnswer(invocation -> {
                Runnable runnable = invocation.getArgument(0);
                runnable.run();
                return true;
            });
            when(handler.postDelayed(any(Runnable.class), anyLong())).thenAnswer(invocation -> {
                pendingRotation.set(invocation.getArgument(0));
                return true;
            });
            doAnswer(invocation -> {
                pendingRotation.set(null);
                return null;
            }).when(handler).removeCallbacks(any(Runnable.class));
        }

        void fireRotationTimer() {
            Runnable runnable = pendingRotation.get();
            assertNotNull("Expected a pending rotation timer callback", runnable);
            runnable.run();
        }
    }

    private static final class Harness {
        final MediaRecorder mediaRecorder;
        final MediaRecorder.OnInfoListener infoListener;
        final FakeRotationHandler rotationHandler;
        final CustomMediaRecorder recorder;
        final MediaRecorderFactoryStub mediaRecorderFactory;

        Harness(
            MediaRecorder mediaRecorder,
            MediaRecorder.OnInfoListener infoListener,
            FakeRotationHandler rotationHandler,
            CustomMediaRecorder recorder,
            MediaRecorderFactoryStub mediaRecorderFactory
        ) {
            this.mediaRecorder = mediaRecorder;
            this.infoListener = infoListener;
            this.rotationHandler = rotationHandler;
            this.recorder = recorder;
            this.mediaRecorderFactory = mediaRecorderFactory;
        }
    }

    /** MediaRecorderFactory stub that returns a fresh mock on every call (segment restarts after
     *  flushCurrentSegment(terminating=false) create a brand-new MediaRecorder) while tracking
     *  how many times it was invoked and exposing the most recently created mock. */
    private static final class MediaRecorderFactoryStub implements CustomMediaRecorder.MediaRecorderFactory {
        int createCount = 0;
        MediaRecorder lastCreated;

        @Override
        public MediaRecorder create() {
            createCount++;
            lastCreated = mock(MediaRecorder.class);
            return lastCreated;
        }
    }

    private static CustomMediaRecorder.DirectoryProvider fixedDirectoryProvider(File dir) {
        return new CustomMediaRecorder.DirectoryProvider() {
            @Override
            public File getDocumentsDirectory() {
                return dir;
            }

            @Override
            public File getFilesDir(Context context) {
                return dir;
            }

            @Override
            public File getCacheDir(Context context) {
                return dir;
            }

            @Override
            public File getExternalFilesDir(Context context) {
                return dir;
            }

            @Override
            public File getExternalStorageDirectory() {
                return dir;
            }
        };
    }

    /** Avoids ever calling real HandlerThread.start()/quitSafely() (both throw under the AGP
     *  unit-test stub jar without Robolectric) by overriding them to no-ops on a real HandlerThread
     *  subclass -- HandlerProvider already ignores the thread instance itself. */
    private static CustomMediaRecorder.HandlerThreadFactory fakeHandlerThreadFactory() {
        return name -> new HandlerThread(name) {
            @Override
            public synchronized void start() {
            }

            @Override
            public boolean quitSafely() {
                return true;
            }
        };
    }

    private Harness createSegmentedRecorder(RecordOptions options) throws Exception {
        Context context = mock(Context.class);
        MediaRecorderFactoryStub mediaRecorderFactory = new MediaRecorderFactoryStub();
        AudioManager audioManager = mock(AudioManager.class);
        AudioFocusRequest focusRequest = mock(AudioFocusRequest.class);
        File cacheDir = tempFolder.newFolder("segmented-" + System.nanoTime());

        CustomMediaRecorder.AudioManagerProvider audioManagerProvider = ignored -> audioManager;
        CustomMediaRecorder.DirectoryProvider directoryProvider = fixedDirectoryProvider(cacheDir);
        CustomMediaRecorder.SdkIntProvider sdkIntProvider = () -> android.os.Build.VERSION_CODES.N;
        CustomMediaRecorder.AudioFocusRequestFactory audioFocusRequestFactory = ignored -> focusRequest;
        MediaMetadataRetriever retriever = mock(MediaMetadataRetriever.class);
        when(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).thenReturn("5000");
        CustomMediaRecorder.MetadataRetrieverFactory metadataRetrieverFactory = () -> retriever;

        FakeRotationHandler rotationHandler = new FakeRotationHandler();
        CustomMediaRecorder.HandlerProvider handlerProvider = (HandlerThread thread) -> rotationHandler.handler;
        CustomMediaRecorder.TimeProvider timeProvider = () -> 0L;

        CustomMediaRecorder recorder = new CustomMediaRecorder(
            context,
            options,
            mediaRecorderFactory,
            audioManagerProvider,
            directoryProvider,
            sdkIntProvider,
            audioFocusRequestFactory,
            metadataRetrieverFactory,
            handlerProvider,
            timeProvider,
            fakeHandlerThreadFactory()
        );

        MediaRecorder firstRecorder = mediaRecorderFactory.lastCreated;
        MediaRecorder.OnInfoListener infoListener = null;
        boolean segmented = options.segmentDurationMs() != null && options.segmentDurationMs() > 0
            && options.directory() != null && options.sessionId() != null && !options.sessionId().isEmpty();
        if (segmented) {
            ArgumentCaptor<MediaRecorder.OnInfoListener> listenerCaptor = ArgumentCaptor.forClass(MediaRecorder.OnInfoListener.class);
            verify(firstRecorder).setOnInfoListener(listenerCaptor.capture());
            infoListener = listenerCaptor.getValue();
        }

        return new Harness(firstRecorder, infoListener, rotationHandler, recorder, mediaRecorderFactory);
    }

    private RecordOptions segmentedOptions() {
        return new RecordOptions("CACHE", null, SEGMENT_DURATION_MS, SESSION_ID);
    }

    @Test
    public void constructorConfiguresMpeg4OutputForFirstSegment() throws Exception {
        Harness harness = createSegmentedRecorder(segmentedOptions());

        verify(harness.mediaRecorder).setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        verify(harness.mediaRecorder).setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        verify(harness.mediaRecorder).prepare();
        assertTrue(harness.recorder.getOutputFile().getName().matches("audio_" + SESSION_ID + "_0\\.m4a"));
    }

    @Test
    public void nonSegmentedOptionsFallBackToLegacyMode() throws Exception {
        // segmentDurationMs missing -> legacy AAC_ADTS path, no rotation handler interaction.
        RecordOptions legacyOptions = new RecordOptions("CACHE", null, null, SESSION_ID);
        Harness harness = createSegmentedRecorder(legacyOptions);

        verify(harness.mediaRecorder).setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
        verify(harness.mediaRecorder, never()).setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        verify(harness.mediaRecorder, never()).setOnInfoListener(any());
    }

    @Test
    public void startRecordingSchedulesRotationTimer() throws Exception {
        Harness harness = createSegmentedRecorder(segmentedOptions());

        harness.recorder.startRecording();

        verify(harness.mediaRecorder).start();
        assertEquals(CurrentRecordingStatus.RECORDING, harness.recorder.getCurrentStatus());
        verify(harness.rotationHandler.handler).postDelayed(any(Runnable.class), anyLong());
    }

    @Test
    public void rotationTimerRequestsNextOutputFile() throws Exception {
        Harness harness = createSegmentedRecorder(segmentedOptions());
        harness.recorder.startRecording();

        harness.rotationHandler.fireRotationTimer();

        ArgumentCaptor<File> nextFileCaptor = ArgumentCaptor.forClass(File.class);
        verify(harness.mediaRecorder).setNextOutputFile(nextFileCaptor.capture());
        assertTrue(nextFileCaptor.getValue().getName().matches("audio_" + SESSION_ID + "_1\\.m4a"));
    }

    @Test
    public void infoListenerEmitsSegmentReadyAndAdvancesIndex() throws Exception {
        Harness harness = createSegmentedRecorder(segmentedOptions());
        harness.recorder.startRecording();
        harness.rotationHandler.fireRotationTimer();

        AtomicReference<SegmentInfo> captured = new AtomicReference<>();
        harness.recorder.setOnSegmentReady(captured::set);

        harness.infoListener.onInfo(harness.mediaRecorder, MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED, 0);

        SegmentInfo segmentInfo = captured.get();
        assertNotNull(segmentInfo);
        assertEquals(SESSION_ID, segmentInfo.sessionId());
        assertEquals(0, segmentInfo.index());
        assertEquals("audio/mp4", segmentInfo.mimeType());
        assertEquals(5000, segmentInfo.msDuration());
        assertTrue(segmentInfo.uri().startsWith("file://"));
        assertTrue(segmentInfo.fileName().matches("audio_" + SESSION_ID + "_0\\.m4a"));
        // A fresh full-duration rotation window is scheduled for the new (index 1) segment.
        verify(harness.rotationHandler.handler, times(2)).postDelayed(any(Runnable.class), anyLong());
    }

    @Test
    public void ignoresDuplicateInfoCallbackWithoutPendingRotation() throws Exception {
        Harness harness = createSegmentedRecorder(segmentedOptions());
        harness.recorder.startRecording();

        AtomicReference<SegmentInfo> captured = new AtomicReference<>();
        harness.recorder.setOnSegmentReady(captured::set);

        // Fires without a preceding fireRotationTimer(), so rotationPending is false.
        harness.infoListener.onInfo(harness.mediaRecorder, MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED, 0);

        assertNull(captured.get());
    }

    @Test
    public void pauseRecordingCancelsRotationTimer() throws Exception {
        Harness harness = createSegmentedRecorder(segmentedOptions());
        harness.recorder.startRecording();

        boolean paused = harness.recorder.pauseRecording();

        assertTrue(paused);
        verify(harness.mediaRecorder).pause();
        verify(harness.rotationHandler.handler).removeCallbacks(any(Runnable.class));
        assertEquals(CurrentRecordingStatus.PAUSED, harness.recorder.getCurrentStatus());
        assertNull(harness.rotationHandler.pendingRotation.get());
    }

    @Test
    public void resumeRecordingReschedulesRotationTimer() throws Exception {
        Harness harness = createSegmentedRecorder(segmentedOptions());
        harness.recorder.startRecording();
        harness.recorder.pauseRecording();

        boolean resumed = harness.recorder.resumeRecording();

        assertTrue(resumed);
        verify(harness.mediaRecorder).resume();
        assertEquals(CurrentRecordingStatus.RECORDING, harness.recorder.getCurrentStatus());
        assertNotNull("resume should reschedule the rotation timer", harness.rotationHandler.pendingRotation.get());
    }

    @Test
    public void interruptionCapturesRemainingTimeAndResumeReschedules() throws Exception {
        Harness harness = createSegmentedRecorder(segmentedOptions());
        harness.recorder.startRecording();
        Runnable interruptionBegan = mock(Runnable.class);
        Runnable interruptionEnded = mock(Runnable.class);
        harness.recorder.setOnInterruptionBegan(interruptionBegan);
        harness.recorder.setOnInterruptionEnded(interruptionEnded);

        harness.recorder.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);

        verify(harness.mediaRecorder).pause();
        verify(interruptionBegan).run();
        assertEquals(CurrentRecordingStatus.INTERRUPTED, harness.recorder.getCurrentStatus());
        assertNull("interruption should cancel the pending rotation", harness.rotationHandler.pendingRotation.get());

        harness.recorder.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        verify(interruptionEnded).run();

        boolean resumed = harness.recorder.resumeRecording();
        assertTrue(resumed);
        assertEquals(CurrentRecordingStatus.RECORDING, harness.recorder.getCurrentStatus());
        assertNotNull(harness.rotationHandler.pendingRotation.get());
    }

    @Test
    public void stopRecordingEmitsFinalSegmentWithoutRotatingAgain() throws Exception {
        Harness harness = createSegmentedRecorder(segmentedOptions());
        harness.recorder.startRecording();

        AtomicReference<SegmentInfo> captured = new AtomicReference<>();
        harness.recorder.setOnSegmentReady(captured::set);

        harness.recorder.stopRecording();

        verify(harness.mediaRecorder).stop();
        verify(harness.mediaRecorder).release();
        assertEquals(CurrentRecordingStatus.NONE, harness.recorder.getCurrentStatus());
        SegmentInfo finalSegment = captured.get();
        assertNotNull(finalSegment);
        assertEquals(0, finalSegment.index());
        // No further rotation should be scheduled once stopped.
        verify(harness.rotationHandler.handler).removeCallbacks(any(Runnable.class));
    }

    @Test
    public void flushCurrentSegmentTerminatingStopsWithoutRestarting() throws Exception {
        Harness harness = createSegmentedRecorder(segmentedOptions());
        harness.recorder.startRecording();

        AtomicReference<SegmentInfo> completionResult = new AtomicReference<>();
        Consumer<SegmentInfo> completion = completionResult::set;

        harness.recorder.flushCurrentSegment(true, completion);

        verify(harness.mediaRecorder).stop();
        verify(harness.mediaRecorder).release();
        assertEquals(CurrentRecordingStatus.NONE, harness.recorder.getCurrentStatus());
        assertNotNull(completionResult.get());
        assertEquals(0, completionResult.get().index());
        // Only the initial (segment 0) MediaRecorder was ever created -- terminating flush does not restart.
        assertEquals(1, harness.mediaRecorderFactory.createCount);
    }

    @Test
    public void flushCurrentSegmentNonTerminatingRestartsNextSegment() throws Exception {
        Harness harness = createSegmentedRecorder(segmentedOptions());
        harness.recorder.startRecording();

        AtomicReference<SegmentInfo> completionResult = new AtomicReference<>();
        Consumer<SegmentInfo> completion = completionResult::set;

        harness.recorder.flushCurrentSegment(false, completion);

        assertNotNull(completionResult.get());
        assertEquals(0, completionResult.get().index());
        assertEquals(CurrentRecordingStatus.RECORDING, harness.recorder.getCurrentStatus());
        // A new MediaRecorder was created for the restarted (index 1) segment.
        assertEquals(2, harness.mediaRecorderFactory.createCount);
        MediaRecorder restarted = harness.mediaRecorderFactory.lastCreated;
        verify(restarted).prepare();
        verify(restarted).start();
        assertTrue(harness.recorder.getOutputFile().getName().matches("audio_" + SESSION_ID + "_1\\.m4a"));
    }

    @Test
    public void flushCurrentSegmentWhenNotRecordingCompletesWithNull() throws Exception {
        Harness harness = createSegmentedRecorder(segmentedOptions());
        // Never started -- currentRecordingStatus is NONE.
        AtomicReference<SegmentInfo> completionResult = new AtomicReference<>();
        harness.recorder.flushCurrentSegment(true, completionResult::set);

        assertNull(completionResult.get());
    }

    @Test
    public void requestSegmentRotationFailureMarksInterruptedAndStopsRotating() throws Exception {
        Harness harness = createSegmentedRecorder(segmentedOptions());
        harness.recorder.startRecording();
        doThrow(new IllegalStateException("setNextOutputFile failed")).when(harness.mediaRecorder).setNextOutputFile(any(File.class));
        Runnable interruptionBegan = mock(Runnable.class);
        harness.recorder.setOnInterruptionBegan(interruptionBegan);

        harness.rotationHandler.fireRotationTimer();

        assertEquals(CurrentRecordingStatus.INTERRUPTED, harness.recorder.getCurrentStatus());
        verify(interruptionBegan).run();
        assertNull("failed rotation must not leave a pending timer", harness.rotationHandler.pendingRotation.get());
    }

    @Test
    public void pauseRecordingBelowMinSdkThrowsWithoutTouchingRotationState() throws Exception {
        Context context = mock(Context.class);
        MediaRecorderFactoryStub mediaRecorderFactory = new MediaRecorderFactoryStub();
        AudioManager audioManager = mock(AudioManager.class);
        AudioFocusRequest focusRequest = mock(AudioFocusRequest.class);
        File cacheDir = tempFolder.newFolder("segmented-old-sdk-" + System.nanoTime());
        CustomMediaRecorder.AudioManagerProvider audioManagerProvider = ignored -> audioManager;
        CustomMediaRecorder.DirectoryProvider directoryProvider = fixedDirectoryProvider(cacheDir);
        CustomMediaRecorder.SdkIntProvider oldSdkProvider = () -> android.os.Build.VERSION_CODES.M;
        CustomMediaRecorder.AudioFocusRequestFactory audioFocusRequestFactory = ignored -> focusRequest;
        MediaMetadataRetriever retriever = mock(MediaMetadataRetriever.class);
        CustomMediaRecorder.MetadataRetrieverFactory metadataRetrieverFactory = () -> retriever;
        FakeRotationHandler rotationHandler = new FakeRotationHandler();
        CustomMediaRecorder.HandlerProvider handlerProvider = (HandlerThread thread) -> rotationHandler.handler;
        CustomMediaRecorder.TimeProvider timeProvider = () -> 0L;

        CustomMediaRecorder recorder = new CustomMediaRecorder(
            context,
            segmentedOptions(),
            mediaRecorderFactory,
            audioManagerProvider,
            directoryProvider,
            oldSdkProvider,
            audioFocusRequestFactory,
            metadataRetrieverFactory,
            handlerProvider,
            timeProvider,
            fakeHandlerThreadFactory()
        );
        recorder.startRecording();

        try {
            recorder.pauseRecording();
            fail("Expected NotSupportedOsVersion");
        } catch (NotSupportedOsVersion expected) {
        }

        // The SDK-version guard must short-circuit before ever dispatching to the rotation thread.
        verify(rotationHandler.handler, never()).removeCallbacks(any(Runnable.class));
        assertFalse(recorder.getCurrentStatus() == CurrentRecordingStatus.PAUSED);
    }
}
