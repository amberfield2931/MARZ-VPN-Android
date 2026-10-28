# MARZ VPN for Android — modification notice

This is the corresponding source of **MARZ VPN 1.0.0-rc6 (1000008)** (package `com.marzvpn.app`),
built from MARZ commit `be4b72c34a4349a39b72ab2cb679663d55a5c468`.

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
