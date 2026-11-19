# Glasses Live

An iPhone app that livestreams the camera of Ray-Ban Meta (Gen 2) glasses to
any RTMP site: Twitch, YouTube, Facebook, Kick, or a custom server.

**How it works:** Meta's Wearables Device Access Toolkit (v1.0.1) brings the
glasses' camera to the phone over Bluetooth. The app encodes it to H.264 with
[HaishinKit](https://github.com/HaishinKit/HaishinKit.swift) (v2.3.0) and
publishes it over RTMP. Audio comes from the glasses' mic (via Bluetooth
headset mode) or the phone's mic.

You build it with **no Mac**: GitHub's cloud Macs compile it and upload it to
TestFlight, and you install it on your iPhone from there.

There is also an **Android version** in [`android/`](android/). It's free to
build and needs no developer account, as described in the next section.

## Android (free, no developer account)

**Get the app:**
- **Direct download** (anyone can use it, no GitHub account needed). Open this on the phone:
  https://github.com/miguel99x/glasses-live/releases/download/android-latest/GlassesLive.apk
  Every Android build replaces it with the newest version.
- **Or build it on a PC** with Android Studio installed: open the `android/`
  folder in Android Studio and click Run with the phone plugged in over USB
  (turn on USB debugging in the phone's Developer options first).

**On the Android phone (Android 10 or newer, e.g. Galaxy S9 and up):**
1. Install the **Meta AI** app and pair your glasses with *this* phone.
   Glasses connect to one phone at a time.
2. Turn on **Developer Mode** in Meta AI (Settings > App Info, then tap the version 5 times).
3. Open the `.apk` and allow "install unknown apps" for your file manager when Android asks.
4. In Glasses Live, allow **Nearby devices** if asked (Android 12+), then **Connect to Meta AI**, **Camera on**, and **Go live**.

How it works on Android: the glasses send compressed HEVC video. The phone's
hardware decoder renders it into a hardware H.264 encoder, and RootEncoder
(v2.8.1) publishes that over RTMP. The Android version streams vertically (9:16) for now.

## iPhone setup (one time)

### 1. Apple ($99/year)
1. Join the **Apple Developer Program** at developer.apple.com/programs.
2. Note your **Team ID**: developer.apple.com > Account > Membership details.
3. **Register the app ID**: Certificates, IDs & Profiles > Identifiers > **+** >
   App IDs > App.
   - Bundle ID (explicit): something like `com.yourname.glasseslive`
   - Capabilities: tick **Access Wi-Fi Information** and **Hotspot**
4. **Create the app** in App Store Connect (appstoreconnect.apple.com) > Apps >
   **+** > New App. Pick that bundle ID; the name can be anything not already taken.
5. **Create an API key**: App Store Connect > Users and Access > Integrations >
   App Store Connect API > **+**. Give it the **Admin** role, which cloud signing
   needs. Download the `.p8` file (you can only download it once) and note the
   **Key ID** and **Issuer ID**.

### 2. GitHub (free)
1. Create a **private** repository and push this folder to it.
2. Settings > Secrets and variables > Actions > **New repository secret**. Add:

   | Secret | Value |
   |---|---|
   | `APPLE_TEAM_ID` | your 10-character Team ID |
   | `BUNDLE_ID` | the bundle ID from step 1.3 |
   | `ASC_KEY_ID` | API Key ID |
   | `ASC_ISSUER_ID` | API Issuer ID |
   | `ASC_KEY_P8` | open the `.p8` in Notepad and paste the whole text |

### 3. Build and install
1. Every push runs **Build check**, a compile-only job that needs no secrets.
   Wait for it to go green.
2. Actions tab > **Ship to TestFlight** > **Run workflow**. It takes about 15–25 minutes.
3. In App Store Connect > your app > TestFlight, add yourself as an
   **internal tester**. Apple processes the build (about 5–15 more minutes).
4. On the iPhone, install **TestFlight** from the App Store and install Glasses Live from it.

### 4. Glasses and Meta AI
1. Update the glasses and the Meta AI app.
2. Turn on **Developer Mode** in Meta AI: Settings > App Info, then tap the version number 5 times.
   (Meta's toolkit docs also mention Settings > your glasses > Developer Mode.)
   Developer Mode lets the app run without a Meta App ID.

## Using the app
1. **Connect to Meta AI**: Meta AI opens; approve Glasses Live.
2. **Settings** (gear icon): pick your site and paste your **stream key** from
   its creator dashboard. For Kick, choose *Custom* and paste both Kick's server URL and key.
3. **Camera on**: the first time, Meta AI asks for camera permission. Choose *Allow always*.
4. **Go live**. Keep the app open on screen while streaming.

Tips: 504p at 24 fps is the default, and lower settings often look *sharper*
because Bluetooth compresses less. Use *Horizontal 16:9* for landscape YouTube
or Twitch viewers (it adds side bars). Streaming drains the glasses' battery fast.

## Rules to respect
Meta's Acceptable Use Policy forbids covering or bypassing the glasses' capture
LED, recording people secretly, and recording in sensitive places. Livestream
only where people know they're on camera.

## Troubleshooting
- **Build check fails**: download the `build-log` artifact from the run and
  share the `error:` lines.
- **"No signing certificate" / certificate limit** on TestFlight runs: the API
  key must have the Admin role. If Apple says you have too many certificates,
  revoke old "Apple Development" certificates created by CI in the developer portal.
- **"Glasses didn't respond"**: open the hinges, check Developer Mode is on, and
  check the glasses are connected in Meta AI.
- **Server rejected the stream**: re-copy the stream key, which some sites reset
  after you view them.

## Project layout
- `project.yml`: XcodeGen spec (the cloud build turns it into an Xcode project)
- `Sources/LiveController.swift`: glasses session, camera, going live
- `Sources/Broadcaster.swift`: RTMP publishing with in-order frame delivery
- `Sources/FrameConverter.swift`: glasses frames converted to encoder buffers
- `Sources/MicCapture.swift`: glasses or phone mic audio
- `Sources/ContentView.swift`: UI and settings
- `.github/workflows/`: the compile check and TestFlight upload jobs
