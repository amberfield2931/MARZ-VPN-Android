#!/usr/bin/env python3
"""Build the GPL-3.0 corresponding-source bundle for one signed MARZ VPN release.

Offline: reads git, the Go module cache and the Gradle cache; no upload, no key access, no signing.
The product commit comes from the signed release manifest. Output stays private in LAB until the
owner publishes it.

    stage_gpl_source.py --artifacts <dist/release-...> --gradle-deps <deps.txt> --out <new dir>

deps.txt = `./gradlew :app:dependencies --configuration otherReleaseRuntimeClasspath` at that commit.
"""
import argparse
import gzip
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import posixpath
import re
import subprocess
import tarfile
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[2]
LAB=Path('/mnt/marz-lab/MARZ-VPN-app')
CORE=LAB/'upstream/sing-box'
CORE_COMMIT='7054cac5124465ffdb145870d7c01885566711b1'
UPSTREAM_SFA='ae93152f21457723af77d144f28c1f7be7362aec'
GOPATH=LAB/'toolchain/gopath'
MODCACHE=GOPATH/'pkg/mod'
DOWNLOAD=MODCACHE/'cache/download'
MOBILE=MODCACHE/'github.com/sagernet/gomobile@v0.1.13'
GO=LAB/'toolchain/go/bin/go'
GRADLE_CACHE=LAB/'toolchain/gradle-home/caches/modules-2/files-2.1'
# Downloaded from the projects' GitHub tags/commits (see THIRD_PARTY_SOURCES): sources of the LGPL
# Android libraries and their native parts, Chromium/BoringSSL licenses of libcronet, MIT texts.
THIRD=LAB/'toolchain/dl/third-party-src'
THIRD_PARTY_SOURCES=(
    ('sora-editor-0.23.6.tar.gz','io.github.Rosemoe.sora-editor:editor, language-treesitter 0.23.6 (LGPL-2.1)','https://github.com/Rosemoe/sora-editor/tree/0.23.6'),
    ('android-tree-sitter-v4.3.2.tar.gz','com.itsaky.androidide.treesitter:* 4.3.2 (LGPL-2.1)','https://github.com/itsaky/android-tree-sitter/tree/v4.3.2'),
    ('tree-sitter-ca7d15ad.tar.gz','tree-sitter core, submodule tree-sitter-lib of android-tree-sitter v4.3.2 (MIT)','https://github.com/tree-sitter/tree-sitter/tree/ca7d15add61bf77391d387d26441599b96c0eb9c'),
    ('tree-sitter-json-3b129203.tar.gz','tree-sitter-json grammar, submodule grammars/json of android-tree-sitter v4.3.2 (MIT)','https://github.com/tree-sitter/tree-sitter-json/tree/3b129203f4b72d532f58e72c5310c0a7db3b8e6d'),
)
# Same tags as sing-box cmd/internal/build_libbox (main variant, release).
LIBBOX_TAGS=('with_gvisor,with_quic,with_wireguard,with_utls,with_naive_outbound,with_clash_api,with_usbip,'
    'with_openvpn,with_openconnect,badlinkname,tfogo_checklinkname0,with_tailscale,ts_omit_logtail,ts_omit_ssh,'
    'ts_omit_drive,ts_omit_taildrop,ts_omit_webclient,ts_omit_doctor,ts_omit_capture,ts_omit_kube,ts_omit_aws,'
    'ts_omit_synology,ts_omit_bird')
ABIS={'arm64':'arm64-v8a','arm':'armeabi-v7a','386':'x86','amd64':'x86_64'}
# Not part of the corresponding source: the unused upstream test keystore, device-lab scripts and
# internal release/status reports (they hold no build input).
EXCLUDE=('app/release.keystore','marz/diag','marz/ui-checks','marz/RELEASE.md','marz/COMMERCIAL-LINT-RESULT.md',
    'marz/COMMERCIAL-LOCAL-STATUS.md','marz/EMAIL-LINK-PILOT.md','marz/RC5-RELEASE-NOTES.md',
    'marz/RC5-SAMSUNG-CHECKLIST.md','marz/RC5-STATUS.md','marz/RC6-CHECKOUT-UNBLOCK.md',
    'marz/RC6-COMMERCIAL-RELEASE.md','marz/RC6-SWEDEN-LOCAL-RESULTS.json','marz/RC6-SWEDEN-RELEASE.md',
    'marz/RC6-SWEDEN-SIGNED-RESULTS.json')
FORBIDDEN_NAMES=('.keystore','.jks','.p12','.pfx','.pem','.key','.env','release.properties','pilot.properties',
    'local.properties','.db','.sqlite','.sqlite3')


def sha(data):return hashlib.sha256(data).hexdigest()


def unesc(path):return re.sub(r'!([a-z])',lambda m:m.group(1).upper(),path)


def esc(path):return re.sub(r'[A-Z]',lambda m:'!'+m.group().lower(),path)


def norm(member,prefix):
    member.name=prefix+'/'+member.name;member.uid=member.gid=0;member.uname=member.gname='';member.pax_headers={}
    member.mtime=min(member.mtime,1700000000);return member


def archive_git(root,commit,target,prefix,exclude=(),overrides=None,extra=None):
    raw=subprocess.check_output(['git','-C',str(root),'archive','--format=tar',commit])
    names=[]
    with tarfile.open(fileobj=io.BytesIO(raw)) as source,gzip.GzipFile(filename='',mode='wb',fileobj=target.open('wb'),mtime=0) as gz,tarfile.open(fileobj=gz,mode='w|',format=tarfile.USTAR_FORMAT) as dest:
        for member in source:
            if any(member.name==x or member.name.startswith(x+'/') for x in exclude):continue
            if member.issym():
                resolved=posixpath.normpath(posixpath.join(posixpath.dirname(member.name),member.linkname))
                if member.linkname.startswith('/') or resolved.startswith('../') or resolved=='..':raise ValueError('unsafe_source_link')
            elif not member.isfile() and not member.isdir():raise ValueError('unexpected_source_member')
            data=(overrides or {}).get(member.name)
            if member.isfile():
                data=source.extractfile(member).read() if data is None else data
                member.size=len(data)
            names.append(member.name)
            dest.addfile(norm(member,prefix),io.BytesIO(data) if data is not None else None)
        for name,data in (extra or {}).items():
            info=tarfile.TarInfo(prefix+'/'+name);info.size=len(data);info.mode=0o644;info.mtime=1700000000
            dest.addfile(info,io.BytesIO(data));names.append(name)
    return names


def go_modules():
    """Modules linked into libbox for every shipped ABI, plus the module graph's go.mod files."""
    env=dict(os.environ,GOPATH=str(GOPATH),GOPROXY='off',GOFLAGS='-mod=mod',GOTOOLCHAIN='local',GOOS='android',CGO_ENABLED='1',
        GOCACHE=os.environ.get('GOCACHE','/tmp/marz-gpl-gocache'))
    linked={}
    # gobind's generated wrapper links the bind runtime from sing-box's module graph.
    bind=('github.com/sagernet/gomobile/bind','github.com/sagernet/gomobile/bind/seq','github.com/sagernet/gomobile/bind/java')
    for arch in ABIS:
        out=subprocess.check_output([str(GO),'list','-deps','-tags',LIBBOX_TAGS,'-f','{{with .Module}}{{.Path}} {{.Version}}{{end}}',
            './experimental/libbox',*bind],cwd=CORE,env=dict(env,GOARCH=arch),text=True)
        for line in out.split('\n'):
            parts=line.split()
            if len(parts)==2:linked.setdefault(tuple(parts),set()).add(ABIS[arch])
    # go.mod of every module the toolchain may consult while resolving the graph (small files).
    every=set()
    for mod in DOWNLOAD.rglob('*.mod'):
        rel=mod.relative_to(DOWNLOAD).parts
        if len(rel)>=3 and rel[-2]=='@v':every.add((unesc('/'.join(rel[:-2])),unesc(mod.name[:-4])))
    # gomobile and gobind are built from the gomobile module with its own dependencies.
    tools=subprocess.check_output([str(GO),'list','-deps','-f','{{with .Module}}{{.Path}} {{.Version}}{{end}}','./cmd/gomobile','./cmd/gobind'],
        cwd=MOBILE,env=dict(env,GOOS='linux',GOARCH='amd64',CGO_ENABLED='0',GOFLAGS='-mod=readonly'),text=True)
    for line in tools.split('\n'):
        parts=line.split()
        if len(parts)==2:linked.setdefault(tuple(parts),set()).add('gomobile-tool')
    return linked,every


def module_proxy(target,linked,every):
    """GOPROXY=file:// layout: .info/.mod for the whole graph, .zip for every linked module and gomobile."""
    zips=set(linked)|{('github.com/sagernet/gomobile','v0.1.13')}
    count=0
    with gzip.GzipFile(filename='',mode='wb',fileobj=target.open('wb'),mtime=0) as gz,tarfile.open(fileobj=gz,mode='w|',format=tarfile.GNU_FORMAT) as dest:
        for path,version in sorted(every|zips):
            base=DOWNLOAD/esc(path)/'@v'
            exts=('.info','.mod','.zip') if (path,version) in zips else ('.info','.mod')
            for ext in exts:
                f=base/(esc(version)+ext)
                if not f.exists():
                    if ext=='.info':continue
                    raise ValueError('module_cache_missing:'+path+'@'+version+ext)
                dest.add(f,arcname='go-proxy/'+esc(path)+'/@v/'+esc(version)+ext,filter=lambda m:norm_plain(m));count+=1
    return count


def norm_plain(member):
    member.uid=member.gid=0;member.uname=member.gname='';member.mode=0o755 if member.isdir() else 0o644;member.mtime=1700000000;return member


LICENSE_FILE=re.compile(r'(?i)^(licen[cs]e|copying|unlicense|notice)([.-].*)?$')


def classify(text):
    t=re.sub(r'\s+',' ',text.lower())
    if 'mozilla public license' in t:return 'MPL-2.0'
    if 'lesser general public license' in t:return 'LGPL-2.1' if 'version 2.1' in t else 'LGPL'
    if 'gnu general public license' in t:return 'GPL-3.0-or-later' if 'version 3' in t else 'GPL-2.0'
    if 'cc0 1.0' in t:return 'CC0-1.0+Apache-2.0' if 'apache license' in t else 'CC0-1.0'
    if 'apache license' in t and 'version 2.0' in t:return 'Apache-2.0'
    if 'permission is hereby granted, free of charge' in t:return 'MIT'
    if 'redistribution and use in source and binary forms' in t:
        return 'BSD-3-Clause' if ('neither the name' in t or 'names of its' in t or 'name of the copyright' in t) else 'BSD-2-Clause'
    if 'permission to use, copy, modify, and/or distribute' in t or 'permission to use, copy, modify, and distribute' in t or 'isc license' in t:return 'ISC'
    if 'this is free and unencumbered software' in t:return 'Unlicense'
    return 'UNCLASSIFIED'


def go_licenses(linked):
    rows=[];texts=[]
    for (path,version),abis in sorted(linked.items()):
        d=MODCACHE/(esc(path)+'@'+esc(version))
        files=sorted(p for p in d.iterdir() if p.is_file() and LICENSE_FILE.match(p.name))
        if (d/'LICENSES').is_dir():files+=sorted((d/'LICENSES').iterdir())
        body='\n'.join(p.read_text(errors='replace') for p in files)
        kinds=sorted({classify(p.read_text(errors='replace')) for p in files if not p.name.upper().startswith('NOTICE')})
        rows.append(dict(module=path,version=version,license='+'.join(kinds) if kinds else 'NO_LICENSE_FILE',abis=sorted(abis)))
        texts.append('='*78+'\n'+path+' '+version+'\n'+'='*78+'\n'+body.strip()+'\n')
    return rows,texts


def gradle_licenses(deps_txt):
    coords=set()
    for line in Path(deps_txt).read_text().splitlines():
        m=re.search(r'[+\\]--- ([^\s:]+):([^\s:]+):(\S+)(?: -> (\S+))?',line)
        if m and not m.group(1).startswith('project'):
            version=(m.group(4) or m.group(3))
            if not version.startswith('{'):coords.add((m.group(1),m.group(2),version))
    # Artifacts whose POM names no license: license read from the upstream project (checked by hand).
    known={('com.github.jeziellago','Markwon'):'Apache-2.0',('com.github.jeziellago','compose-markdown'):'MIT',
        ('com.github.topjohnwu.libsu','core'):'Apache-2.0',('com.github.topjohnwu.libsu','service'):'Apache-2.0',
        ('androidx.compose','compose-bom'):'BOM (no code)'}
    rows=[]
    for g,a,v in sorted(coords):
        name=pom_license(g,a,v)
        rows.append(dict(group=g,artifact=a,version=v,license=normalize(name) if name else known.get((g,a),'UNKNOWN'),
            source='pom' if name else ('upstream project' if (g,a) in known else 'none')))
    return rows


def android_texts(rows):
    """MIT/BSD texts: from the artifact itself, else the project's LICENSE (THIRD/maven-licenses)."""
    fetched={'com.github.jeziellago:compose-markdown':'jeziellago_compose-markdown','dev.rikka.shizuku':'RikkaApps_Shizuku-API',
        'org.kodein.emoji':'kosi-libs_Emoji.kt','io.github.sagernet':'SagerNet_libghostty-android'}
    out=[];seen=set()
    for r in rows:
        if r['license'] not in ('MIT','BSD-2-Clause','BSD-3-Clause'):continue
        key=r['group']+':'+r['artifact'];text=None
        for f in GRADLE_CACHE.glob(f"{r['group']}/{r['artifact']}/{r['version']}/*/*.[ja][ar]r"):
            if f.name.endswith('-sources.jar'):continue
            with zipfile.ZipFile(f) as z:
                names=[n for n in z.namelist() if re.search(r'(?i)(^|/)licen[cs]e[^/]*$',n)]
                if names:text=z.read(names[0]).decode(errors='replace');break
        if text is None:
            name=fetched.get(key) or fetched.get(r['group'])
            if name:text=(THIRD/'maven-licenses'/(name+'.LICENSE')).read_text()
        if text is None:raise ValueError('license_text_missing:'+key)
        if text in seen:out.append(f'--- {key}:{r["version"]}: same text as above ---\n');continue
        seen.add(text);out.append(f'--- {key}:{r["version"]} ({r["license"]}) ---\n{text.strip()}\n')
    out.append('--- ghostty (bundled in libghostty_android.so) ---\n'+(THIRD/'maven-licenses/ghostty-org_ghostty.LICENSE').read_text())
    return '\n'.join(out)


def pom_license(g,a,v,depth=0):
    poms=list(GRADLE_CACHE.glob(f'{g}/{a}/{v}/*/{a}-{v}.pom'))
    if not poms:return None
    text=re.sub(r'xmlns="[^"]+"','',poms[0].read_text(errors='replace'),count=1)
    try:root=ET.fromstring(text.encode())
    except ET.ParseError:return None
    names=[(x.findtext('name') or '').strip()+' '+(x.findtext('url') or '').strip() for x in root.findall('./licenses/license')]
    if names:return '; '.join(names)
    parent=root.find('parent')
    if parent is not None and depth<4:return pom_license(parent.findtext('groupId'),parent.findtext('artifactId'),parent.findtext('version'),depth+1)
    return None


def normalize(name):
    n=name.lower()
    if 'apache' in n:return 'Apache-2.0'
    if 'lgpl' in n or 'lesser' in n:return 'LGPL-2.1'
    if 'mit' in n:return 'MIT'
    if 'bsd-3' in n or '3-clause' in n:return 'BSD-3-Clause'
    if 'bsd-2' in n or '2-clause' in n:return 'BSD-2-Clause'
    return name


COMPATIBLE={'CC0-1.0','CC0-1.0+Apache-2.0','GPL-3.0-or-later','Apache-2.0','MIT','BSD-2-Clause','BSD-3-Clause','ISC','Unlicense','MPL-2.0','LGPL-2.1','BOM (no code)'}


def index_html(out,version,code,commit,cert):
    import html
    rows=''.join(f'<tr><td><a href="{html.escape(p.name)}">{html.escape(p.name)}</a></td><td>{p.stat().st_size:,}</td>'
        f'<td><code>{sha(p.read_bytes())}</code></td></tr>\n' for p in sorted(out.iterdir()) if p.is_file() and p.name not in ('index.html','SHA256SUMS'))
    return f'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MARZ VPN Android source code</title>
<style>body{{font:16px/1.5 system-ui,sans-serif;max-width:60rem;margin:2rem auto;padding:0 16px;color:#111;background:#fff}}
table{{border-collapse:collapse;width:100%;font-size:14px}}td{{border-top:1px solid #ddd;padding:.4rem;vertical-align:top}}
code{{word-break:break-all;font-size:12px}}</style></head><body>
<h1>MARZ VPN for Android — source code</h1>
<p>Corresponding source of <b>MARZ VPN {html.escape(version)} ({code})</b>, package <code>com.marzvpn.app</code>,
MARZ commit <code>{commit}</code>. Signing certificate SHA-256 <code>{cert}</code>.</p>
<p>MARZ VPN is a modified version of <a href="https://github.com/SagerNet/sing-box-for-android">sing-box-for-android</a>
(Copyright &copy; 2022 nekohasekai) and uses <a href="https://github.com/SagerNet/sing-box">sing-box</a>, licensed under the
<a href="COPYING-GPL-3.txt">GNU General Public License v3.0 or later</a>. It is not affiliated with or endorsed by SagerNet.
This program comes with ABSOLUTELY NO WARRANTY. Changes: <a href="MODIFICATIONS.md">MODIFICATIONS.md</a>;
how to build: <a href="BUILD.md">BUILD.md</a>; third-party licenses: <a href="THIRD-PARTY-NOTICES.txt">THIRD-PARTY-NOTICES.txt</a>.</p>
<p>Исходный код MARZ VPN для Android (GPL-3.0). · کد منبع MARZ VPN برای اندروید (GPL-3.0). · MARZ VPN Android 源代码（GPL-3.0）。</p>
<table><tr><td><b>File</b></td><td><b>Bytes</b></td><td><b>SHA-256</b></td></tr>
{rows}</table>
<p><a href="SHA256SUMS">SHA256SUMS</a> · Questions: <a href="https://marzvpn.com/contact">marzvpn.com/contact</a></p>
</body></html>
'''


def main():
    p=argparse.ArgumentParser();p.add_argument('--artifacts',required=True);p.add_argument('--gradle-deps',required=True)
    p.add_argument('--out',required=True);a=p.parse_args()
    dist=Path(a.artifacts).resolve();out=Path(a.out).resolve()
    if out.exists() or not str(out).startswith('/mnt/marz-lab/'):raise ValueError('fresh_private_lab_output_required')
    manifest=json.loads((dist/'release-manifest.json').read_text())
    spec=importlib.util.spec_from_file_location('sign_helpers',ROOT/'marz/tools/sign_candidate.py')
    sign=importlib.util.module_from_spec(spec);spec.loader.exec_module(sign)
    for item in manifest['artifacts']:
        path=dist/item['file']
        if sha(path.read_bytes())!=item['sha256'] or sign.certificate(path)!=manifest['certificateSha256']:raise ValueError('artifact_integrity')
    commit=manifest['sourceCommit'];version=manifest['version'];code=manifest['versionCode']
    if subprocess.check_output(['git','-C',str(CORE),'rev-parse','HEAD'],text=True).strip()!=CORE_COMMIT:raise ValueError('core_source_drift')
    if set(subprocess.check_output(['git','-C',str(CORE),'diff','--name-only'],text=True).split())-{'go.sum'}:raise ValueError('unreviewed_core_changes')
    out.mkdir(mode=0o700)
    stem=f'MARZ-VPN-{version}-{code}'
    notice=(ROOT/'marz/gpl/MODIFICATIONS.md').read_text().replace('@COMMIT@',commit).replace('@VERSION@',f'{version} ({code})').encode()
    app_names=archive_git(ROOT,commit,out/f'{stem}-source.tar.gz','MARZ-VPN',exclude=EXCLUDE,
        extra={'MODIFICATIONS.md':notice,'BUILD-RELEASE.md':(ROOT/'marz/gpl/BUILD-RELEASE.md').read_bytes()})
    (out/'marz-changes-vs-upstream.patch').write_bytes(subprocess.check_output(['git','-C',str(ROOT),'diff','--no-color',
        UPSTREAM_SFA,commit,'--','.']+[':!'+x for x in EXCLUDE]))
    bad=[n for n in app_names if n.lower().endswith(FORBIDDEN_NAMES)]
    if bad or 'LICENSE' not in app_names:raise ValueError('source_archive_scope:'+','.join(bad))
    archive_git(CORE,CORE_COMMIT,out/'sing-box-v1.14.3-source.tar.gz','sing-box',overrides={'go.sum':(CORE/'go.sum').read_bytes()})
    with gzip.GzipFile(filename='',mode='wb',fileobj=(out/'gomobile-v0.1.13-source.tar.gz').open('wb'),mtime=0) as gz,tarfile.open(fileobj=gz,mode='w|') as tar:
        tar.add(MOBILE,arcname='gomobile',filter=lambda m:norm_plain(m) if m.isfile() or m.isdir() else None)
    linked,every=go_modules()
    zips=len(set(linked)|{('github.com/sagernet/gomobile','v0.1.13')})
    proxied=module_proxy(out/'go-modules-sing-box-v1.14.3.tar.gz',linked,every)
    with gzip.GzipFile(filename='',mode='wb',fileobj=(out/'third-party-android-sources.tar.gz').open('wb'),mtime=0) as gz,tarfile.open(fileobj=gz,mode='w|') as tar:
        readme=''.join(f'{name}\n  {what}\n  {url}\n\n' for name,what,url in THIRD_PARTY_SOURCES).encode()
        info=tarfile.TarInfo('third-party/SOURCES.txt');info.size=len(readme);info.mode=0o644;info.mtime=1700000000
        tar.addfile(info,io.BytesIO(readme))
        for name,_,_ in THIRD_PARTY_SOURCES:tar.add(THIRD/name,arcname='third-party/'+name,filter=norm_plain)
    go_rows,go_texts=go_licenses(linked)
    gradle_rows=gradle_licenses(a.gradle_deps)
    with zipfile.ZipFile(dist/'MARZ-VPN-1.0.0-rc6-universal.apk') as apk:
        native=sorted(n for n in apk.namelist() if n.startswith('lib/') and n.endswith('.so'))
        apk_notice=apk.read('META-INF/NOTICE.md').decode(errors='replace') if 'META-INF/NOTICE.md' in apk.namelist() else ''
    (out/'LICENSE-ANDROID.txt').write_bytes((ROOT/'LICENSE').read_bytes())
    (out/'LICENSE-SING-BOX.txt').write_bytes((CORE/'LICENSE').read_bytes())
    (out/'LICENSE-GOMOBILE.txt').write_bytes((MOBILE/'LICENSE').read_bytes())
    (out/'COPYING-GPL-3.txt').write_bytes(Path('/usr/share/common-licenses/GPL-3').read_bytes())
    (out/'COPYING-APACHE-2.0.txt').write_bytes(Path('/usr/share/common-licenses/Apache-2.0').read_bytes())
    (out/'COPYING-LGPL-2.1.txt').write_bytes(Path('/usr/share/common-licenses/LGPL-2.1').read_bytes())
    (out/'COPYING-MPL-2.0.txt').write_bytes(Path('/usr/share/common-licenses/MPL-2.0').read_bytes())
    (out/'MODIFICATIONS.md').write_bytes(notice)
    (out/'BUILD.md').write_bytes((ROOT/'marz/gpl/BUILD-RELEASE.md').read_bytes())
    (out/'THIRD-PARTY-NOTICES.txt').write_text('MARZ VPN '+version+' ('+str(code)+') third-party notices\n\n'
        'Go modules linked into libbox.so (full license texts, from the module sources):\n\n'+'\n'.join(go_texts)+
        '\n'+'='*78+'\nAndroid libraries in the APK (Maven coordinates and licenses; full texts: COPYING-APACHE-2.0.txt,\n'
        'COPYING-LGPL-2.1.txt, COPYING-MPL-2.0.txt, COPYING-GPL-3.txt; MIT/BSD texts and copyright lines are in each project)\n'+'='*78+'\n'+
        ''.join(f"{r['group']}:{r['artifact']}:{r['version']}  {r['license']}\n" for r in gradle_rows)+
        '\n'+'='*78+'\nMIT / BSD license texts of Android libraries\n'+'='*78+'\n'+android_texts(gradle_rows)+
        '\n'+'='*78+'\nlibcronet.a (github.com/sagernet/cronet-go/lib/android_*): Chromium network stack built by SagerNet from\n'
        'naiveproxy https://github.com/SagerNet/naiveproxy/tree/72a06c9fca0e2d228588c7f3074bf7efff3ff686 . The\n'
        'complete list of Chromium third-party licenses is in that tree (src/third_party/*/LICENSE, credits).\n'+'='*78+'\n'+
        ''.join(f'--- {n} ---\n'+(THIRD/n).read_text()+'\n' for n in ('naiveproxy-LICENSE.txt','chromium-LICENSE.txt','boringssl-LICENSE.txt'))+
        '\n'+'='*78+'\nMETA-INF/NOTICE.md shipped in the APK\n'+'='*78+'\n'+apk_notice)
    unknown=[r for r in go_rows if not set(r['license'].split('+'))<=COMPATIBLE|{'CC0-1.0'}]+[r for r in gradle_rows if r['license'] not in COMPATIBLE]
    inventory=dict(product=dict(package=manifest['package'],version=version,versionCode=code,androidCommit=commit),
        goModules=go_rows,gradleArtifacts=gradle_rows,nativeLibraries=native,unclassified=unknown)
    (out/'licenses.json').write_text(json.dumps(inventory,indent=1)+'\n')
    (out/'index.html').write_text(index_html(out,version,code,commit,manifest['certificateSha256']))
    provenance=dict(status='LOCAL_STAGING_NOT_PUBLISHED',package=manifest['package'],version=version,versionCode=code,
        androidCommit=commit,singBoxTag='v1.14.3',singBoxCommit=CORE_COMMIT,coreLocalChange='go.sum entries only',
        coreGoSumSha256=sha((CORE/'go.sum').read_bytes()),gomobile='v0.1.13',go='1.26.8',ndk='28.0.13004108',
        cronet=dict(module='github.com/sagernet/cronet-go',prebuilt='lib/android_* libcronet.a built by SagerNet from cronet-go 049f2909701ca088c9569f6b303bba5376a2b2ff',
            naiveproxy='https://github.com/SagerNet/naiveproxy/tree/72a06c9fca0e2d228588c7f3074bf7efff3ff686'),
        signedArtifacts={i['file']:i['sha256'] for i in manifest['artifacts']},certificateSha256=manifest['certificateSha256'],
        excludedFromAppArchive=list(EXCLUDE),goModuleZips=zips,goProxyFiles=proxied,
        thirdPartySources=[dict(file=n,what=w,url=u,sha256=sha((THIRD/n).read_bytes())) for n,w,u in THIRD_PARTY_SOURCES],
        reproduced=dict(date='2026-10-28',libbox='byte-identical, all 4 ABIs (arm64-v8a offline from this bundle)',
            apk='all 5 APKs identical to the published ones except the 3 signature entries'),releaseCredentialsIncluded=False,
        files={p.name:sha(p.read_bytes()) for p in sorted(out.iterdir()) if p.is_file()})
    (out/'source-provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
    (out/'SHA256SUMS').write_text(''.join(sha(p.read_bytes())+'  '+p.name+'\n' for p in sorted(out.iterdir()) if p.is_file() and p.name!='SHA256SUMS'))
    print(json.dumps(dict(status='STAGED',out=str(out),commit=commit,goModules=len(linked),proxyFiles=proxied,
        gradleArtifacts=len(gradle_rows),unclassified=len(unknown),published=False)))


if __name__=='__main__':main()
