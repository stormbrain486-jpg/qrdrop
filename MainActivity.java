package com.codedrop.app; // ⚠️ CHANGE THIS to match your applicationId in capacitor.config.json

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.BridgeActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * - Registers the ScreenCapture and FileSaver plugins (real native screenshot/recording/file
 *   saving).
 * - Bridges getUserMedia() camera/mic requests, which Android's WebView denies by default
 *   unless the host app explicitly grants them here.
 *
 * FIX vs. the original version of this file: the WebView permission bridge used to require
 * BOTH camera AND microphone to already be granted before it would grant ANY WebView camera/mic
 * request — including a camera-only request like the in-app QR scanner's, which never even asks
 * for the microphone. If the person had denied microphone access (very common for someone who
 * only intends to scan a code or take a photo), the camera silently stopped working everywhere
 * in the app, forever, because the check could never pass. It now grants exactly the resources
 * the WebView actually asked for and that are actually permitted, instead of an all-or-nothing
 * check.
 */
public class MainActivity extends BridgeActivity {

    private static final int RUNTIME_PERMISSION_REQUEST_CODE = 8422;
    private PermissionRequest pendingWebViewRequest;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ScreenCapturePlugin.class);
        registerPlugin(FileSaverPlugin.class);
        super.onCreate(savedInstanceState);

        String[] needed = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        };
        List<String> toRequest = new ArrayList<>();
        for (String p : needed) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), RUNTIME_PERMISSION_REQUEST_CODE);
        }

        this.bridge.getWebView().setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    String[] resources = request.getResources();
                    List<String> stillNeeded = new ArrayList<>();
                    for (String resource : resources) {
                        String androidPerm = androidPermissionFor(resource);
                        if (androidPerm != null
                                && ContextCompat.checkSelfPermission(MainActivity.this, androidPerm) != PackageManager.PERMISSION_GRANTED) {
                            stillNeeded.add(androidPerm);
                        }
                    }
                    if (stillNeeded.isEmpty()) {
                        // Everything this specific request needs is already granted — e.g. a
                        // camera-only request when only microphone was ever denied.
                        request.grant(resources);
                    } else {
                        pendingWebViewRequest = request;
                        ActivityCompat.requestPermissions(
                                MainActivity.this, stillNeeded.toArray(new String[0]), RUNTIME_PERMISSION_REQUEST_CODE);
                    }
                });
            }
        });
    }

    private String androidPermissionFor(String webViewResource) {
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(webViewResource)) return Manifest.permission.CAMERA;
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(webViewResource)) return Manifest.permission.RECORD_AUDIO;
        return null; // some resource types (e.g. protected media) don't map to a runtime permission
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RUNTIME_PERMISSION_REQUEST_CODE && pendingWebViewRequest != null) {
            String[] resources = pendingWebViewRequest.getResources();
            List<String> grantedResources = new ArrayList<>();
            for (String resource : resources) {
                String androidPerm = androidPermissionFor(resource);
                // Grant exactly the resources that are now actually permitted, not an
                // all-or-nothing decision for the whole request.
                if (androidPerm == null || ContextCompat.checkSelfPermission(this, androidPerm) == PackageManager.PERMISSION_GRANTED) {
                    grantedResources.add(resource);
                }
            }
            if (!grantedResources.isEmpty()) {
                pendingWebViewRequest.grant(grantedResources.toArray(new String[0]));
            } else {
                pendingWebViewRequest.deny();
            }
            pendingWebViewRequest = null;
        }
    }
}
