# Security

Use the newest installer from [Paperwork downloads](https://github.com/ishafayetul/paperwork-pdf/releases/latest). Public security fixes are listed with the relevant release.

## Reporting vulnerabilities

Use GitHub’s private vulnerability reporting under the repository’s **Security** tab. Describe the version, platform, affected feature, and expected result. Use a minimal sample with synthetic data whenever possible and keep private documents, credentials, and signing keys out of reports.

## Document and camera handling

Paperwork processes files on the device. The Android app requests no network or broad storage permissions and uses the system file picker and a temporary camera grant. Camera behavior also depends on the camera app installed on the device. Draft scan images stay in app-private storage. Exporting a file writes to the location you choose.

Cropping and on-page annotations leave the original content in the PDF. Reassembling or converting an encrypted PDF can produce an unencrypted export. Use the **Protect PDF** tool if the output needs its own opening password.
