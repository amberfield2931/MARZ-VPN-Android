# MARZ VPN for Android — source code

Source code of the **MARZ VPN** Android app (`com.marzvpn.app`), published under the
**GNU General Public License v3.0 or later** (`COPYING`, `LICENSE`).

MARZ VPN is a modified version of [sing-box-for-android](https://github.com/SagerNet/sing-box-for-android)
(Copyright © 2022 nekohasekai) and runs the [sing-box](https://github.com/SagerNet/sing-box) engine.
It is **not affiliated with or endorsed by SagerNet**. What MARZ changed: [`MODIFICATIONS.md`](MODIFICATIONS.md).
The original upstream README is kept as [`README.upstream.md`](README.upstream.md).

This program comes with ABSOLUTELY NO WARRANTY.

## Releases and corresponding source

**Current version: 1.0.3 (versionCode 1000390, Stable).** Its APK and complete corresponding source
(`MARZ-VPN-Android-1.0.3-corresponding-source.tar`) are published in the GitHub release
[`stable-2026-11-16`](https://github.com/amberfield2931/MARZ-VPN-Apps/releases/tag/stable-2026-11-16) of MARZ-VPN-Apps,
together with SHA-256 sums. APK SHA-256: `ca1983484f093edc940f1797ffdefe5a8828e6df179e25d6bc32bd8d1bfd1577`.
The table and notes below describe the first public build kept in this repository.

| App version | APK | Corresponding source |
|---|---|---|
| 1.0.0-rc6 (versionCode 1000008), arm64-v8a | [marzvpn.com/android](https://marzvpn.com/android) | [Release `1.0.0-rc6-1000008`](https://github.com/amberfield2931/MARZ-VPN-Android/releases/tag/1.0.0-rc6-1000008) |

This repository contains the app source of version 1000008 (MARZ commit
`be4b72c34a4349a39b72ab2cb679663d55a5c468`). The **complete corresponding source** is the release
asset `MARZ-VPN-1.0.0-rc6-1000008-corresponding-source.tar`. It has everything needed to rebuild the
published APKs, which this repository and GitHub's automatic "Source code" ZIP do not have:
- sing-box v1.14.3 with the `go.sum` used for the build;
- every Go module the engine links;
- gomobile;
- sources of the LGPL libraries;
- license texts and third-party notices;
- build instructions.

The release lists its SHA-256 values.

- Signing certificate of the published APKs (SHA-256):
  `34:1F:9B:40:05:4F:AF:E2:6C:E3:AF:D2:CD:63:3F:72:7D:0A:EF:10:45:D6:41:B9:6C:4C:59:99:0A:10:FF:6A`
- APK `MARZ-VPN-1.0.0-rc6-1000008-arm64-v8a.apk` SHA-256:
  `3786fb5a8402e63efa6a286b7cceb113950d0322fec2708f5a48d8df6dd7cad1`

## Building

See [`BUILD-RELEASE.md`](BUILD-RELEASE.md). In short:
1. Build `libbox.aar` from sing-box v1.14.3 with gomobile.
2. Copy it to `app/libs/`.
3. Run `./gradlew :app:assembleOtherRelease`.
4. Sign the result with your own key.

Built this way, the APK contents are identical to the published APK except for the signature
(checked 2026-10-28).

## Licenses

- App, sing-box and the `sagernet/sing-*` modules: GPL-3.0-or-later. The upstream `LICENSE` adds
  one term: derivative works may not use the upstream name.
- `third_party/libxposed-api`: Apache-2.0.
- Libraries: see [`THIRD-PARTY-NOTICES.txt`](THIRD-PARTY-NOTICES.txt) and [`licenses.json`](licenses.json).

The MARZ VPN service (servers, accounts, payments) is not part of this repository.

Contact: https://marzvpn.com/contact
