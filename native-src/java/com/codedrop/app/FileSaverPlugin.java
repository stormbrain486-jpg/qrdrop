package com.codedrop.app; // ⚠️ must match your applicationId / MainActivity's package

import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Saves files that originate inside the WebView — received P2P transfers, received chat
 * images/videos, screenshots, exported files — into the device's real shared storage
 * (the Photos app for images, the Videos app for video, Downloads for everything else),
 * using MediaStore.
 *
 * WHY THIS EXISTS: a plain website can trigger a download with
 * `<a href="blob:...">` + `.click()`, and the browser hands it to the OS download manager.
 * Android's WebView does NOT do this for blob: URLs — the click does nothing, or produces
 * a file the user can never find. There is no WebView setting that fixes this; you need a
 * native bridge that actually writes the bytes to storage. This plugin is that bridge.
 *
 * USAGE FROM JS (see index.html's nativeSaveBlob() helper):
 *   const {id} = await FileSaver.beginFile({name, mimeType});
 *   await FileSaver.appendChunk({id, base64});   // call once per ~256KB chunk
 *   const {uri} = await FileSaver.finishFile({id});
 *   // on error/abort: await FileSaver.cancelFile({id});
 * For small one-shot files (e.g. a screenshot), saveBase64() skips the begin/append/finish dance.
 */
@CapacitorPlugin(name = "FileSaver")
public class FileSaverPlugin extends Plugin {

    private static class OpenFile {
        Uri uri;
        OutputStream stream;
    }

    private final Map<String, OpenFile> openFiles = new HashMap<>();

    @PluginMethod
    public void beginFile(PluginCall call) {
        String name = call.getString("name", "file-" + System.currentTimeMillis());
        String mimeType = call.getString("mimeType", "application/octet-stream");
        try {
            Uri itemUri = insertNewMediaEntry(name, mimeType);
            if (itemUri == null) { call.reject("Could not create a new file in storage."); return; }
            OutputStream os = getContext().getContentResolver().openOutputStream(itemUri);
            if (os == null) { call.reject("Could not open the new file for writing."); return; }

            OpenFile of = new OpenFile();
            of.uri = itemUri; of.stream = os;
            String id = UUID.randomUUID().toString();
            openFiles.put(id, of);

            JSObject ret = new JSObject();
            ret.put("id", id);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to start saving the file: " + e.getMessage());
        }
    }

    @PluginMethod
    public void appendChunk(PluginCall call) {
        String id = call.getString("id");
        String base64 = call.getString("base64");
        OpenFile of = id != null ? openFiles.get(id) : null;
        if (of == null) { call.reject("Unknown or already-closed file id."); return; }
        try {
            of.stream.write(Base64.decode(base64, Base64.NO_WRAP));
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to write a chunk: " + e.getMessage());
        }
    }

    @PluginMethod
    public void finishFile(PluginCall call) {
        String id = call.getString("id");
        OpenFile of = id != null ? openFiles.remove(id) : null;
        if (of == null) { call.reject("Unknown or already-closed file id."); return; }
        try {
            of.stream.flush();
            of.stream.close();
            clearPendingFlag(of.uri);
            JSObject ret = new JSObject();
            ret.put("uri", of.uri.toString());
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to finish saving the file: " + e.getMessage());
        }
    }

    @PluginMethod
    public void cancelFile(PluginCall call) {
        String id = call.getString("id");
        OpenFile of = id != null ? openFiles.remove(id) : null;
        if (of == null) { call.resolve(); return; }
        try { of.stream.close(); } catch (Exception ignored) {}
        try { getContext().getContentResolver().delete(of.uri, null, null); } catch (Exception ignored) {}
        call.resolve();
    }

    /** One-shot convenience for small files (screenshots) — no begin/append/finish needed. */
    @PluginMethod
    public void saveBase64(PluginCall call) {
        String name = call.getString("name", "file-" + System.currentTimeMillis());
        String mimeType = call.getString("mimeType", "application/octet-stream");
        String base64 = call.getString("base64");
        if (base64 == null) { call.reject("Missing base64 data."); return; }
        try {
            Uri itemUri = insertNewMediaEntry(name, mimeType);
            if (itemUri == null) { call.reject("Could not create a new file in storage."); return; }
            try (OutputStream os = getContext().getContentResolver().openOutputStream(itemUri)) {
                if (os == null) { call.reject("Could not open the new file for writing."); return; }
                os.write(Base64.decode(base64, Base64.NO_WRAP));
            }
            clearPendingFlag(itemUri);
            JSObject ret = new JSObject();
            ret.put("uri", itemUri.toString());
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to save the file: " + e.getMessage());
        }
    }

    // ---- internals ----

    private String collectionFor(String mimeType) {
        if (mimeType == null) return "downloads";
        if (mimeType.startsWith("image/")) return "images";
        if (mimeType.startsWith("video/")) return "video";
        return "downloads";
    }

    private Uri insertNewMediaEntry(String name, String mimeType) throws Exception {
        String collection = collectionFor(mimeType);
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);

        String baseDir = collection.equals("images") ? Environment.DIRECTORY_PICTURES
                : collection.equals("video") ? Environment.DIRECTORY_MOVIES
                : Environment.DIRECTORY_DOWNLOADS;

        Uri collectionUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Scoped storage (Android 10+): MediaStore handles the real path, we just say
            // where under the standard collection it should live.
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, baseDir + "/CodeDrop");
            collectionUri = collection.equals("images") ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    : collection.equals("video") ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        } else {
            // Pre-Android 10: no scoped storage, no MediaStore.Downloads collection — write a
            // real file path directly and still insert it into MediaStore so it shows up in
            // Gallery/Files immediately instead of only after a manual media scan.
            File dir = new File(Environment.getExternalStoragePublicDirectory(baseDir), "CodeDrop");
            if (!dir.exists()) dir.mkdirs();
            File outFile = new File(dir, name);
            values.put(MediaStore.MediaColumns.DATA, outFile.getAbsolutePath());
            collectionUri = collection.equals("images") ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    : collection.equals("video") ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Files.getContentUri("external");
        }
        return getContext().getContentResolver().insert(collectionUri, values);
    }

    private void clearPendingFlag(Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues update = new ContentValues();
            update.put(MediaStore.MediaColumns.IS_PENDING, 0);
            try { getContext().getContentResolver().update(uri, update, null, null); } catch (Exception ignored) {}
        }
    }
}
