# MARZ VPN for Android — source code of 1.0.1 (versionCode 1000092)

This commit holds the app source of **MARZ VPN for Android 1.0.1** (versionCode 1000092), package
`com.marzvpn.app`. **It is not the current version** — the current one is on the
[`main`](https://github.com/amberfield2931/MARZ-VPN-Android) branch.

| | |
|---|---|
| Git tag | `1.0.1-1000092` |
| Release with the APK and the complete corresponding source | [MARZ-VPN-Apps `stable-2026-11-04`](https://github.com/amberfield2931/MARZ-VPN-Apps/releases/tag/stable-2026-11-04) |
| `MARZ-VPN-Android-1.0.1-corresponding-source.tar` SHA-256 | `6566f631dfae5fc22792542dc042cea038a93801ce4fdeb5eac7effa1a3b990c` |
| `MARZ-VPN-Android-1.0.1.apk` (arm64-v8a) SHA-256 | `183921b6bc91812d289413a9b4d656d8526662fa0bd0e9f150cf47cc1c296800` |
| APK signing certificate SHA-256 | `341f9b40054fafe26ce3afd2cd633f727d0aef1045d641b96c4c59990a10ff6a` |
| MARZ development commit | `65e831e8e395eb069d795996e0492a9d1b034ff1` |

The files of this tree are the app archive of that corresponding-source tar, unchanged, except:
- `README.md` (this file; the upstream README is `README.upstream.md`);
- `COPYING`, `licenses.json`, `THIRD-PARTY-NOTICES.txt`, `MODIFICATIONS.md` and `BUILD-RELEASE.md`
  (`BUILD.md` of the tar) are copied from the top level of the tar;
- `marz/tools/scan_source_bundle.py`, MARZ's pre-publication scanner, is not used to build the app and
  is only in the tar.

The **complete corresponding source** — the sing-box engine, every Go module it links, gomobile, the
sources of the LGPL libraries, license texts and build instructions — is the tar in the release above.
GitHub's automatic "Source code" archives hold only this tree and are not enough to rebuild the APK.

MARZ VPN is free software under the GNU General Public License v3.0 or later (`COPYING`, `LICENSE`)
and comes with ABSOLUTELY NO WARRANTY. It is a modified version of
[sing-box-for-android](https://github.com/SagerNet/sing-box-for-android) (Copyright © 2022 nekohasekai)
and is not affiliated with or endorsed by SagerNet.
