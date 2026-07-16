package app.independo.capacitorvoicerecorder.platform;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import app.independo.capacitorvoicerecorder.R;
import app.independo.capacitorvoicerecorder.VoiceRecorder;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;

public class RecordingForegroundService extends Service {

    private static final String CHANNEL_ID = "voice_recorder_recording";
    private static final int NOTIFICATION_ID = 1001;

    private Handler mainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundWithNotification();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Voice Recording",
            android.app.NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Shows when audio is being recorded");
        channel.setSound(null, null);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void startForegroundWithNotification() {
        try {
            Notification notification = buildNotification();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            startForeground(NOTIFICATION_ID, new NotificationCompat.Builder(this, CHANNEL_ID).build());
        }
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, VoiceRecorder.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording in progress")
            .setContentText("Audio is being recorded")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);

        mainHandler.post(() -> {
            flushCurrentSegmentAndWriteMarker();
        });
    }

    private void flushCurrentSegmentAndWriteMarker() {
        try {
            VoiceRecorderServiceHolder serviceHolder = VoiceRecorderServiceHolder.getInstance();
            if (serviceHolder.getService() != null) {
                serviceHolder.getService().flushCurrentSegment(true, segmentInfo -> {
                    if (segmentInfo != null) {
                        writePendingFlushMarker(segmentInfo.sessionId(), segmentInfo.index(),
                            segmentInfo.fileName(), segmentInfo.uri(), segmentInfo.msDuration(),
                            segmentInfo.mimeType());
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            stopSelf();
        }
    }

    private void writePendingFlushMarker(String sessionId, int segmentIndex, String fileName,
                                          String path, int durationMs, String mimeType) {
        try {
            File segmentFile = new File(path.replace("file://", ""));
            File parentDir = segmentFile.getParentFile();

            if (parentDir != null) {
                File markerFile = new File(parentDir, "pending_flush_" + sessionId + ".json");

                JSONObject marker = new JSONObject();
                marker.put("sessionId", sessionId);
                marker.put("segmentIndex", segmentIndex);
                marker.put("fileName", fileName);
                marker.put("path", path);
                marker.put("durationMs", durationMs);
                marker.put("mimeType", mimeType);

                try (FileWriter writer = new FileWriter(markerFile)) {
                    writer.write(marker.toString());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static class VoiceRecorderServiceHolder {
        private static VoiceRecorderServiceHolder instance;
        private app.independo.capacitorvoicerecorder.service.VoiceRecorderService service;

        private VoiceRecorderServiceHolder() {}

        public static synchronized VoiceRecorderServiceHolder getInstance() {
            if (instance == null) {
                instance = new VoiceRecorderServiceHolder();
            }
            return instance;
        }

        public void setService(app.independo.capacitorvoicerecorder.service.VoiceRecorderService service) {
            this.service = service;
        }

        public app.independo.capacitorvoicerecorder.service.VoiceRecorderService getService() {
            return service;
        }
    }
}