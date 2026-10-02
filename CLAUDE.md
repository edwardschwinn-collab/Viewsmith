# ViewSmith (formerly PencilCam): project briefing for Claude Code

Read this first every session. It carries everything from the earlier chats.

## What this is
ViewSmith is a personal Android camera app (a successor to the old "Paper Camera" app).
It redraws the live camera feed, a gallery video, or a gallery photo as a hand-drawn
sketch in one of 7 styles, with optional hand-drawn "wobble" (line boil).
Renamed from PencilCam in v0.4. The GitHub repo and local folder are still named PencilCam.
Owner: Rodger. First Android app, so explain Android-specific steps plainly.
Test phone: Samsung Galaxy S24 Ultra.

## Current state: v0.4 (versionCode 4)
- v0.1: live sketch shader on camera feed.
- v0.2: glossy silver/black-glass UI, ModeDial shutter (drag ring: PHOTO / VIDEO / FILE),
  video recording with sound, File mode for photos, side tabs (Style, Lines, Shade, Paper,
  Motion; Motion hidden in Photo mode), portrait = drawing on top 4:3 with deck below,
  landscape = full-screen 16:9.
- v0.3: panels rotate to face the user; Settings cog at the user's bottom-left; menu
  opacity, haptics, keep awake, remember look, Exit. Back gesture closes menus first.
- v0.4: renamed ViewSmith with new adaptive icon (design/). File mode takes videos: loops
  silently on screen, tap saves a sketched copy with original audio (tap again cancels).
  New styles: Comic (3), Halftone (4), Watercolor (5), Chalkboard (6).
  Saves go to Pictures/ViewSmith and Movies/ViewSmith.
- v0.4 has NOT been tested on the phone yet. Most likely trouble spots: video rotation
  sign in File mode (renderer applies the clip's rotation itself in Pass 0, decoder
  KEY_ROTATION forced to 0), and the look of the four new styles.

## Roadmap (agreed so far)
- More styles.
- Before ever sharing or selling: replace the test signing key with a real secret key,
  and at the same time change applicationId/namespace to com.viewsmith.app (forces a
  fresh install, so do both together).

## Architecture (app/src/main/java/com/pencilcam/app/)
- MainActivity.kt: whole UI built in code (no XML layouts). Camera via CameraX.
  Activity is LOCKED to portrait in the manifest. An OrientationEventListener
  (deviceRot = 0/90/180/270, debounced 350ms) rearranges and spins things instead,
  so the camera never restarts mid-shot. Rotation changes are ignored while recording
  or exporting. deviceRot 90 = phone's left side up. Rotated UI uses rotation = -deviceRot.
  All positioning math is in the PORTRAIT pixel grid. Tabs always hug the portrait
  right edge. placePanels() sizes/rotates both menus; placeCog() parks the cog.
  File mode holds either loadedBitmap or videoUri (never both).
- SketchRenderer.kt: GLSurfaceView renderer (GLES 3). Sources: camera, bitmap, or video
  file (priority bitmap > video > camera). Outputs: screen, photo capture, camera
  recorder surface, video export surface. Other threads only set @Volatile requests;
  onDrawFrame handles them on the GL thread. Slider values are in "720-pixel units"
  scaled by k. During export the wobble clock is the video's own timestamps.
- VideoFile.kt: VideoInfo (size/rotation/duration), VideoPlayer (real-time looping
  decode into the renderer's Surface), VideoExporter (lock-step decode -> renderer ->
  H.264 encoder -> MediaMuxer, audio copied as compressed samples).
- Shaders.kt: GLSL. DRAW_FS branches on u_style (0..6).
- SketchSettings.kt: every slider value, saved to SharedPreferences "pencilcam"
  (old name kept on purpose so saved looks survive the rename). STYLE_COUNT = 7.
- ModeDial.kt: the silver dial shutter (drag ring, tap center, long-press in File mode).
- Gloss.kt: the look. Gloss colors, GlossPanelDrawable (has .opacity),
  MaxHeightScrollView, GlossTab, GlossButton (FLIP or COG icon, iconRotation).
- VideoRecorder.kt + RecordableConfigChooser.kt: MediaRecorder recording from a Surface.
- Icon: res/mipmap-anydpi-v26 adaptive icon. Foreground PNGs are rendered from
  design/viewsmith-icon-foreground.svg (no Inkscape/ImageMagick here; headless Edge
  screenshots with --default-background-color=00000000 work). Background is a gradient
  shape drawable.
- minSdk 29, targetSdk 35.

## Design rules (Rodger's, keep them)
- ONE light source, straight above. Highlights at the TOP of every shape, shadows
  BELOW, silver gradients bright-top to dark-bottom. When something rotates, rotate
  only its icon/label, never the glass or its shine.
- Silver shine + black glass look everywhere. New controls must match Gloss.kt.
- Light menu text gets a dark shadow halo (TextView.shade()) so it reads over white paper.

## Code and writing conventions
- VERY heavy commenting, written for a controls engineer new to Android/Kotlin.
- Comments and the README should read like Rodger explaining his own app: plain,
  first person where natural, describing what the app does and why. No AI-sounding
  phrasing, no references to Claude or "the chat", no version-history chatter in code.
- Copy-paste-ready, complete files. No placeholder "..." code.
- Never use em dashes in user-facing text, comments, or docs.
- Bump versionCode and versionName in app/build.gradle.kts for every release.

## Build and release
- No Java, Android SDK, or Gradle on this PC, so builds happen only on GitHub Actions
  (.github/workflows/build.yml, Java 17, Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21).
- gh CLI is installed and logged in (account edwardschwinn-collab). To test a change
  without touching main: push a branch, then `gh workflow run build.yml --ref <branch>`
  and `gh run watch <id>`. Merge to main only when green. Every push to main builds.
- APK lands under Actions > the run > Artifacts > ViewSmith-apk.
- Signing: app/pencilcam-test.keystore (password "pencilcam") so builds install as
  updates. Repo must stay PRIVATE.
