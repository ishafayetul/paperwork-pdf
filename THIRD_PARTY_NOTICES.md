# License and corresponding source

Paperwork is released under the GNU Affero General Public License version 3. The [license text](LICENSE) accompanies the application. Use the GitHub release’s source archive for the version corresponding to the installer.

| Installer | Bundled component | Corresponding source |
| --- | --- | --- |
| Ubuntu | PyMuPDF Lite 2.2 with MuPDF 1.28.6, by Artifex Software | [PyMuPDF Lite 2.2 distribution](https://pypi.org/project/pymupdf-lite/2.2/#files) and [MuPDF 1.28.6 source archive](https://github.com/ArtifexSoftware/mupdf-downloads/releases/download/1.28.6/mupdf-1.28.6-source.tar.gz) |
| Android | MuPDF 1.28.6 and its Android Java/JNI bindings, by Artifex Software | [MuPDF 1.28.6 source archive with bundled libraries](https://github.com/ArtifexSoftware/mupdf-downloads/releases/download/1.28.6/mupdf-1.28.6-source.tar.gz) |

The [build instructions](docs/build.md) and [backport patches](packaging/patches) in this repository show how the applications are compiled and packaged. PyQt5 comes from Ubuntu’s package repositories. Android platform APIs come from Android.

The installers include [MuPDF dependency and font notices](packaging/mupdf-third-party-notices.txt).
