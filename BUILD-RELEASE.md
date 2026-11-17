# Building MARZ VPN 1.0.0-rc6 (1000008) from this source

The published APKs were built from exactly these inputs. Following the steps gives byte-identical
native engines (`libbox.so`) and an APK whose contents match the published one except for the
signature, which needs the MARZ release key (not distributed). You can sign your own build with your
own key and install it; nothing in the app or the MARZ service checks the signing key.

## Bundle

| File | What |
|---|---|
| `MARZ-VPN-1.0.3-1000390-source.tar.gz` | the Android app (MARZ commit in `source-provenance.json`), `MODIFICATIONS.md` |
| `sing-box-v1.14.3-source.tar.gz` | sing-box `v1.14.3` (`7054cac5…11b1`) with the `go.sum` used for the build |
| `go-modules-sing-box-v1.14.3.tar.gz` | every Go module the engine links, in `GOPROXY=file://` layout (checked against `go.sum`) |
| `gomobile-v0.1.13-source.tar.gz` | `github.com/sagernet/gomobile` v0.1.13 |
| `marz-changes-vs-upstream.patch` | all MARZ changes against SFA `ae93152f…` |
| `third-party-android-sources.tar.gz` | sources of the LGPL-2.1 libraries in the APK (sora-editor 0.23.6, android-tree-sitter 4.3.2) and of their tree-sitter parts |
| `THIRD-PARTY-NOTICES.txt`, `licenses.json`, `COPYING-*`, `LICENSE-*` | licenses |

## Toolchain

| Tool | Version |
|---|---|
| Go | 1.26.8 (`GOTOOLCHAIN=local`) |
| JDK | OpenJDK 17 for gomobile, OpenJDK 21 for Gradle |
| Android SDK | platforms `android-37.1`, `android-24`; build-tools 37.0.0; NDK 28.0.13004108 |
| Gradle | 9.7.0 (wrapper) |
| Host | Linux x86_64 |

## 1. Engine: `libbox.aar`

Run everything from the directory that holds the bundle files (`$B` below).

```sh
B=$PWD; mkdir build && cd build
for f in sing-box-v1.14.3 go-modules-sing-box-v1.14.3 gomobile-v0.1.13; do tar xzf $B/$f*.tar.gz; done
export GOPATH=$PWD/gopath GOPROXY=file://$PWD/go-proxy GOSUMDB=off GOFLAGS=-mod=mod GOTOOLCHAIN=local
export PATH=$GOPATH/bin:$PATH JAVA_HOME=<jdk17> ANDROID_HOME=<sdk> ANDROID_NDK_HOME=<sdk>/ndk/28.0.13004108
(cd gomobile && go install ./cmd/gomobile ./cmd/gobind)
cd sing-box
TAGS=with_gvisor,with_quic,with_wireguard,with_utls,with_naive_outbound,with_clash_api,with_usbip,with_openvpn,with_openconnect,badlinkname,tfogo_checklinkname0,with_tailscale,ts_omit_logtail,ts_omit_ssh,ts_omit_drive,ts_omit_taildrop,ts_omit_webclient,ts_omit_doctor,ts_omit_capture,ts_omit_kube,ts_omit_aws,ts_omit_synology,ts_omit_bird
gomobile bind -o libbox.aar -target android -androidapi 24 -javapkg=io.nekohasekai -libname=box \
  -trimpath -buildvcs=false -tags "$TAGS" \
  -ldflags "-X github.com/sagernet/sing-box/constant.Version=1.14.3 -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid=" \
  ./experimental/libbox
```

These are the flags of `go run ./cmd/internal/build_libbox -target android` (main variant) with the
version written out (`1.14.3`, as the script derives it from the tag). The `go-proxy` module zips are
verified by Go against `go.sum`, so they are the public upstream module sources. `-target android/arm64`
(or `arm`, `386`, `amd64`) builds one ABI.

`github.com/sagernet/cronet-go/lib/android_*` ships `libcronet.a`, a static library SagerNet builds
from naiveproxy/Chromium (cronet-go commit `049f2909701ca088c9569f6b303bba5376a2b2ff`, naiveproxy
`https://github.com/SagerNet/naiveproxy/tree/72a06c9fca0e2d228588c7f3074bf7efff3ff686`). MARZ uses it unchanged.

## 2. APK

```sh
cd $B && tar xzf MARZ-VPN-1.0.3-1000390-source.tar.gz && cd MARZ-VPN
mkdir -p app/libs && cp $B/build/sing-box/libbox.aar app/libs/
echo "sdk.dir=<sdk>" > local.properties
JAVA_HOME=<jdk21> ./gradlew :app:assembleOtherRelease
# app/build/outputs/apk/other/release/MARZ-VPN-1.0.0-rc6-{arm64-v8a,armeabi-v7a,x86,x86_64,universal}-unsigned.apk
```

Gradle downloads the Android libraries listed in `licenses.json` from Google Maven, Maven Central,
the Gradle plugin portal, JitPack and api.xposed.info (the versions are pinned in
`gradle/libs.versions.toml` and `app/build.gradle.kts`). `marz/BUILD.md` inside the app archive is the
developer guide; it mentions internal files (`marz/RELEASE.md`, `marz/ui-checks`) that are not part of
this source — use this file instead.

Without `MARZ_RELEASE_KEYSTORE` the release APKs are unsigned. Sign with your own key:

```sh
<sdk>/build-tools/37.0.0/apksigner sign --ks my.jks --out my.apk MARZ-VPN-1.0.0-rc6-arm64-v8a-unsigned.apk
```

Android does not let an APK signed with another key update the installed MARZ VPN: uninstall it
first (your account is on the server; sign in again), or change `applicationId` in
`app/build.gradle.kts` to install side by side. The `otherLegacy` (Android 5–6) flavour needs
`libbox-legacy.aar` (the same command with `-androidapi 21` and without `with_naive_outbound`); MARZ
does not publish that flavour.

## 3. Compare with the published APK

```sh
unzip -o -q <downloaded>/MARZ-VPN-1.0.0-rc6-1000008-arm64-v8a.apk -d published
unzip -o -q app/build/outputs/apk/other/release/MARZ-VPN-1.0.0-rc6-arm64-v8a-unsigned.apk -d mine
rm published/META-INF/MARZVPN-.SF published/META-INF/MARZVPN-.RSA published/META-INF/MANIFEST.MF
diff -r published mine && echo "contents identical (signature aside)"
```

MARZ checked this on 2026-10-28, offline, from this bundle only: every entry of all five APKs
(arm64-v8a, armeabi-v7a, x86, x86_64, universal) is identical to the published file except the three
signature entries. `libbox.so` is byte-identical to the shipped one: arm64-v8a built offline from
this bundle exactly as in step 1; armeabi-v7a, x86 and x86_64 built with the same command, toolchain
and module versions (the module zips in this bundle hash-match `go.sum`).

Published certificate SHA-256: `34:1F:9B:40:05:4F:AF:E2:6C:E3:AF:D2:CD:63:3F:72:7D:0A:EF:10:45:D6:41:B9:6C:4C:59:99:0A:10:FF:6A`.
