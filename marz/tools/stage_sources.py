#!/usr/bin/env python3
"""Prepare corresponding-source/notice staging and verify signed artifacts locally.

No upload, key access, signing or network. Product source commit comes from the
signed manifest, not a later documentation HEAD. All output stays private in LAB.
"""
import argparse
import gzip
import hashlib
import importlib.util
import io
import json
import posixpath
from pathlib import Path
import subprocess
import tarfile
import zipfile

ROOT=Path(__file__).resolve().parents[2]
CORE=Path('/mnt/marz-lab/MARZ-VPN-app/upstream/sing-box')
MOBILE=Path('/mnt/marz-lab/MARZ-VPN-app/toolchain/gopath/pkg/mod/github.com/sagernet/gomobile@v0.1.13')


def sha(data):return hashlib.sha256(data).hexdigest()


def archive_git(root,commit,target,prefix,exclude=(),overrides=None):
    raw=subprocess.check_output(['git','-C',str(root),'archive','--format=tar',commit])
    with tarfile.open(fileobj=io.BytesIO(raw)) as source,gzip.GzipFile(filename='',mode='wb',fileobj=target.open('wb'),mtime=0) as gz,tarfile.open(fileobj=gz,mode='w|') as dest:
        for member in source:
            if any(member.name==x or member.name.startswith(x+'/') for x in exclude):continue
            if member.issym():
                resolved=posixpath.normpath(posixpath.join(posixpath.dirname(member.name),member.linkname))
                if member.linkname.startswith('/') or resolved.startswith('../') or resolved=='..':
                    raise ValueError('unsafe_source_link')
            elif not member.isfile() and not member.isdir():raise ValueError('unexpected_source_link')
            data=(overrides or {}).get(member.name)
            if member.isfile():
                data=source.extractfile(member).read() if data is None else data
                member.size=len(data)
            member.name=prefix+'/'+member.name;member.uid=member.gid=0;member.uname=member.gname='';member.pax_headers={}
            dest.addfile(member,io.BytesIO(data) if data is not None else None)


def main():
    p=argparse.ArgumentParser();p.add_argument('--artifacts',required=True);p.add_argument('--out',required=True);a=p.parse_args()
    dist=Path(a.artifacts).resolve();out=Path(a.out).resolve()
    if out.exists() or not str(out).startswith('/mnt/marz-lab/'):raise ValueError('fresh_private_lab_output_required')
    manifest=json.loads((dist/'release-manifest.json').read_text())
    if manifest['published'] or manifest['distributionApproved']:raise ValueError('publication_not_authorized')
    spec=importlib.util.spec_from_file_location('sign_helpers',ROOT/'marz/tools/sign_candidate.py')
    sign=importlib.util.module_from_spec(spec);spec.loader.exec_module(sign)
    out.mkdir(mode=0o700)
    rc5=sign.RC5.parent/'MARZ-VPN-1.0.0-rc5-universal.apk'
    baseline_permissions=sign.checked([sign.BT/'aapt2','dump','permissions',rc5])
    with zipfile.ZipFile(rc5) as old:
        native_before={n:sha(old.read(n)) for n in old.namelist() if n.startswith('lib/') and n.endswith('.so')}
    for item in manifest['artifacts']:
        path=dist/item['file']
        if sha(path.read_bytes())!=item['sha256'] or sign.certificate(path)!=manifest['certificateSha256']:
            raise ValueError('artifact_integrity')
        permissions=sign.checked([sign.BT/'aapt2','dump','permissions',path])
        if permissions!=baseline_permissions:raise ValueError('permission_drift')
        for forbidden in ('android.permission.CAMERA','android.permission.QUERY_ALL_PACKAGES','android.permission.ACCESS_FINE_LOCATION','android.permission.ACCESS_COARSE_LOCATION'):
            if forbidden in permissions:raise ValueError('excess_permission')
        with zipfile.ZipFile(path) as apk:
            natives={n:sha(apk.read(n)) for n in apk.namelist() if n.startswith('lib/') and n.endswith('.so')}
        if not natives or any(native_before.get(n)!=s for n,s in natives.items()):raise ValueError('native_engine_drift')
    commit=manifest['sourceCommit'];core_commit=subprocess.check_output(['git','-C',str(CORE),'rev-parse','HEAD'],text=True).strip()
    if core_commit!='7054cac5124465ffdb145870d7c01885566711b1':raise ValueError('core_source_drift')
    changes=subprocess.check_output(['git','-C',str(CORE),'diff','--name-only'],text=True).splitlines()
    if set(changes)-{'go.sum'}:raise ValueError('unreviewed_core_changes')
    archive_git(ROOT,commit,out/'MARZ-VPN-1.0.0-rc6-source.tar.gz','MARZ-VPN',
        exclude=('app/release.keystore','marz/diag','marz/ui-checks'))
    archive_git(CORE,core_commit,out/'sing-box-v1.14.3-source.tar.gz','sing-box',overrides={'go.sum':(CORE/'go.sum').read_bytes()})
    with tarfile.open(out/'gomobile-v0.1.13-source.tar.gz','w:gz') as tar:
        tar.add(MOBILE,arcname='gomobile',filter=lambda m:m if m.isfile() or m.isdir() else None)
    with tarfile.open(out/'MARZ-VPN-1.0.0-rc6-source.tar.gz') as tar:
        names=tar.getnames()
        if not any(n.endswith('/LICENSE') for n in names) or any(n.endswith(('.keystore','.p12','.env','release.properties','.db','.sqlite3')) for n in names):
            raise ValueError('source_archive_scope')
    (out/'LICENSE-ANDROID.txt').write_bytes((ROOT/'LICENSE').read_bytes())
    (out/'LICENSE-SING-BOX.txt').write_bytes((CORE/'LICENSE').read_bytes())
    (out/'LICENSE-GOMOBILE.txt').write_bytes((MOBILE/'LICENSE').read_bytes())
    (out/'COPYING-GPL-3.txt').write_bytes(Path('/usr/share/common-licenses/GPL-3').read_bytes())
    (out/'NOTICE.txt').write_text('MARZ VPN is a modified sing-box-for-android application.\n'
        'Original SagerNet notices are retained in the source and LICENSE files.\n'
        'MARZ VPN is not affiliated with or endorsed by SagerNet.\n'
        'Android/core sources are GPL-3.0-or-later; gomobile and vendored dependency notices remain in their source trees.\n'
        'Native AAR inputs were reused unchanged from RC5, not rebuilt for RC6.\n'
        'The source provenance lists exact commits and AAR hashes.\n'
        'No production credential or signing key is part of this staging folder.\n')
    (out/'BUILD.md').write_bytes((ROOT/'marz/BUILD.md').read_bytes())
    provenance=dict(status='LOCAL_SOURCE_STAGING_NOT_PUBLISHED',androidCommit=commit,singBoxCommit=core_commit,
        coreGoSumLocalAdditionsOnly=True,coreGoSumSha256=sha((CORE/'go.sum').read_bytes()),gomobile='v0.1.13',
        artifactCertificateMatchesRC5=True,nativeLibrariesByteEqualRC5=True,permissionsEqualRC5=True,
        nativeEngineRebuilt=False,fromScratchReproducibilityNotRetested=True,
        existingAarInputs={p.name:sha(p.read_bytes()) for p in (ROOT/'app/libs').glob('*.aar')},
        sourceArchiveExcludesUpstreamKeystore=True,releaseCredentialsIncluded=False,
        sourceSha256={p.name:sha(p.read_bytes()) for p in out.glob('*') if p.is_file()})
    (out/'source-provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
    (out/'SHA256SUMS').write_text(''.join(sha(p.read_bytes())+'  '+p.name+'\n' for p in sorted(out.iterdir()) if p.is_file()))
    print(json.dumps({'status':'PASS','source_out':str(out),'product_commit':commit,'native_equal_rc5':True,'permissions_equal_rc5':True,'published':False}))


if __name__=='__main__':main()
