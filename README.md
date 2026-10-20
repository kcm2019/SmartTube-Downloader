# SmartTube Downloader

An app that downloads the latest [SmartTube](https://github.com/yuliskov/SmartTube)
release from GitHub and installs it on your Fire TV Stick.

## What the app does

1. On launch it queries `https://api.github.com/repos/yuliskov/SmartTube/releases?per_page=10`
   and picks the newest release — stable **or** pre-release/beta — that has an asset
   whose name ends with `_universal.apk` (the build that runs on any Fire TV).
   Nothing is hardcoded, so it always finds the current version.
2. It shows the newest build (name, tag, stable or pre-release, APK filename, size) and
   whether SmartTube Stable/Beta is already installed and which version you have.
3. Press the big **Download & Install** button: the APK downloads with a progress bar
   (percent and MB), then the system installer opens so you can approve the install.
4. After SmartTube is installed, the downloaded APK file is deleted automatically
   (a package-installed broadcast receiver deletes it, the app re-checks on resume,
   and any leftover APK is cleaned up on the next launch if SmartTube is present).

Everything is plain Android framework code — no AndroidX/third-party libraries —
and all network/disk work runs off the UI thread.

## Target device

- **Fire TV Stick running Fire OS 6.7.1.1 = Android 7.1.1, API level 25**
- `minSdkVersion 25`, `targetSdkVersion 25`, compiled against android-34.
- Appears on the Fire TV home screen (LAUNCHER + LEANBACK_LAUNCHER categories,
  320×180 TV banner included).
- Icon + banner design: a red TV (antennae, play-button screen) with the words
  "SmartTube Downloader" — launcher icon in 5 mipmap densities, same motif on
  the banner since the Fire TV home tile renders `android:banner`.

## Project details

| | |
|---|---|
| App label | SmartTube Downloader |
| Application ID | `com.kurt.smarttubedownloader` |
| Version | 1.2 (versionCode 3) |
| SmartTube packages watched | `org.smarttube.stable` and `org.smarttube.beta` (both verified from the real release APKs: `SmartTube_stable_32.56_universal.apk` → stable package; `SmartTube_beta_32.68_universal.apk` → beta package) |
| Permissions | `INTERNET`, `REQUEST_INSTALL_PACKAGES` |

## Build instructions

Build route used: **manual SDK-tools build** (in `build.sh`). Gradle was tested
first, but in this sandbox the Gradle 8.7 daemon cannot even start (`gradle help`
fails with "Could not receive a message from the daemon"), so the manual route
below produces the identical, verified APK. The app deliberately uses only
framework APIs, so no AndroidX/Gradle dependency resolution is needed.

### Manual build (used)

Requires JDK 17, Android build-tools 34.0.0 and platform android-34.
Toolchain paths on the build VM:

- `JAVA_HOME=/home/hatch/toolchains/jdk-17.0.20.1+1`
- Android SDK: `/home/hatch/android-sdk`

Run:

```bash
export JAVA_HOME=/home/hatch/toolchains/jdk-17.0.20.1+1
export PATH=$JAVA_HOME/bin:$PATH
cd smarttube-downloader
./build.sh
```

The script runs: `aapt2 compile` → `aapt2 link` (min/target SDK 25, version
1.2/3) → `javac` (source level 8 against `android.jar`) → `d8 --min-api 25` →
adds `classes.dex` → `zipalign -p 4` → `apksigner sign` (v1 + v2 signatures).
Output: `build/manual/apk/SmartTubeDownloader.apk`.

### Gradle route (alternative, for a machine where Gradle works)

Create a standard Android project with the same `src/main/` layout, AGP 8.5+
on JDK 17, Gradle 8.7:

```gradle
android {
    namespace 'com.kurt.smarttubedownloader'
    compileSdk 34
    defaultConfig {
        applicationId 'com.kurt.smarttubedownloader'
        minSdk 25
        targetSdk 25
        versionCode 3
        versionName '1.2'
    }
    signingConfigs { release { storeFile file('keystore.jks'); storePassword 'smarttube'; keyAlias 'smarttube'; keyPassword 'smarttube' } }
    buildTypes { release { signingConfig signingConfigs.release } }
}
```

No dependencies are needed (framework APIs only, `org.json` is built in).
Then `gradle assembleRelease`. Note: the code relies on the
`com.kurt.smarttubedownloader.fileprovider` authority and provider/receiver
declared in `AndroidManifest.xml` — keep the manifest as-is.

## Signing / keystore

- Keystore: **`keystore.jks`** (included in this ZIP) — RSA 2048, alias `smarttube`,
  DN `CN=Kurt, OU=Personal, O=Personal, L=Uniondale, ST=NY, C=US`.
- Keystore password / key password: **`smarttube`**
- Keep this keystore: future rebuilds must be signed with the same key so the
  Fire TV accepts updates over the installed app.

## Install on the Fire TV (sideload)

The app itself installs SmartTube; the Downloader app has to be sideloaded once.

1. On the Fire TV: **Settings → My Fire TV → About → tap the device name 7 times**
   to unlock Developer Options.
2. **Settings → My Fire TV → Developer Options → Apps from Unknown Sources = ON**
   (and enable ADB debugging if you use adb).
3. Install the APK by either method:
   - **Downloader app:** install "Downloader" from the Amazon Appstore, open it,
     enter the URL of the APK (host the file somewhere reachable), and accept
     the install prompt.
   - **adb:** connect over USB or network
     (`adb connect <fire-tv-ip>:5555`), then run:
     `adb install SmartTubeDownloader.apk`
4. Launch **SmartTube Downloader** from the Fire TV home screen, press
   **Download & Install**, and approve the SmartTube install prompt.

## Layout of this repo

```
build.sh                              one-command manual build + sign
keystore.jks                          signing keystore (password: smarttube)
src/main/AndroidManifest.xml          manifest (permissions, leanback flags, provider, receiver)
src/main/java/com/kurt/smarttubedownloader/
    MainActivity.java                 UI + GitHub fetch + download + install prompt
    ApkFileProvider.java              read-only ContentProvider serving the downloaded APK
    InstallReceiver.java              deletes the APK when SmartTube is added/replaced
src/main/res/drawable/banner.png      320x180 Fire TV home-screen banner (red TV + "SmartTube Downloader")
src/main/res/mipmap-*/ic_launcher.png launcher icon, 5 densities (same red-TV design)
```
