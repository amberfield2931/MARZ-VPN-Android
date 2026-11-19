# MARZ VPN for Android — modification notice

This is the corresponding source of **MARZ VPN 1.0.4 (1000400)** (package `com.marzvpn.app`),
built from the app source tarball in this archive.

MARZ VPN is a **modified version of sing-box-for-android (SFA)**:

- Upstream: https://github.com/SagerNet/sing-box-for-android, commit
  `ae93152f21457723af77d144f28c1f7be7362aec` (SFA 1.14.3, 2026-10-09).
- Copyright (C) 2022 by nekohasekai <contact-sagernet@sekai.icu> — see `LICENSE`.
- Modified by the MARZ VPN project (https://marzvpn.com) between 2026-10-19 and 2026-10-27.

MARZ VPN is **not affiliated with or endorsed by SagerNet or the sing-box project**, and it is
distributed under its own name, as the upstream `LICENSE` asks.

## License

The application, as modified, is distributed under the **GNU General Public License, version 3 or
(at your option) any later version** (`LICENSE`, `COPYING-GPL-3.txt`), with the additional term in
the upstream `LICENSE` on use of the name. `third_party/libxposed-api` is Apache-2.0. Libraries
fetched by Gradle and Go keep their own licenses (`THIRD-PARTY-NOTICES.txt`, `licenses.json`).

The program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.

## What MARZ changed

The full change set against the upstream commit is `marz-changes-vs-upstream.patch`. In summary:

- Application ID `com.marzvpn.app`, name, icon and colours; build flavours `pilot` and `pilotLab`;
  release signing read from properties outside the repository; version `version.properties`.
- New package `com.marzvpn.app` (`app/src/main/java/com/marzvpn/app`): the MARZ user interface
  (home, servers, plans, sign-in, settings), the client of the MARZ App API (`app-api.marzvpn.com`,
  certificate-pinned network security config), sign-in by Telegram or e-mail link, checkout through
  the web page, route selection and route checks, engine diagnostics. It runs the unmodified sing-box
  engine (`libbox`) through the upstream service classes.
- The upstream SFA user interface is kept but reachable only from Diagnostics; upstream update
  checks are disabled; backup and data-extraction rules tightened; small upstream fixes in
  `io.nekohasekai.sfa` listed in the patch.
- Translations (EN, RU, FA, ZH-CN) for the MARZ screens.

The sing-box engine (`libbox.aar`) is built **without source changes** from sing-box `v1.14.3`
(`7054cac5124465ffdb145870d7c01885566711b1`); the only local difference is additional `go.sum`
entries (`sing-box-v1.14.3-source.tar.gz` contains that `go.sum`).

Removed from this archive (not used by the build): the upstream test keystore `app/release.keystore`,
device-lab test scripts `marz/ui-checks`, `marz/diag` and internal release reports in `marz/`
(`marz/BUILD.md` still mentions some of them; `BUILD-RELEASE.md` is the build guide for this source).
No signing key, password, token or user data is part of this source.

## Changes in 1.0.0 (1000091) against 1.0.0-rc6 (1000008)

`marz-changes-rc6-to-1.0.0.patch`: the app calls itself MARZ VPN in five inherited strings, an accurate open-source note, a source code row in Settings, full third-party notices in the APK. The engine `libbox` and every dependency are the same as in 1.0.0-rc6. `marz-changes-vs-upstream.patch` lists the changes up to rc6.

## Changes in 1.0.1 (1000092) against 1.0.0 (1000091)

`marz-changes-1.0.0-to-1.0.1.patch`: the connect button stays green while the VPN is left on, also without a network; a text says the VPN comes back by itself; a mobile IMS network no longer counts as internet. The engine `libbox` and every dependency are the same as in 1.0.0.

## Changes in 1.0.2 (1000093) against 1.0.1 (1000092)
`marz-changes-1.0.1-to-1.0.2.patch`: a "Check for updates" button in Settings, a notice on the home screen and at most one automatic check a day at start, reading https://marzvpn.com/updates/manifest.json only. The app opens the official download page and never downloads or installs anything itself; no account, token or device data is sent. Store (Play) builds have no check.

## Changes in 1.0.3 (1000390) against 1.0.2 (1000093)
`marz-changes-1.0.2-to-1.0.3.patch`: the plans come from the public App API catalog (GET https://app-api.marzvpn.com/v1/catalog, no account data sent); the app offers Basic and, only when the catalog describes it, Pro and Pro 6 months, and shows the Pro package state of a signed-in Pro customer. The engine `libbox` and every dependency are the same as in 1.0.2.

## Changes in 1.0.4 (1000400) against 1.0.3 (1000390)
`marz-changes-1.0.3-to-1.0.4.patch`: sign-in with a one-time code from the MARZ VPN Telegram bot (the app asks the App API for a sign-in request, the user opens the bot, the bot shows a 6-digit code, the user enters it in the app); the code screen and its texts in English, Russian, Persian and Simplified Chinese. The engine `libbox` and every dependency are the same as in 1.0.3 and 1.0.2.
`marz/tools/scan_source_bundle.py` (an internal pre-publication scanner, not used to build the app) is no longer included.
