# DownLS10 v3.0 — GitHub APK build

This repository is an Android Gradle project. It is configured with `applicationId com.downls10`, `versionName 3.0`, and `versionCode 300`.

## Build on GitHub using a phone
1. Create a GitHub repository.
2. Upload the **contents of this folder** to the repository root (the root must contain `build.gradle`, `settings.gradle`, `src/`, and `.github/`). Do not upload only the ZIP as a file.
3. Open **Actions** → **Build DownLS10 APK** → **Run workflow**. A push to `main` or `master` also triggers a build.
4. When the run succeeds, open it and download the `DownLS10-v3.0-debug-apk` artifact.

## Build details
- JDK 17
- Android Gradle Plugin 8.2.2
- Gradle 8.2
- compileSdk 34 / targetSdk 34 / minSdk 24
- Debug APK is unsigned for release distribution and is intended for testing.

If the app still crashes at launch, download the APK and collect an Android crash log; source-level safeguards cannot prove the cause without the device's actual exception.
