# Install Paperwork

Get the right installer and `SHA256SUMS` from the [Paperwork downloads page](https://github.com/ishafayetul/paperwork-pdf/releases/latest).

## Android

1. Download the signed APK on an Android 9 or later phone or tablet.
2. Open it from the Android Files app. If Android prompts you, allow installation from Files for this install and tap **Install**.
3. Open **Paperwork** from the launcher. For document capture, open **Scanner** and choose **Take photo**.

Install a newer APK over the existing app to keep your local drafts. Android may warn that an older APK was signed by someone else if you installed a build from different source or signing key.

## Ubuntu

Download the `amd64.deb` for Ubuntu 24.04 on an x86-64 machine. In a terminal opened in the download folder, run the command below with the actual downloaded filename.

```sh
sudo apt install ./paperwork-pdf_1.0.1_amd64.deb
```

Launch **Paperwork** from the application menu or run `paperwork-pdf` in a terminal.

## Verify a download

Keep `SHA256SUMS` in the same folder as your installer, then run `sha256sum --ignore-missing -c SHA256SUMS` on Linux. It checks whichever installer files you downloaded.

If you have Android Build Tools, run `apksigner verify --print-certs paperwork-android-1.0.1.apk` to check the APK signature. The published signing certificate SHA-256 digest is:

```text
862f2b3dfc75f3eb35ee0f42ca3e5513c0926600ae1ff582c6c253206476ff5a
```
