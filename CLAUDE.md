# ViewSmith (formerly PencilCam): project briefing for Claude Code

Read this first every session. It carries everything from the earlier chats.

## What this is
ViewSmith is a personal Android camera app (a successor to the old "Paper Camera" app).
It redraws the live camera feed, a gallery video, or a gallery photo as a hand-drawn
sketch in one of 13 looks, with stackable finishes and optional hand-drawn "wobble" (line boil).
Renamed from PencilCam in v0.4. The GitHub repo and local folder are still named PencilCam.
Owner: Rodger. First Android app, so explain Android-specific steps plainly.
Test phone: Samsung Galaxy S24 Ultra. Must ALSO work on Google Pixel and other phones: no Samsung-only
APIs, check camera/encoder capabilities and fall back, shaders stay GLSL ES 1.00 (runs on any ES 3.0 GPU).

## Current state: v0.7 (versionCode 7)
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
- v0.5: looks 7-12 (Oil painting = Kuwahara, Neon edges, Crayon edges, Old west tintype,
  Inverted, Predator vision = fake thermal palette). Every look past the first three has 3
  own sliders (Looks.kt -> SketchSettings.lookParams -> u_p). Finish tab: vignette, matte,
  glossy, polaroid, stackable (finishOn/finishParams -> u_finOn/u_finA/u_finB); tab badge
  + 2 s look label. Video resolution setting 720/1080/1440/4K (default 1080), encoder
  check + GPU keep-up check step down with a toast. Redraws per second min is 1.
- v0.6: Looks.PARAMS = 4 (u_p is vec4; saved as look4_N, old look_N migrated on load) and
  pick-one settings (Param.choices -> radio rows). Neon: neonColor swatches on Lines tab
  (passed as u_ink), modes Solid/Rainbow/Two-tone/Scene, u_time for cycling. Oil: anisotropic
  Kuwahara in OIL_GRAD_FS + OIL_PAINT_FS at half size (paint rgb + stroke angle in alpha),
  impasto lit via upVec() in DRAW_FS. Old west: printScratches(), creases, Wear stages,
  Print/Film, u_dmg = settings.damageSeed (New damage button, locked while recording/saving).
  Thermal palettes (u_p.w). Watercolor pooling at the bled wash edge. Polaroid caption:
  MainActivity.updateCaption() draws res/font/caveat.ttf text into a 1024x192 bitmap ->
  renderer.setCaption() -> u_caption on the bottom border.
- v0.7: viewfinder gestures in MainActivity.onViewfinderTouch (glView touch listener):
  swipe = next/prev look in USER coords (handleSwipe maps by deviceRot), hold 350 ms =
  renderer.compare (PLAIN_FS, screen only), tap = focusAt() via DisplayOrientedMeteringPoint
  Factory on the fitted picture rect + FocusRing, pinch = ScaleGestureDetector -> camera zoom.
  Menu open = tap closes it, nothing else. Mix per look (settings.mix[] -> u_mix, applied
  before finishes). Faves tab (Favorites.kt, prefs "viewsmith_favorites", SketchSettings
  toJson/fromJson, keeps mirror + rotationFix). Self-timer button (GlossButton.TIMER, prefs
  "timerSec") with countdownText. Seven tabs at 54dp.
- Polaroid "squeeze in landscape" (reported v0.6): shader math verified round in WebGL
  (circle stays round, just cropped). Waiting on a screenshot from Rodger to see what he saw.
- Looks can be previewed without a phone: extract the shaders into a WebGL page and
  screenshot it with headless Edge (--use-angle=swiftshader --enable-unsafe-swiftshader).
  Also compiles every shader under strict GLSL ES 1.00 first.
- v0.4 to v0.7 have NOT been tested on a phone yet. Most likely trouble spots: video rotation
  sign in File mode (renderer applies the clip's rotation itself in Pass 0, decoder
  KEY_ROTATION forced to 0), the look of styles 3-12 (tuned by reasoning, never seen),
  shader compile errors (only show at runtime in logcat), polaroid/glossy orientation
  for mirrored selfies held sideways, and whether CameraX gives the Preview a 4K stream.

## Roadmap (agreed so far)
- More looks (append to the END of Looks.names so saved style numbers stay valid).
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
  Encoder outputs (recording, export) draw at the encoder's own size, not the proc size.
- VideoFile.kt: VideoInfo (size/rotation/duration), VideoPlayer (real-time looping
  decode into the renderer's Surface), VideoExporter (lock-step decode -> renderer ->
  H.264 encoder -> MediaMuxer, audio copied as compressed samples).
- Looks.kt: catalog of looks (names, per-look slider specs, hints) and finishes. Order =
  style number = shader u_style; only ever append.
- Shaders.kt: GLSL ES 1.00. DRAW_VS has u_turn (quarter turn for wide-only encoders).
  Texture units in DRAW: 0 blur, 1 src, 2 paint (oil), 3 caption.
  DRAW_FS branches on u_style (0..12), then applies finishes in a fixed order. u_frameRot
  (viewer's bottom edge = outRotation(), 0 in File mode) orients the polaroid border
  and glossy shine; polaroid crops the picture into the inner rect.
- SketchSettings.kt: every slider value, saved to SharedPreferences "pencilcam"
  (old name kept on purpose so saved looks survive the rename). Arrays are saved as
  look_N, finOn_N, finVal_N. Always hand the renderer settings.snapshot() (deep copy).
- ModeDial.kt: the silver dial shutter (drag ring, tap center, long-press in File mode).
- Gloss.kt: the look. Gloss colors, GlossPanelDrawable (has .opacity),
  MaxHeightScrollView, GlossTab, GlossButton (FLIP or COG icon, iconRotation).
- VideoRecorder.kt + RecordableConfigChooser.kt: MediaRecorder recording from a Surface.
  EncoderCheck (in VideoRecorder.kt): MediaCodecList AVC caps, fit() tries upright then
  sideways (turned), bitrate() = 0.2 bits/pixel/frame capped by the encoder.
- Video mode draws at renderer.cameraShort (= Settings size) so the preview shows real
  performance; renderer.perf compares camera frames arrived vs drawn over 1.5 s.
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
- APK lands under Actions > the run > Artifacts > ViewSmith-apk. After a green build, download it for Rodger with
  `gh run download <id> -n ViewSmith-apk -D apk` and rename to apk/ViewSmith-v<version>.apk (apk/ is gitignored).
- Signing: app/pencilcam-test.keystore (password "pencilcam") so builds install as
  updates. Repo must stay PRIVATE.
