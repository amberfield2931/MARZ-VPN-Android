# MARZ VPN for Android — source code

Source code of the **MARZ VPN** Android app (`com.marzvpn.app`), published under the
**GNU General Public License v3.0 or later** (`COPYING`, `LICENSE`).

MARZ VPN is a modified version of [sing-box-for-android](https://github.com/SagerNet/sing-box-for-android)
(Copyright © 2022 nekohasekai) and runs the [sing-box](https://github.com/SagerNet/sing-box) engine.
It is **not affiliated with or endorsed by SagerNet**. What MARZ changed: [`MODIFICATIONS.md`](MODIFICATIONS.md).
The original upstream README is kept as [`README.upstream.md`](README.upstream.md).

This program comes with ABSOLUTELY NO WARRANTY.

## Current version: 1.0.3 (versionCode 1000390), Stable

The `main` branch (tag [`1.0.3-1000390`](https://github.com/amberfield2931/MARZ-VPN-Android/releases/tag/1.0.3-1000390))
is the app source of the published **1.0.3**.

| | |
|---|---|
| Download | [marzvpn.com/android](https://marzvpn.com/android) · GitHub release [`stable-2026-11-16`](https://github.com/amberfield2931/MARZ-VPN-Apps/releases/tag/stable-2026-11-16) of MARZ-VPN-Apps · [SourceForge](https://sourceforge.net/projects/marz-vpn/) |
| `MARZ-VPN-Android-1.0.3.apk` (Android 7.0+, arm64-v8a) SHA-256 | `ca1983484f093edc940f1797ffdefe5a8828e6df179e25d6bc32bd8d1bfd1577` |
| Complete corresponding source `MARZ-VPN-Android-1.0.3-corresponding-source.tar` SHA-256 | `4769966d30e217812b3184148134d6bad9ff01dc7aee100256609e35a233b94f` |
| APK signing certificate SHA-256 | `341f9b40054fafe26ce3afd2cd633f727d0aef1045d641b96c4c59990a10ff6a` |
| MARZ development commit | `5aaed7b8ffdb2b4bf0c27e837ebc1be286758ac1` |

**Installing and updating.** Install the APK from [marzvpn.com/android](https://marzvpn.com/android).
A newer MARZ VPN installs over the old one and keeps your sign-in and settings, because every release is
signed with the same certificate (above). The app looks for updates only at
`https://marzvpn.com/updates/manifest.json` (at most once a day, or when you press the button in Settings)
and only shows a notice; it never installs anything by itself. Android does not install an older version
over a newer one without removing the app first.

## Versions and where their source is

| Version | versionCode | Git tag (app source) | Release with APK and complete corresponding source |
|---|---|---|---|
| **1.0.3** (Stable, current) | 1000390 | [`1.0.3-1000390`](https://github.com/amberfield2931/MARZ-VPN-Android/tree/1.0.3-1000390) | [MARZ-VPN-Apps `stable-2026-11-16`](https://github.com/amberfield2931/MARZ-VPN-Apps/releases/tag/stable-2026-11-16) |
| 1.0.2 | 1000093 | [`1.0.2-1000093`](https://github.com/amberfield2931/MARZ-VPN-Android/tree/1.0.2-1000093) | [MARZ-VPN-Apps `stable-2026-11-05-2`](https://github.com/amberfield2931/MARZ-VPN-Apps/releases/tag/stable-2026-11-05-2) |
| 1.0.1 | 1000092 | [`1.0.1-1000092`](https://github.com/amberfield2931/MARZ-VPN-Android/tree/1.0.1-1000092) | [MARZ-VPN-Apps `stable-2026-11-04`](https://github.com/amberfield2931/MARZ-VPN-Apps/releases/tag/stable-2026-11-04) |
| 1.0.0 | 1000091 | [`1.0.0-1000091`](https://github.com/amberfield2931/MARZ-VPN-Android/tree/1.0.0-1000091) | [MARZ-VPN-Apps `beta-2026-11-03`](https://github.com/amberfield2931/MARZ-VPN-Apps/releases/tag/beta-2026-11-03) |
| 1.0.0-rc6 (old release candidate) | 1000008 | [`1.0.0-rc6-1000008`](https://github.com/amberfield2931/MARZ-VPN-Android/tree/1.0.0-rc6-1000008) | [`1.0.0-rc6-1000008`](https://github.com/amberfield2931/MARZ-VPN-Android/releases/tag/1.0.0-rc6-1000008) in this repository |

Each version's tree is the app archive of its published corresponding-source tar, unchanged, except:
- `README.md` (this file; the upstream README is `README.upstream.md`);
- `COPYING`, `licenses.json`, `THIRD-PARTY-NOTICES.txt`, `MODIFICATIONS.md` and `BUILD-RELEASE.md`
  (`BUILD.md` of the tar) are copied from the top level of the tar;
- `marz/tools/scan_source_bundle.py`, MARZ's pre-publication scanner, is not used to build the app and
  is only in the tar.

The **complete corresponding source** of each APK is the tar named in its release: the app, sing-box
v1.14.3 with the `go.sum` used for the build, every Go module the engine links, gomobile v0.1.13, the
sources of the LGPL libraries in the APK, license texts and build instructions. GitHub's automatic
"Source code" archives hold only the app tree and are not enough to rebuild the APK.

The APKs and the source tars are published once, in the [MARZ-VPN-Apps](https://github.com/amberfield2931/MARZ-VPN-Apps)
releases (all MARZ VPN apps together), on [marzvpn.com](https://marzvpn.com/android) and on
[SourceForge](https://sourceforge.net/projects/marz-vpn/). This repository keeps the Android source history.

## Building

See [`BUILD-RELEASE.md`](BUILD-RELEASE.md). In short:
1. Build `libbox.aar` from sing-box v1.14.3 with gomobile.
2. Copy it to `app/libs/`.
3. Run `./gradlew :app:assembleOtherRelease`.
4. Sign the result with your own key.

`BUILD-RELEASE.md` is copied unchanged from the tar. Two details in it are out of date:
- its title and example file names still say `1.0.0-rc6`;
- the app archive unpacks to `MARZ-VPN-1.0.3-1000390-source/`, not `MARZ-VPN/`.

The unsigned APKs come out as `app/build/outputs/apk/other/release/MARZ-VPN-1.0.3-<abi>-unsigned.apk`.

**Checked on 2026-11-17.** The app was rebuilt offline from `MARZ-VPN-Android-1.0.3-corresponding-source.tar`
(Gradle 9.7.0, JDK 21, Android SDK/NDK as in `BUILD-RELEASE.md`). Every entry of the rebuilt arm64-v8a APK is identical to the
published `MARZ-VPN-Android-1.0.3.apk`, except the three signature entries. The 81 JVM unit tests pass.

For that build the engine `libbox.aar` was not rebuilt; MARZ's existing build was used. Its `libbox.so` is the same file in
1.0.0-rc6, 1.0.2 and 1.0.3. The engine inputs in the 1.0.3 tar (sing-box, Go modules, gomobile) are byte-identical to those
of the rc6 tar. On 2026-10-28, `libbox.so` was rebuilt from the rc6 tar and came out byte-identical.

## Licenses

- App, sing-box and the `sagernet/sing-*` modules: GPL-3.0-or-later. The upstream `LICENSE` adds
  one term: derivative works may not use the upstream name.
- `third_party/libxposed-api`: Apache-2.0.
- Libraries: see [`THIRD-PARTY-NOTICES.txt`](THIRD-PARTY-NOTICES.txt) and [`licenses.json`](licenses.json).

The MARZ VPN service (servers, accounts, payments) is not part of this repository.

Contact: https://marzvpn.com/contact
