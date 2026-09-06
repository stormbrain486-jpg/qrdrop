package com.codedrop.app; // ⚠️ must match your applicationId

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

/**
 * Shows a small, draggable, always-on-top "📸" button using the SYSTEM_ALERT_WINDOW overlay
 * API, so a screenshot can be taken from any app, not just while CodeDrop is in the foreground —
 * this is the real, native equivalent of the old desktop-only "Floating capture" feature (which
 * used the Document Picture-in-Picture API and could never have worked on Android; that API
 * flatly doesn't exist on any mobile browser or WebView).
 *
 * Tapping the button calls straight into ScreenCapturePlugin.captureFromFloatingButton(), which
 * reuses a single already-authorized MediaProjection for the whole session (started once, in
 * ScreenCapturePlugin.enableFloatingCapture()) — so repeated taps never re-prompt for the
 * system screen-capture permission, only the very first tap after enabling it does.
 */
public class FloatingButtonService extends Service {
    private static final String CHANNEL_ID = "floating_button_channel";
    private static final int NOTIFICATION_ID = 5502;

    private WindowManager windowManager;
    private Button button;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannelIfNeeded();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("CodeDrop")
                .setContentText("Floating screenshot button is active — tap it to capture")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
        startForeground(NOTIFICATION_ID, notification);
        addOverlayButton();
    }

    private void addOverlayButton() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        button = new Button(this);
        button.setText("📸");
        button.setAlpha(0.9f);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 200;

        // Draggable, with a real click only registering if the finger didn't move much —
        // otherwise every drag would also fire a capture.
        button.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean moved = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x; initialY = params.y;
                        initialTouchX = event.getRawX(); initialTouchY = event.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true;
                        params.x = initialX + dx;
                        params.y = initialY + dy;
                        windowManager.updateViewLayout(button, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) v.performClick();
                        return true;
                }
                return false;
            }
        });
        button.setOnClickListener(v -> ScreenCapturePlugin.captureFromFloatingButton());
        windowManager.addView(button, params);
    }

    private void createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Floating Screenshot Button", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (windowManager != null && button != null) {
            try { windowManager.removeView(button); } catch (Exception ignored) {}
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
