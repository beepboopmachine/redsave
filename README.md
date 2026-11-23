# 📥 Reddit Download Media Patch

A high-performance, crash-free [Morphe](https://github.com/MorpheApp) patch for the official **Reddit** Android app (`v2026.47.0`).

This patch injects a clean, native download button into Reddit's feed action bar and fixes Reddit's broken built-in media download feature—enabling direct, one-tap downloads for full-resolution images, multi-image galleries, GIFs, native Reddit videos, and RedGifs.

---

## ✨ Features

- **🎯 Native Feed Action Bar Integration**: Injects an unobtrusive native "Download" pill into Reddit's Jetpack Compose action bar (between Repost and Share) across all feed card variants (Classic, Plain, and Refresh layouts).
- **🖼️ High-Resolution, Clean Media Extraction**: Extracts and downloads original full-quality media without Reddit watermarks.
  - **Images & Galleries**: Automatically indexes all gallery items and downloads high-res JPEG/PNG files.
  - **Animated GIFs**: Preserves full animation in original GIF/MP4 format.
  - **Native Reddit Videos (`v.redd.it`)**: Resolves high-resolution video streams (`c0k`, `ioq0`, `xv20`).
  - **RedGifs Integration**: Automatically resolves authenticated RedGifs endpoints and downloads direct MP4 files.
- **⚡ Dual-Action Interaction**:
  - **Single Tap**: Instantly downloads media to your device.
  - **Long Press**: Opens a theme-aware options dialog showing media count, "Download single", "Download all", and a checkbox to remember your preference for future single taps.
- **🛠️ Fixes Built-in Reddit Download**: Intercepts Reddit's broken Full Bleed Player (FBP) 3-dots overflow download button (`FbpImageDownloadWorker`), replacing it with this robust background downloader.
- **📁 Organized Storage**: Automatically saves media to standard public folders:
  - Pictures: `/sdcard/Pictures/Reddit/`
  - Videos: `/sdcard/Movies/Reddit/`
- **🔒 High Stability & Zero Crashes**: Specially hardened against R8 obfuscation:
  - No `kotlin.Unit.INSTANCE` collisions or class-cast exceptions.
  - Isolated from comments, profile, navigation, and share drawers.
  - Safe against Android OS clipboard and storage permission restrictions.

---

## 📱 Compatibility

| Property | Value |
| :--- | :--- |
| **Target Application** | Reddit (`com.reddit.frontpage`) |
| **Target Version** | `v2026.47.0` (Version code: `2647001`) |
| **Minimum Android Version** | Android 12L (API level 32+) |
| **Architecture** | `arm64-v8a`, `armeabi-v7a` |

> [!IMPORTANT]
> When patching Reddit `v2026.47.0`, always use an **APKM bundle** or merge the base APK with its resource/density splits (e.g. `split_config.xxhdpi.apk`). Installing a standalone base APK missing density splits will cause Reddit's native resources to crash on the profile and share sheets.

---

## 🚀 How to Patch

### Option 1: Morphe Manager (Easiest)

1. Open **Morphe Manager** on your Android device.
2. Go to **Settings** > **Sources**.
3. Add this repository's GitHub URL as a custom patch source.
4. Go back to the dashboard, select **Reddit** (`v2026.47.0`), enable **Download media**, and tap **Patch**.
5. Install the generated APK.

---

### Option 2: Morphe CLI (Command Line)

1. **Clone this repository**:
   ```bash
   git clone https://github.com/<your-username>/red-pat.git
   cd red-pat
   ```

2. **Build the patch bundle**:
   ```bash
   ./gradlew :patches:build
   ```
   The compiled `.mpp` patch bundle will be generated in `patches/build/libs/patches-1.0.0.mpp`.

3. **Patch your Reddit APKM / APK**:
   ```bash
   java -jar morphe-cli.jar patch \
       --patch-bundle patches/build/libs/patches-1.0.0.mpp \
       --out reddit-patched.apk \
       reddit.apkm
   ```

4. **Install on your device**:
   ```bash
   adb install -r reddit-patched.apk
   ```

---

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.0.0](https://github.com/beepboopmachine/red-pat/releases/tag/v1.0.0)**&nbsp;&nbsp;&middot;&nbsp;&nbsp;`main`&nbsp;&nbsp;&middot;&nbsp;&nbsp;**31 patches** across **1 apps**&nbsp;&nbsp;&middot;&nbsp;&nbsp;[Full details](PATCHES.md)

| # | App | Patches | Version | Package |
|---|---|---|---|---|
| 1 | [**Reddit**](PATCHES.md#reddit-comredditfrontpage) | 30 | `2026.47.0`, `2026.46.0`, `2026.45.0`, `2026.44.0`, `2026.43.0`, `2026.24.0` | [`com.reddit.frontpage`](https://play.google.com/store/apps/details?id=com.reddit.frontpage) |
| 2 | [**Universal**](PATCHES.md#universal) | 1 | — | — |
<!-- PATCHES_END -->

---

## 🏗️ Architecture & How It Works

```
                        Reddit Compose Feed Card
                                   │
              ┌────────────────────┴────────────────────┐
              ▼                                         ▼
   FeedActionBarDispatch Hook              PostActionBarDispatch Hook
       (m5s0.c / mtj)                           (ttd0.a / mtj)
              │                                         │
              └────────────────────┬────────────────────┘
                                   ▼
                      DownloadActionButton.kt
                     (Injected Compose Pill)
                                   │
           ┌───────────────────────┴───────────────────────┐
           ▼ (Tap)                                         ▼ (Long Press)
   Fast-Path Download                            Options AlertDialog
           │                                 ("Download all", "Save default")
           └───────────────────────┬───────────────────────┘
                                   ▼
                        MediaDownloadHelper.kt
                  ┌────────────────┴────────────────┐
                  ▼                                 ▼
         Direct CDN Download              Android DownloadManager
       (/sdcard/Pictures/Reddit)          (/sdcard/Movies/Reddit)
```

1. **Smali Bytecode Injections (`patches/`)**:
   - `DownloadMediaPatch.kt`: Uses dexlib2 instruction transformation to inject Compose action bar pills into `h30.f` (Classic), `h30.d` (Plain), and `h30.q` (Refresh).
   - Dynamic parameter register slot calculation accounts for wide types (`J`/`D`) without register clobbering.
   - Dynamic `Lkotlin/Unit;` field name resolution avoids hardcoding obfuscated field names.
   - Intercepts Full Bleed Player event handler (`com.reddit.fullbleedplayer.data.events.c.a`) to handle `s8h` and `t8h` events.

2. **Android Runtime Extension (`extensions/`)**:
   - `DownloadActionButton.kt`: Coordinates Compose button rendering, touch handling, and post media metadata extraction.
   - `MediaDownloadHelper.kt`: Extracts media URLs from complex Reddit post models, resolves RedGifs tokens, and handles file persistence via `DownloadManager`.
   - `DownloadActionCallback.java`: Interop bridge providing stable equals/hash code contracts for Compose recomposition.
   - `ExtensionStrUtils.java`: Pure-Java string manipulation helpers immune to R8 `kotlin.text.StringsKt` stripping.

---

## 🛠️ Building from Source

### Prerequisites
- JDK 17 or higher
- Android SDK (API 34/35)

### Build Commands
```bash
# Clean and build the entire patch bundle
./gradlew clean :patches:build

# Output patch bundle location:
# patches/build/libs/patches-1.0.0.mpp
```

---

## 📄 License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
Part of the [Morphe](https://github.com/MorpheApp) ecosystem.
