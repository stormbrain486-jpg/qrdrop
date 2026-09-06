package com.codedrop.app; // ⚠️ must match your applicationId / MainActivity's package

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Native replacement for the parts of the web app that call getDisplayMedia() — which Android's
 * WebView does not support — using Android's real MediaProjection API. Exposed to JS via
 * window.Capacitor.Plugins.ScreenCapture:
 *
 *   - takeScreenshot()        -> { base64: "<png data>" }   (one frame, for Snip / Full Screenshot)
 *   - startRecording()        -> starts recording to a video, saved to the Movies app when stopped
 *   - stopRecording()         -> { path: "<friendly location>" }
 *   - enableFloatingCapture() -> shows a draggable on-top button that takes + auto-saves a
 *                                screenshot on tap, from any app, without re-prompting each time
 *   - disableFloatingCapture()-> hides the floating button and releases everything
 *   - startLiveStream()       -> begins continuously emitting downscaled JPEG frames of the
 *                                screen as a "screenLiveFrame" plugin event, so JS can draw them
 *                                onto a <canvas> and turn that into a real MediaStream via
 *                                canvas.captureStream() — see nativeLiveScreenStream() in
 *                                index.html. This is what makes live "Share Screen" during a
 *                                call actually work on native Android (see fix #3 below).
 *   - stopLiveStream()        -> stops the frame stream and releases the projection
 *
 * FIXES vs. the original version of this file:
 *   1. Foreground-service race condition. MediaProjection requires an active foreground service
 *      of type mediaProjection at the moment createVirtualDisplay() is called. The old code
 *      called Context.startService() and then, in the very same synchronous call stack,
 *      immediately created the virtual display — but startService() only *posts* a message to
 *      start the service; the service's onStartCommand()/startForeground() doesn't actually run
 *      until the current call stack unwinds back to the main Looper. So the virtual display was
 *      being created before the foreground service had actually promoted itself, which is
 *      exactly the condition Android 10+ (and especially 14+) rejects. This is a well-known
 *      gotcha with MediaProjection sample code. The fix: start the foreground service BEFORE
 *      requesting the screen-capture consent intent, not after. Granting that system permission
 *      requires a real human tap, which takes far longer than one Looper cycle — by the time the
 *      result comes back, the service is definitely already in the foreground.
 *   2. Recordings were written to getCacheDir(), which is private to the app — invisible to any
 *      file manager, Photos app, or "Files" app. stopRecording() now copies the finished file
 *      into MediaStore's Movies collection (a real, user-visible location) and deletes the
 *      private temp copy.
 *   3. Live "Share Screen" during a call was previously disabled entirely on Android, because
 *      getDisplayMedia() (what the web version uses) doesn't exist in Android's WebView, and
 *      bundling a full native WebRTC video-capturer is a much bigger undertaking than a small
 *      plugin. This version adds a lighter-weight but genuinely working path instead: the
 *      MediaProjection ImageReader we already use for screenshots is kept open and continuously
 *      emits downscaled JPEG frames (throttled to ~8fps) to JS as "screenLiveFrame" events. JS
 *      draws each one onto an offscreen &lt;canvas&gt; and calls the canvas's own
 *      captureStream() — a standard Web API the WebView already supports — which yields a real
 *      MediaStream video track. That track is handed to the exact same PeerJS/WebRTC call code
 *      the rest of the app already uses for calls, so no native WebRTC library is needed at all;
 *      the WebView's built-in RTCPeerConnection does the actual encoding/sending.
 */
@CapacitorPlugin(name = "ScreenCapture")
public class ScreenCapturePlugin extends Plugin {

    private static final int MODE_SCREENSHOT = 0;
    private static final int MODE_RECORDING = 1;
    private static final int MODE_FLOATING = 2;
    private static final int MODE_LIVE_STREAM = 3;

    // Live-stream frames are capped to this interval so we don't flood the WebView JS bridge or
    // burn CPU JPEG-encoding faster than a call actually needs — ~8fps is plenty for a shared
    // screen (unlike a camera feed, screen content is mostly static between frames).
    private static final long LIVE_FRAME_MIN_INTERVAL_MS = 120;
    // Downscale long edge to this many px before encoding — full display resolution is total
    // overkill for what ends up as a WebRTC video track, and would make every JPEG far bigger
    // than it needs to be for no visible benefit at typical call viewing sizes.
    private static final int LIVE_STREAM_MAX_DIMENSION = 960;
    private long lastLiveFrameEmitMs = 0;
    private boolean liveStreamActive = false;

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private MediaRecorder mediaRecorder;
    private int screenWidth, screenHeight, screenDensity;
    private PluginCall pendingCall;
    private int pendingMode;
    private File currentRecordingFile;

    // Kept alive for the whole floating-button session so repeat taps never re-prompt.
    private static ScreenCapturePlugin activeInstance;

    @Override
    public void load() {
        activeInstance = this;
        projectionManager = (MediaProjectionManager) getContext()
                .getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE);
        refreshScreenMetrics();
    }

    private void refreshScreenMetrics() {
        WindowManager wm = (WindowManager) getContext().getSystemService(android.content.Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;
    }

    // ---------------------------------------------------------------- screenshot / recording

    @PluginMethod
    public void takeScreenshot(PluginCall call) {
        pendingMode = MODE_SCREENSHOT;
        pendingCall = call;
        refreshScreenMetrics();
        // Start the foreground service FIRST — see fix #1 above — well before the system
        // permission dialog even appears, let alone before the user responds to it.
        getContext().startService(new Intent(getContext(), ScreenCaptureService.class));
        Intent intent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(call, intent, "handleProjectionResult");
    }

    @PluginMethod
    public void startRecording(PluginCall call) {
        pendingMode = MODE_RECORDING;
        pendingCall = call;
        refreshScreenMetrics();
        getContext().startService(new Intent(getContext(), ScreenCaptureService.class));
        Intent intent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(call, intent, "handleProjectionResult");
    }

    @PluginMethod
    public void startLiveStream(PluginCall call) {
        pendingMode = MODE_LIVE_STREAM;
        pendingCall = call;
        refreshScreenMetrics();
        getContext().startService(new Intent(getContext(), ScreenCaptureService.class));
        Intent intent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(call, intent, "handleProjectionResult");
    }

    @PluginMethod
    public void stopLiveStream(PluginCall call) {
        liveStreamActive = false;
        teardownProjection();
        call.resolve();
    }

    @PluginMethod
    public void stopRecording(PluginCall call) {
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.reset();
                mediaRecorder.release();
                mediaRecorder = null;
            }
        } catch (Exception e) {
            call.reject("Failed to stop recording: " + e.getMessage());
            teardownProjection();
            return;
        }
        teardownProjection();

        if (currentRecordingFile == null || !currentRecordingFile.exists()) {
            call.reject("No recording file was produced.");
            return;
        }
        try {
            Uri savedUri = moveRecordingToMovies(currentRecordingFile);
            JSObject ret = new JSObject();
            ret.put("path", "Movies/CodeDrop (" + currentRecordingFile.getName() + ")");
            ret.put("uri", savedUri != null ? savedUri.toString() : "");
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Recording finished but could not be saved to your Movies folder: " + e.getMessage());
        }
    }

    @ActivityCallback
    private void handleProjectionResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("Screen capture permission was denied.");
            teardownProjection();
            return;
        }
        mediaProjection = projectionManager.getMediaProjection(result.getResultCode(), result.getData());

        if (pendingMode == MODE_RECORDING) beginRecording(call);
        else if (pendingMode == MODE_FLOATING) beginFloatingSession(call);
        else if (pendingMode == MODE_LIVE_STREAM) beginLiveStream(call);
        else captureSingleFrame(call);
    }

    private void beginLiveStream(PluginCall call) {
        try {
            double scale = Math.min(1.0, LIVE_STREAM_MAX_DIMENSION / (double) Math.max(screenWidth, screenHeight));
            int liveWidth = Math.max(2, (int) (screenWidth * scale) & ~1);   // even dims for encoders
            int liveHeight = Math.max(2, (int) (screenHeight * scale) & ~1);

            imageReader = ImageReader.newInstance(liveWidth, liveHeight, android.graphics.PixelFormat.RGBA_8888, 2);
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "CodeDropLiveShare", liveWidth, liveHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, null);

            liveStreamActive = true;
            lastLiveFrameEmitMs = 0;
            final int fw = liveWidth, fh = liveHeight;
            imageReader.setOnImageAvailableListener(reader -> {
                if (!liveStreamActive) { 
                    Image stale = reader.acquireLatestImage();
                    if (stale != null) stale.close();
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - lastLiveFrameEmitMs < LIVE_FRAME_MIN_INTERVAL_MS) {
                    // Still have to drain the reader even when skipping a frame, or the next
                    // real frame can never arrive (ImageReader's queue is bounded).
                    Image skip = reader.acquireLatestImage();
                    if (skip != null) skip.close();
                    return;
                }
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) return;
                    lastLiveFrameEmitMs = now;
                    Bitmap bitmap = bitmapFromImage(image, fw, fh);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 55, out);
                    String b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                    JSObject frame = new JSObject();
                    frame.put("base64", b64);
                    frame.put("width", fw);
                    frame.put("height", fh);
                    notifyListeners("screenLiveFrame", frame);
                } catch (Exception ignored) {
                    // A single dropped frame isn't worth killing the whole stream over.
                } finally {
                    if (image != null) image.close();
                }
            }, null);

            call.resolve();
        } catch (Exception e) {
            call.reject("Could not start live screen sharing: " + e.getMessage());
            teardownProjection();
        }
    }

    private void captureSingleFrame(PluginCall call) {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, android.graphics.PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "CodeDropScreenshot", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null);

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;
                Bitmap cropped = bitmapFromImage(image, screenWidth, screenHeight);
                String b64 = Base64.encodeToString(bitmapToPngBytes(cropped), Base64.NO_WRAP);
                JSObject ret = new JSObject();
                ret.put("base64", b64);
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("Screenshot capture failed: " + e.getMessage());
            } finally {
                if (image != null) image.close();
                teardownProjection();
            }
        }, null);
    }

    private void beginRecording(PluginCall call) {
        try {
            currentRecordingFile = new File(getContext().getCacheDir(),
                    "codedrop-recording-" + System.currentTimeMillis() + ".mp4");

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            // H264 requires even width/height; some devices report odd real-display metrics.
            int w = screenWidth - (screenWidth % 2);
            int h = screenHeight - (screenHeight % 2);
            mediaRecorder.setVideoSize(w, h);
            mediaRecorder.setVideoFrameRate(30);
            mediaRecorder.setVideoEncodingBitRate(8_000_000);
            mediaRecorder.setOutputFile(currentRecordingFile.getAbsolutePath());
            mediaRecorder.prepare();

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "CodeDropRecording", w, h, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mediaRecorder.getSurface(), null, null);

            mediaRecorder.start();
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to start recording: " + e.getMessage());
            teardownProjection();
        }
    }

    private Uri moveRecordingToMovies(File sourceFile) throws Exception {
        String name = sourceFile.getName();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");

        Uri collectionUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CodeDrop");
            collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "CodeDrop");
            if (!dir.exists()) dir.mkdirs();
            values.put(MediaStore.MediaColumns.DATA, new File(dir, name).getAbsolutePath());
            collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        }

        Uri itemUri = getContext().getContentResolver().insert(collectionUri, values);
        if (itemUri == null) throw new Exception("Could not create a Movies entry.");
        try (InputStream in = new FileInputStream(sourceFile);
             OutputStream out = getContext().getContentResolver().openOutputStream(itemUri)) {
            if (out == null) throw new Exception("Could not open the Movies entry for writing.");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues update = new ContentValues();
            update.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContext().getContentResolver().update(itemUri, update, null, null);
        }
        //noinspection ResultOfMethodCallIgnored
        sourceFile.delete();
        return itemUri;
    }

    // ---------------------------------------------------------------- floating capture button

    @PluginMethod
    public void enableFloatingCapture(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(getContext())) {
            pendingCall = call;
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getContext().getPackageName()));
            startActivityForResult(call, intent, "handleOverlayPermissionResult");
            return;
        }
        requestFloatingProjection(call);
    }

    @ActivityCallback
    private void handleOverlayPermissionResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(getContext())) {
            call.reject("\"Display over other apps\" permission was not granted.");
            return;
        }
        requestFloatingProjection(call);
    }

    private void requestFloatingProjection(PluginCall call) {
        pendingMode = MODE_FLOATING;
        pendingCall = call;
        refreshScreenMetrics();
        getContext().startService(new Intent(getContext(), ScreenCaptureService.class));
        Intent intent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(call, intent, "handleProjectionResult");
    }

    private void beginFloatingSession(PluginCall call) {
        try {
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, android.graphics.PixelFormat.RGBA_8888, 2);
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "CodeDropFloatingCapture", screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, null);
            getContext().startService(new Intent(getContext(), FloatingButtonService.class));
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not start floating capture: " + e.getMessage());
            teardownProjection();
        }
    }

    @PluginMethod
    public void disableFloatingCapture(PluginCall call) {
        getContext().stopService(new Intent(getContext(), FloatingButtonService.class));
        teardownProjection();
        call.resolve();
    }

    /** Called by FloatingButtonService (a plain Service, not a plugin) when its button is tapped. */
    static void captureFromFloatingButton() {
        if (activeInstance != null) activeInstance.doFloatingCapture();
    }

    private void doFloatingCapture() {
        if (imageReader == null) return;
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) { showToast("Still preparing — try again in a second."); return; }
            Bitmap bitmap = bitmapFromImage(image, screenWidth, screenHeight);
            byte[] png = bitmapToPngBytes(bitmap);
            saveScreenshotBytes(png);
            showToast("Screenshot saved to Pictures/CodeDrop");
        } catch (Exception e) {
            showToast("Screenshot failed: " + (e.getMessage() != null ? e.getMessage() : "unknown error"));
        } finally {
            if (image != null) image.close();
        }
    }

    private void saveScreenshotBytes(byte[] png) throws Exception {
        String name = "codedrop-" + System.currentTimeMillis() + ".png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
        Uri collectionUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CodeDrop");
            collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "CodeDrop");
            if (!dir.exists()) dir.mkdirs();
            values.put(MediaStore.MediaColumns.DATA, new File(dir, name).getAbsolutePath());
            collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        }
        Uri itemUri = getContext().getContentResolver().insert(collectionUri, values);
        if (itemUri == null) throw new Exception("Could not create a Pictures entry.");
        try (OutputStream os = getContext().getContentResolver().openOutputStream(itemUri)) {
            if (os == null) throw new Exception("Could not open the Pictures entry for writing.");
            os.write(png);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues update = new ContentValues();
            update.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContext().getContentResolver().update(itemUri, update, null, null);
        }
    }

    private void showToast(String msg) {
        try { Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- shared helpers

    private static Bitmap bitmapFromImage(Image image, int width, int height) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;

        Bitmap bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        return Bitmap.createBitmap(bitmap, 0, 0, width, height);
    }

    private static byte[] bitmapToPngBytes(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    private void teardownProjection() {
        liveStreamActive = false;
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (mediaProjection != null) { mediaProjection.stop(); mediaProjection = null; }
        getContext().stopService(new Intent(getContext(), ScreenCaptureService.class));
    }
}
