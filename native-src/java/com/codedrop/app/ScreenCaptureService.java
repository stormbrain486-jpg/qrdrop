package com.codedrop.app; // ⚠️ must match your applicationId

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Android 10+ requires MediaProjection to run inside an active foreground service — without
 * this, the OS throws a SecurityException the moment you try to create the VirtualDisplay,
 * regardless of whether the user granted the screen-capture permission. This service exists
 * purely to satisfy that requirement; ScreenCapturePlugin starts it right before beginning a
 * capture and stops it when finished.
 */
public class ScreenCaptureService extends Service {

    private static final String CHANNEL_ID = "screen_capture_channel";
    private static final int NOTIFICATION_ID = 5501;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannelIfNeeded();

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("CodeDrop")
                .setContentText("Screen capture is active")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10+
            startForeground(NOTIFICATION_ID, notification);
        }
        return START_NOT_STICKY;
    }

    private void createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Screen Capture", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
