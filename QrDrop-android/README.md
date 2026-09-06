# QrDrop — Android app

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

### ⚠️ Read this first — what this repo can and can't do

I built and wrote every file in here by hand, but **I could not compile or run any of it** —
the environment I built this in has no internet access and no Android SDK, so none of this has
been tested on a real build or a real device. The fixes below are based on carefully reading
the code and Android's documented behavior (in particular, two genuine, well-known Android
gotchas — see "What was actually broken" below) — but budget time for possible small build
fixes in Android Studio, and please actually test screenshots/recording/floating button/camera
flip on a real device once it builds, since I have no way to verify runtime behavior myself.

**One feature is still not included:** live "Share Screen" during a call (seeing your screen in
real time from another device) is disabled on Android with an explanation in the app. Turning a
native screen capture into a live video stream that WebRTC can send to another device requires
much deeper native/WebRTC integration than a small plugin — genuinely a separate project.
Screenshots and screen *recording* (saved as a video file you can then send as a normal file)
both work, as does the floating screenshot button.

---

## What was actually broken, and what I changed

**1. Files never arrived (P2P transfers, chat images/videos, screenshots, recordings).**
Every "download" in this app worked by creating a `blob:` URL and clicking a hidden
`<a download>` link — a real-browser trick. Android's WebView does not support this: the click
silently does nothing, or produces a file nobody can find. There is no WebView setting that
fixes this. Fix: a new **FileSaverPlugin** native plugin that writes bytes into the device's
real Photos/Movies/Downloads storage via `MediaStore`, and a shared JS helper
(`nativeSaveBlob()` in `index.html`) that every download path now routes through when running
in the native app.

**2. Screen recording silently failed.** Two separate bugs:
- *Race condition*: MediaProjection requires an active foreground service at the moment the
  virtual display is created. The old code called `startService()` and then, in the very same
  synchronous call, immediately created the virtual display — but `startService()` only *posts*
  a message; the service doesn't actually start running until the current code finishes and
  control returns to the main event loop. So the virtual display was being created before the
  foreground service had actually started, which Android 10+ (and especially 14+) rejects. Fix:
  the foreground service is now started **before** requesting the screen-capture permission
  dialog, not after — granting that system permission requires a real human tap, which takes far
  longer than one event-loop cycle, so by the time it comes back the service is definitely
  running.
- *Recordings were saved to `getCacheDir()`*, which is private to the app — invisible to any
  file manager or gallery. Fix: `stopRecording()` now copies the finished video into the device's
  real Movies folder (`Movies/CodeDrop`) and deletes the private temp copy.

**3. Camera sometimes stopped working entirely.** `MainActivity.java`'s WebView permission
bridge required **both** camera AND microphone to already be granted before it would grant
*any* camera or mic request — including camera-only requests like the built-in QR scanner's,
which never even asks for the microphone. If you'd ever denied microphone access, the camera
would silently stop working everywhere in the app, permanently, since that check could never
pass. Fixed to grant exactly the resources a given request actually needs and actually has.

**4. No way to switch front/rear camera.** Every camera feature was hardcoded to the front
(selfie) camera with no way to change it. Added a shared `cameraFacingMode` state and a
"🔄 Flip Camera" button in the Camera modal, the device-chat call screen, and the meeting call
screen — tapping it re-acquires the camera on the other side and swaps it into any active call
without dropping the connection.

**5. The old "Floating capture" button could never have worked on Android.** It used the
Document Picture-in-Picture API, which is desktop Chrome/Edge only — no mobile browser or
WebView has ever implemented it, so it was correctly hidden on Android, but that also meant
mobile had no floating button at all. Added a real one: **FloatingButtonService**, a foreground
service that draws a draggable "📸" button on top of every app using Android's "display over
other apps" permission. The first tap after enabling it asks for that permission plus one
screen-capture consent; every tap after that captures instantly with no further prompts, and
auto-saves straight to `Pictures/CodeDrop`.

---

## What's in this repo

```
├── www/index.html              ← the actual app (all fixes + Android bridging already applied)
├── native-src/                 ← custom native Android files — you copy these INTO the
│   │                              generated android/ folder (step 4 below creates it)
│   ├── AndroidManifest-additions.xml
│   └── java/com/codedrop/app/
│       ├── MainActivity.java
│       ├── ScreenCapturePlugin.java
│       ├── ScreenCaptureService.java
│       ├── FloatingButtonService.java
│       └── FileSaverPlugin.java
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
# 1. Install the Capacitor packages (needs internet — this downloads the real,
#    correct Android project template, which is why I can't hand-write it myself)
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

The included `.github/workflows/main.yml` does all of steps 1–5 automatically on every push to
`main` and uploads a debug APK as a workflow artifact, if you'd rather grab a build from GitHub
Actions than build locally.

## If your package name isn't `com.codedrop.app`

`capacitor.config.json`'s `appId` controls this. If you change it, also change the `package`
line at the top of all five `.java` files in `native-src/` to match, and the folder path in
step 3 above (`android/app/src/main/java/<your/package/path>/`).

## Rebuilding after editing www/index.html

Any time you change `www/index.html`, run:
```bash
npx cap sync android
```
then re-run from Android Studio.

## Permissions your phone will ask for

- **Camera & microphone** — asked once on first launch (for calls, QR scanning, camera capture)
- **Screen recording** — asked each time you start Snipping Tool / Full Screenshot / Screen
  Recording, and once when you first enable the floating button (this is how Android's
  system-level screen capture consent always works for a fresh session — it can't be granted
  permanently, by design, for privacy reasons)
- **Display over other apps** — asked once, the first time you enable the floating screenshot
  button; you'll be sent to a Settings screen to grant it, then returned to the app
- **Notifications** (Android 13+) — needed for the small persistent notification Android
  requires while a screen recording or the floating button is active

## Known limitations, being upfront

- Live screen-sharing during calls: see the warning at the top of this file.
- The in-browser "Floating Capture" button (Document Picture-in-Picture) still doesn't exist on
  Android because no mobile browser has ever implemented that API — but the native floating
  button described above replaces it with something that actually works.
- This app relies on the public PeerJS broker and a free OpenRelay TURN server for connecting
  devices across different networks. Fine for personal use; if you ever need guaranteed
  uptime, you'd want your own PeerServer + TURN credentials.
- Files larger than a couple MB are streamed to native storage in ~256KB chunks rather than one
  big transfer, to avoid holding an entire video in memory as text — this hasn't been measured
  for speed on a real device; if very large video transfers feel slow, that chunk size
  (`CHUNK_SIZE`/`chunkSize` in `index.html`'s native-save helper) is the first thing to tune.
