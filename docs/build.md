# Build from source

## Ubuntu desktop

Target Ubuntu 24.04 on x86-64. Install Python 3.11 or newer, pip, PyQt5 (`python3-pyqt5`), and the Debian packaging tools. From the repository root, install the hash-pinned PDF engine, then launch the app.

```sh
python3 -m pip --isolated install --index-url https://pypi.org/simple --only-binary=:all: --no-deps --require-hashes --target .runtime -r requirements-linux-amd64.txt
./launch-paperwork
```

Install `build-essential`, `curl`, and `patch` to build the native library and installer. Run `/usr/bin/python3 -m tests.smoke` with `python3-pil` installed. Build the installer with `./packaging/build-deb`. The script compiles the patched MuPDF library and writes the package to `dist/`.

## Android

Use a JDK 17 or newer, Android SDK Platform 35, Build Tools 35, and NDK r28c. Building also requires `curl`, `patch`, `zip`, and `openssl`. The APK build uses the Android MuPDF bindings from the pinned Artifex source archive.

```sh
PAPERWORK_PLATFORM_JAR="${ANDROID_HOME}/platforms/android-35/android.jar" \
PAPERWORK_ANDROID_TOOLS="${ANDROID_HOME}/build-tools/35.0.0" \
PAPERWORK_NDK="${ANDROID_HOME}/ndk/28.2.13676358" \
./android/build-apk
```

The script writes a signed APK to `dist/`. It creates and reuses a local signing key under `android/.work/signing/`. Android accepts a build as an update only when it uses the same signing key as the installed app.

Set `PAPERWORK_ADB` to the SDK’s `adb` path and `PAPERWORK_SERIAL` to a test device or emulator. Run `./android/tests/run-smoke` after building. The test installs the app and a matching test APK, then checks scan geometry, filters, and outputs from all PDF tools.
