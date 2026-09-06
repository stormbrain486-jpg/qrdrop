# QrDrop — Android app

This repo wraps the QrDrop web app in a real Android app using [Capacitor](https://capacitorjs.com).

> **This is a second round of fixes on top of the previous build.** The three issues below were
> reported after the first version was built and pushed through GitHub Actions, and are now
> fixed in this version. As before, I could not compile or run this myself (no internet/Android
> SDK in the environment I work in) — please test on a real device once it builds, especially the
> live screen-share, since frame throttling/quality was tuned by reasoning about the code, not by
> watching it run.

---

## What was broken this round, and what changed

**1. Couldn't add/attach files anywhere in the app (Compress, Convert, Zip, Enhance, chat
attachments) on the native Android build.**
`MainActivity.java` replaced the WebView's chrome client with a bare `new WebChromeClient() {...}`
— a plain `android.webkit.WebChromeClient`. That silently wiped out Capacitor's own
`BridgeWebChromeClient`, which is what implements `onShowFileChooser()` — the callback Android's
WebView needs to actually open a file picker when JS taps an `<input type="file">`. A plain
`WebChromeClient` doesn't implement that method (the platform default just returns `false`), so
every "Tap to choose files" drop zone in the app — Compress, Convert, Zip, Enhance, chat
attachments, all of it — silently did nothing on the native build, even though the exact same
code worked fine on desktop browsers (which never go through this native bridge at all).
**Fix:** `MainActivity`'s inner class now extends `BridgeWebChromeClient` instead of the plain
`WebChromeClient`, and only overrides `onPermissionRequest()` — file choosers now work exactly as
Capacitor intends.

**2. Camera flip failing, especially in the meeting screen.**
Most Android camera HALs can only have **one** camera session open at a time — front *or* back,
never both simultaneously. The old flip code requested the new-facing camera stream *before*
releasing the currently-active one, which real Android hardware often rejects (or silently hands
back the same camera it was already using, ignoring the requested facing mode). **Fix:**
`flipCallCamera()` now stops and releases the current camera track *first*, then requests the
opposite-facing camera. It also preserves mic/camera mute state across the flip, and if the new
camera genuinely fails to open (single-camera device), it tries to recover the original camera
so you're never left with a dead video feed instead of just a failed flip.

**3. Live "Share Screen" during a call didn't work on Android at all.**
This was intentionally disabled in the previous version — Android's WebView has no
`getDisplayMedia()`, and building a full native WebRTC video-capturer is a much bigger project
than a small plugin. This version adds a lighter-weight but genuinely working path instead:
- `ScreenCapturePlugin.java` gained `startLiveStream()` / `stopLiveStream()`, which reuse the
  same MediaProjection pipeline already used for screenshots, but keep the capture session open
  and continuously emit downscaled JPEG frames (throttled to ~8fps, capped at 960px on the long
  edge) as a `"screenLiveFrame"` plugin event.
- On the JS side, `nativeLiveScreenStream()` (in `index.html`) listens for those frames, draws
  each one onto an offscreen `<canvas>`, and calls the canvas's own `captureStream()` — a
  standard Web API the WebView already supports — which produces a real `MediaStream` video
  track.
- That track is handed to the **exact same** PeerJS/WebRTC call code the rest of the app already
  uses for device-to-device calls and meetings, so no native WebRTC library needed to be bundled
  at all — the WebView's built-in `RTCPeerConnection` does the actual encoding/sending, same as
  every other call in the app.
- **Known limitation of this approach:** no audio track is captured yet (Android's
  system-audio-playback capture is a separate, newer API not wired up here), and the frame rate
  is capped around 8fps — fine for reading a shared screen, not video-smooth like a desktop
  share. The "Share Screen" buttons are no longer disabled on native Android; there's a tooltip
  noting the lower quality instead.

---

## Everything below is unchanged from the previous README

This repo wraps the QrDrop web app in a real Android app using [Capacitor](https://capacitorjs.com),
with custom native code added for:
- Camera & microphone access (calls, camera capture, QR scanning), including a front/rear
  camera **flip button** in the camera modal and in both call screens
- Real screen capture (Snipping Tool, Full Screenshot, Screen Recording) via Android's
  MediaProjection API, saved to your real Movies/Pictures folders (not a hidden app-private one)
- A real, native **floating screenshot button** ("draw over other apps") that works from any
  app, not just while CodeDrop is open
- Saving received files (P2P transfers, chat images/videos, screenshots, recordings) to your
  actual Photos/Movies/Downloads via a native file-saving bridge, since Android's WebView can't
  do a normal browser-style download
- Proper Android back-button handling
- Live screen sharing during device-to-device calls and meetings (new — see above)

## What's in this repo

```
├── index.html / www/index.html  ← the actual app (all fixes + Android bridging already applied)
├── native-src/                  ← custom native Android files — you copy these INTO the
│   │                               generated android/ folder (step 4 below creates it)
│   ├── AndroidManifest-additions.xml
│   └── java/com/codedrop/app/
│       ├── MainActivity.java
│       ├── ScreenCapturePlugin.java
│       ├── ScreenCaptureService.java
│       ├── FloatingButtonService.java
│       └── FileSaverPlugin.java
├── .github/workflows/main.yml   ← builds a debug APK automatically on every push to main
├── package.json
├── capacitor.config.json
└── README.md
```

## Requirements

- [Node.js](https://nodejs.org) 18+
- [Android Studio](https://developer.android.com/studio) (includes the Android SDK)
- A phone or emulator running Android 8.0+

## Setup — run these once

```bash
# 1. Install the Capacitor packages
npm install

# 2. Generate the native Android project
npx cap add android

# 3. Copy the custom native files into the generated project (overwrite when asked)
cp native-src/java/com/codedrop/app/*.java android/app/src/main/java/com/codedrop/app/

# 4. Open android/app/src/main/AndroidManifest.xml and add the blocks from
#    native-src/AndroidManifest-additions.xml at the locations described in its comments
#    (permissions near the top, the two <service> tags inside <application>)

# 5. Sync everything
npx cap sync android

# 6. Open in Android Studio
npx cap open android
```

Then in Android Studio: let Gradle sync finish, plug in your phone (with USB debugging
enabled) or start an emulator, and press **Run ▶**.

`.github/workflows/main.yml` does all of steps 1–5 automatically on every push to `main` and
uploads a debug APK as a workflow artifact, if you'd rather grab a build from GitHub Actions
than build locally.

## If your package name isn't `com.codedrop.app`

`capacitor.config.json`'s `appId` controls this. If you change it, also change the `package`
line at the top of all five `.java` files in `native-src/`, and the folder path in step 3 above.

## Rebuilding after editing index.html

Any time you change `index.html`, copy it into `www/index.html` too (or just let the GitHub
Actions workflow do it), then run:
```bash
npx cap sync android
```
then re-run from Android Studio.

## Please test these specifically before relying on this build

- **File uploads**: Compress / Convert / Zip / Enhance drop zones, and chat file attachments —
  confirm tapping actually opens Android's file picker now.
- **Camera flip**: in the 1:1 device call screen, in a meeting, and in the plain camera modal —
  confirm it switches and that mute state survives the flip.
- **Live screen share**: start a call/meeting, tap "Share Screen", confirm the other side
  actually sees a (throttled, ~8fps) live view of your screen rather than a frozen frame or
  nothing. There's currently no audio with it.
