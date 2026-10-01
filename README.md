# PencilCam (working title) v0.1

Live camera, redrawn as pencil, ink pen, or colored pencil, with a hand-drawn wobble.
Same look as the browser lab, now as a real Android app.

## What's in v0.1
- Live preview with Pencil, Ink pen, and Colored pencil styles
- "Adjust" panel: every slider from the lab, plus ink colors
- Flip between front and back cameras
- Take photo: saves full size to Pictures/PencilCam
- Settings are remembered between launches

Coming next: video recording (v0.2), converting existing videos, more styles.

## Getting the APK (GitHub does the building)

1. Make a **private** repo on github.com (New repository, name it `PencilCam`, choose Private, tick "Add a README").
2. Click **Add file > Upload files** and drag in everything from this folder, *including* the `.github` folder. Commit.
3. Open the **Actions** tab. A run called "Build APK" starts on its own. Wait for the green check (about 3 to 6 minutes).
4. Click the run, scroll to **Artifacts**, download **PencilCam-apk**, and unzip it. Inside is `app-debug.apk`.
5. Get that APK onto your phone (download it on the phone directly, or copy it over USB) and tap it to install.

If the Actions tab shows nothing after uploading, the `.github` folder didn't make it. Fix it with
**Add file > Create new file**, type `.github/workflows/build.yml` as the name, paste the file's contents, and commit.

## Installing on a Samsung phone
- Settings > Security and privacy > **Auto Blocker**: turn off while testing.
- When asked, allow **Install unknown apps** for My Files or Chrome.
- If Play Protect warns about an unknown app, choose **Install anyway**.

## If something looks wrong
- **Picture sideways or upside down:** Adjust > Testing > **Rotate preview 90°** until it's right. Tell Claude which setting fixed it so it can be made the default.
- **Selfie view backwards:** toggle **Mirror image**.
- **Build failed (red X):** open the failed run, click the failed step, copy the red error text, and paste it to Claude.

## About the signing key
`app/pencilcam-test.keystore` is a TEST key so every build installs as an update.
Keep this repo private. Before sharing or selling the app, make a real secret release key.
