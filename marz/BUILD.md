# Building MARZ VPN for Android

MARZ VPN is a fork of [sing-box-for-android](https://github.com/SagerNet/sing-box-for-android)
(GPL-3.0-or-later). It is not affiliated with or endorsed by SagerNet.

## Pinned inputs

| Input | Version |
|---|---|
| sing-box (libbox source) | tag `v1.14.3`, commit `7054cac5124465ffdb145870d7c01885566711b1` |
| Upstream SFA base | commit `ae93152f21457723af77d144f28c1f7be7362aec` (1.14.3) |
| Go | 1.26.8 |
| gomobile / gobind | `github.com/sagernet/gomobile` v0.1.13 |
| JDK for libbox | OpenJDK 17 (the libbox build script requires it) |
| JDK for Gradle | OpenJDK 21 (17 also works) |
| Android SDK | platforms 37.1, 24 and 21; build-tools 37.0.0; NDK 28.0.13004108 |

## 1. libbox.aar

```sh
git clone --branch v1.14.3 https://github.com/SagerNet/sing-box.git
cd sing-box
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.13
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.13
export JAVA_HOME=<jdk17> ANDROID_HOME=<sdk> ANDROID_NDK_HOME=<sdk>/ndk/28.0.13004108
go run ./cmd/internal/build_libbox -target android
cp libbox.aar libbox-legacy.aar <this repo>/app/libs/
```

## 2. APK

```sh
echo "sdk.dir=<sdk>" > local.properties
JAVA_HOME=<jdk21> ./gradlew :app:assembleOtherDebug
# app/build/outputs/apk/other/debug/MARZ-VPN-<version>-{arm64-v8a,armeabi-v7a,x86,x86_64,universal}-debug.apk
```

Debug builds use the local Android debug key. Production (release) signing uses the permanent MARZ
release key kept outside the repository — see `marz/RELEASE.md`. The upstream `app/release.keystore`
is never used for MARZ VPN builds.

## 3. Pilot APK (owner test against the production App API)

```sh
# pilot.properties (outside the repo, 0600): MARZ_PILOT_KEYSTORE, MARZ_PILOT_KEYSTORE_PASS, MARZ_PILOT_KEY_ALIAS
LOCAL_PROPERTIES=$(base64 -w0 <private>/pilot.properties) JAVA_HOME=<jdk21> ./gradlew :app:assembleOtherPilot
# app/build/outputs/apk/other/pilot/MARZ-VPN-<version>-arm64-v8a-pilot.apk  (com.marzvpn.app.pilot)
```

- `pilot` = release code paths (R8, no developer tools, upstream UI disabled), API
  `https://app-api.marzvpn.com`, HTTPS only, system CAs only, the API host pinned to the root CAs
  Cloudflare issues from (`app/src/pilot/res/xml/marz_pilot_network.xml`, expires 2027-10-01).
- Signed with a separate pilot key (never the future store key); package `.pilot`, so it installs
  next to the lab debug build.
- `pilotLab` (TEST ONLY): the same minified code against the lab App API on `127.0.0.1` (adb reverse), so
  `marz/ui-checks/e2e31.sh` runs on R8 output: `PKG=com.marzvpn.app.pilotlab APK=... e2e31.sh`.
