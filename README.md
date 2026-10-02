<img src="design/viewsmith-icon-512.png" width="128" align="right" alt="ViewSmith icon">

# ViewSmith v0.5

ViewSmith is a camera app that redraws what it sees by hand. Point it at something and the
screen shows it as a pencil sketch, a watercolor, an oil painting, a neon sign, an old west
tintype, and more, live, while you move. The lines can wobble a little from frame to frame,
like hand-drawn animation, so even a still scene feels alive. Finishes like a vignette or a
polaroid border stack on top of whatever look you pick.

I built it because I missed the old Paper Camera app and wanted something like it that I
could keep tweaking. It runs on any Android 10 or newer phone with a camera, not just my Samsung.

## How it works

Drag the silver ring on the dial to pick a mode, then tap the black glass button in the middle.

| Mode  | What the button does |
|-------|----------------------|
| PHOTO | Saves the drawing as a picture in **Pictures/ViewSmith** |
| VIDEO | Starts and stops recording the drawing, with sound, to **Movies/ViewSmith** |
| FILE  | Pick a photo or video from your gallery. Tap to save it drawn. Press and hold to pick a different one |

Whatever you see in the viewfinder is exactly what gets saved: the look, its sliders, and
every finish, in photos, recorded videos, and File mode saves alike.

When you load a video in File mode, it loops on screen (silently) while you dial in the look.
Tap the button and ViewSmith saves a drawn copy of the whole clip, with its original sound,
to Movies/ViewSmith. The button turns red and the label counts up the percentage. Tap it again
to cancel. A long clip takes a while, since every single frame gets drawn.

Hold the phone upright and the drawing sits on top with the controls underneath. Turn it
sideways and the drawing fills the screen. The app doesn't actually rotate; the icons and
menus turn themselves to face you, so the camera never restarts in the middle of a shot.

### The side tabs

- **Style**: pick one look. Under the list are that look's own sliders, plus Mirror image,
  Rotate camera 90° (in case a camera ever shows up sideways), and Reset all sliders.
- **Finish**: vignette, matte, glossy, and polaroid. Each has an On switch and two sliders,
  and you can stack any number of them. When any are on, the Finish tab glows silver and
  shows how many.
- **Lines**: ink color, and how dark, how detailed, and how soft the outlines are
- **Shade**: how much crosshatching, and how far apart the strokes are
- **Paper**: how much paper grain shows through
- **Motion**: how much the lines wobble and how often they redraw, from 1 to 30 times a
  second. Down at 1 or 2 it turns into a slow flipbook. "Hold the video between redraws"
  freezes the picture between wobbles too. Hidden in Photo mode.

Whenever you change the look or a finish, a small label fades in over the picture for two
seconds showing what's stacked, like "Watercolor + Vignette + Polaroid".

### The looks

| Look | What it does | Its own sliders |
|------|--------------|-----------------|
| Pencil | Graphite outlines and crosshatching that gets denser in the shadows | uses Lines, Shade, Paper |
| Ink pen | The same strokes, solid and crisp | uses Lines, Shade, Paper |
| Colored pencil | Strokes take on the colors of the picture | uses Lines, Shade, Paper |
| Comic | Flat bold colors, heavy outlines, dots in the shadows | Color levels, Shadow dots, Outline weight |
| Halftone | Printed in cyan, magenta, yellow, and black dots like an old newspaper photo | Dot size, Color, Outlines |
| Watercolor | Soft washes that bleed past the edges, with paint pooling where a wash ends | Bleed, Color strength, Edge pooling |
| Chalkboard | White chalk on a green slate board | Chalk shading, Board darkness, Chalk dust |
| Oil painting | Smooth patches of paint with sharp edges, canvas weave, and brush streaks | Brush size, Color richness, Canvas texture |
| Neon edges | Every edge glows like a neon tube on a black background | Glow, Hue, Rainbow |
| Crayon edges | Thick waxy outlines and loose colored scribbles, with paper showing through the wax | Stroke thickness, Color fill, Wax texture |
| Old west | A sepia tintype with uneven plate chemistry, scratches, and dust | Sepia, Scratches and dust, Plate wear |
| Inverted | A color negative | Contrast, Color, Outlines |
| Predator vision | A fake heat camera: bright and busy spots glow hot | Edge heat, Contrast, Scan lines |

Oil painting does the most work per frame of any look. On an older phone it may not keep up
at the higher video sizes; if so, ViewSmith drops the recording size and says so.

Predator vision isn't measuring heat. It maps brightness and edges onto heat camera colors.

### The finishes

| Finish | What it does | Sliders |
|--------|--------------|---------|
| Vignette | Darkens toward the corners | Amount, Size |
| Matte | Lifted blacks and calmer color, like a matte print | Fade, Soften |
| Glossy | Deeper color plus a shine across the top, lit from above | Shine, Punch |
| Polaroid | A white card border, thick along the bottom, with faded warm tones | Frame, Faded tones |

The polaroid's thick edge and the glossy shine always go on the bottom and top as you're
holding the phone, so they come out the right way up in the saved picture.

### Settings (the cog)

The gear in the bottom-left corner (it moves to stay in *your* bottom-left as you turn the phone):

- **Menu background**: 0% makes the menus clear glass so you can watch the drawing change
  as you adjust. 100% is solid.
- **Video resolution**: 720p, 1080p (the default), 1440p, or 4K. Used for Video mode and for
  saving videos in File mode. Before recording, ViewSmith asks the phone's video encoder
  whether it can handle that size, and checks whether the current look can be drawn that big
  at a steady frame rate. If either answer is no, it drops to the next size down and shows a
  short message saying so. Bigger sizes get a higher bitrate (roughly 5 Mbps at 720p up to
  50 Mbps at 4K) so the fine strokes stay clean.
- **Haptic clicks**, **Keep screen awake**, **Remember my look between launches**
- **Exit ViewSmith**

The back gesture closes an open menu first.

## What's new in v0.5

- Six new looks: Oil painting, Neon edges, Crayon edges, Old west, Inverted, Predator vision.
  Comic, Halftone, Watercolor, and Chalkboard now have their own sliders too.
- New Finish tab: vignette, matte, glossy, and polaroid, stackable on any look
- A label over the viewfinder shows the current look and finishes whenever they change
- Videos record at 1080p by default, with 720p, 1440p, and 4K in Settings
- Works on phones other than Samsung (Pixel and others): checks what the camera and video
  encoder can actually do and falls back instead of failing
- "Redraws per second" now goes down to 1 for a slow flipbook feel

Earlier: v0.4 renamed the app ViewSmith, added videos in File mode, and four styles. v0.3
added the menus that turn with the phone and the Settings cog. v0.2 added the dial, video
recording, File mode for photos, and the side tabs.

**Coming next:** a real release key so the app can be shared.

## Getting the APK

GitHub builds the app for me every time a change lands on `main`.

1. Open the repo on GitHub and go to the **Actions** tab.
2. Click the newest **Build APK** run with a green check.
3. Scroll to **Artifacts** and download **ViewSmith-apk**. Unzip it to get `app-debug.apk`.
4. Get that file onto the phone (download it on the phone, or copy it over USB) and tap it to install.

It installs right over the top of the previous version and keeps your saved look.

### Installing on a Samsung phone

- Settings > Security and privacy > **Auto Blocker**: turn it off while testing.
- When asked, allow **Install unknown apps** for My Files or Chrome.
- If Play Protect warns about an unknown app, choose **Install anyway**.

### Installing on a Pixel (or other Android phone)

- Open the APK from Files or Chrome. When asked, allow **Install unknown apps** for that app.
- If Play Protect warns about an unknown app, choose **More details > Install anyway**.

## If something looks wrong

- **Camera picture sideways or upside down:** Style tab > **Rotate camera 90°** until it's right.
- **Selfie view backwards:** Style tab > **Mirror image**.
- **A saved video is sideways, or a video won't open:** note which clip and which phone it was
  recorded on. Different phones store video rotation differently.
- **"Recording at 1080p" message when 4K was picked:** that phone's encoder or graphics chip
  couldn't handle the bigger size with the current look. Try a lighter look (Oil painting is
  the heaviest), or pick a smaller size in Settings.
- **A build failed (red X on the Actions tab):** open the failed run and click the failed step
  to see the error.

## About the signing key

`app/pencilcam-test.keystore` is a test key. Every build is signed with it so each one installs
as an update. That's why this repo has to stay private. Before the app is ever shared or sold,
it gets a real, secret release key.

The app's internal ID is still `com.pencilcam.app`. It never shows on screen, and changing it
would make Android treat ViewSmith as a different app. It'll switch to `com.viewsmith.app`
along with the release key.

## Project layout

| File | What it does |
|------|--------------|
| `MainActivity.kt` | The screen: dial, tabs, menus, camera, saving, File mode |
| `SketchRenderer.kt` | Runs the drawing on the graphics chip, for the screen, photos, and videos |
| `Shaders.kt` | The GPU programs that draw every look and finish |
| `Looks.kt` | The list of looks and finishes, and each one's sliders |
| `SketchSettings.kt` | Every slider value, and saving them between launches |
| `VideoFile.kt` | File mode videos: playing them on screen and saving drawn copies |
| `VideoRecorder.kt` | Recording in Video mode, and checking what the phone's video encoder can do |
| `ModeDial.kt` | The silver dial shutter |
| `Gloss.kt` | The black glass and silver look for every control |
| `design/` | The icon artwork |
