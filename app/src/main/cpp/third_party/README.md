# Third-party software

## SoundTouch

- Version: 2.4.1
- Upstream: <https://codeberg.org/soundtouch/soundtouch>
- Source commit: `f738b1132ec1fd56efc90367898244cf52d9e6a5`
- Copyright: Olli Parviainen and SoundTouch contributors
- License: GNU Lesser General Public License 2.1 or later (`LGPL-2.1-or-later`)
- License text: [`soundtouch/COPYING.TXT`](soundtouch/COPYING.TXT)
- Host project license: GNU General Public License v3 (`../../../../../LICENSE`)
- Local changes: none in the vendored SoundTouch source files; Android integration and
  compile options are defined in `app/CMakeLists.txt`.

The vendored source is built as a static library with 32-bit floating-point samples and
linked into the Android native frontend. From the repository root, rebuild it with:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
.\gradlew.bat :app:buildCMakeRelWithDebInfo
```

Binary distributors are responsible for the LGPL notice, license-copy, corresponding-source,
and relinking requirements. The simplest source distribution is the exact application and
SoundTouch source used for the binary, including this CMake build integration and build
instructions, so recipients can replace SoundTouch and rebuild the application.
