# PencilCam: project briefing for Claude Code

Read this first every session. It carries everything from the earlier claude.ai chats.

## What this is
PencilCam is a personal Android camera app (a successor to the old "Paper Camera" app).
It redraws the live camera feed, recorded video, or a gallery photo as a hand-drawn
pencil / ink pen / colored pencil sketch, with optional hand-drawn "wobble" (line boil).
Owner: Rodger. First Android app, so explain Android-specific steps plainly.
Test phone: Samsung Galaxy S24 Ultra.

## Current state: v0.3 (versionCode 3), installed and building clean
- v0.1: live sketch shader on camera feed.
- v0.2: glossy silver/black-glass UI, ModeDial shutter (drag ring: PHOTO / VIDEO / FILE),
  video recording with sound, File mode (gallery photo -> sketch -> save),
  side tabs (Style, Lines, Shade, Paper, Motion; Motion hidden in Photo mode),
  portrait = drawing on top 4:3 with control deck below, landscape = full-screen 16:9.
- v0.3: slide-out panels rotate to face the user in any orientation; Settings cog that
  always sits at the user's bottom-left corner (hops corners when rotated); Settings has
  menu background opacity 0-100 (default 35%), haptic clicks, keep screen awake,
  remember my look, Close, and Exit PencilCam (finishAndRemoveTask). Icons now spin on
  their own glass so the shine stays on top. Back gesture closes open menus first.

## Roadmap (agreed so far)
- File mode: apply the look to saved VIDEOS (needs a decode / re-encode pipeline).
- More styles in the Style tab: comic, halftone, watercolor.
- Before ever sharing or selling: replace the test signing key with a real secret key.

## Architecture (app/src/main/java/com/pencilcam/app/)
- MainActivity.kt: whole UI built in code (no XML layouts). Camera via CameraX.
  Activity is LOCKED to portrait in the manifest. An OrientationEventListener
  (deviceRot = 0/90/180/270, debounced 350ms) rearranges and spins things instead,
  so the camera never restarts mid-shot. Rotation changes are ignored while recording.
  deviceRot 90 = phone's left side up. Rotated UI uses rotation = -deviceRot.
  All positioning math is in the PORTRAIT pixel grid. Tabs always hug the portrait
  right edge. placePanels() sizes/rotates both menus; placeCog() parks the cog.
- SketchRenderer.kt: GLSurfaceView renderer (GLES 3). Sources: camera or bitmap.
  Outputs: screen, photo capture, video encoder surface. Slider values are in
  "720-pixel units" scaled by k so looks match across resolutions.
- Shaders.kt: GLSL for the sketch looks.
- SketchSettings.kt: every slider value, saved to SharedPreferences "pencilcam".
- ModeDial.kt: the silver dial shutter (drag ring, tap center, long-press in File mode).
- Gloss.kt: the look. Gloss colors, GlossPanelDrawable (has .opacity),
  MaxHeightScrollView, GlossTab, GlossButton (FLIP or COG icon, iconRotation).
- VideoRecorder.kt + RecordableConfigChooser.kt: MediaCodec/MediaMuxer recording.
- Saves: Pictures/PencilCam and Movies/PencilCam via MediaStore. minSdk 29, targetSdk 35.

## Design rules (Rodger's, keep them)
- ONE light source, straight above. Highlights at the TOP of every shape, shadows
  BELOW, silver gradients bright-top to dark-bottom. When something rotates, rotate
  only its icon/label, never the glass or its shine.
- Silver shine + black glass look everywhere. New controls must match Gloss.kt.
- Light menu text gets a dark shadow halo (TextView.shade()) so it reads over white paper.

## Code conventions
- VERY heavy commenting, written for a controls engineer new to Android/Kotlin.
- Copy-paste-ready, complete files. No placeholder "..." code.
- Never use em dashes in user-facing text, comments, or docs.
- Bump versionCode and versionName in app/build.gradle.kts for every release.

## Build and release
- No Gradle wrapper in the repo yet. GitHub Actions builds on every push to main
  (.github/workflows/build.yml, Java 17, Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21).
  APK lands under Actions > the run > Artifacts > PencilCam-apk.
- Local builds need Android Studio (for the SDK) and JDK 17. If those are installed,
  build locally with `gradle assembleDebug` (or add a wrapper with
  `gradle wrapper --gradle-version 8.11.1`) and fix compile errors BEFORE pushing.
- Signing: app/pencilcam-test.keystore (password "pencilcam") so builds install as
  updates. Repo must stay PRIVATE.
