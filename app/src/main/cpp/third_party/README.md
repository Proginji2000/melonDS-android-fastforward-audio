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

## Rubber Band Library

- Version/tag: 4.0.0 (`v4.0.0`)
- Upstream: <https://github.com/breakfastquay/rubberband>
- Source commit: `1d95888bec3ae0a17c0c4af791810d5a63f6bc35`
- Copyright: 2007-2024 Particular Programs Ltd
- License: GNU General Public License 2.0 or later (`GPL-2.0-or-later`)
- License text: [`rubberband/COPYING`](rubberband/COPYING)
- Host project license: GNU General Public License v3 (`../../../../../LICENSE`)
- Local changes: none in the vendored Rubber Band source files

The vendored source is built as a static library through the official
`single/RubberBandSingle.cpp` compilation unit. On Android this unit selects Rubber Band's
built-in FFT and BQResampler implementations and disables its internal timing and threading,
so no external FFT, resampler, or dynamic Rubber Band library is required. The retained
source tree is the exact active include closure for that built-in configuration, together with
the upstream licence and build/version documentation.

Binary distributors must preserve the Rubber Band copyright and GPL notice and provide the
complete corresponding source under GPL-compatible terms. The GPLv3 host project satisfies
the GPL-2.0-or-later compatibility requirement when the combined application is distributed
under GPLv3.
