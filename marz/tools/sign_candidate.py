#!/usr/bin/env python3
"""Sign already-built release APKs using the existing private MARZ key. No key creation.

Credentials are consumed only in memory and a child environment, never arguments,
stdout, Git or files. Private build logs are not copied into the release bundle.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parents[2]
BT=Path('/mnt/marz-lab/MARZ-VPN-app/toolchain/android-sdk/build-tools/37.0.0')
SIGNING=Path('/mnt/marz-lab/MARZ-VPN/private/android-release-signing')
RC5=Path('/mnt/marz-lab/MARZ-VPN-app/dist/release-1.0.0-rc6-checkout-1000008/MARZ-VPN-1.0.0-rc6-arm64-v8a.apk')  # the certificate reference: the public RC6


def checked(args,env=None):
    env=dict(os.environ if env is None else env)
    env['JAVA_HOME']='/mnt/marz-lab/MARZ-VPN-app/toolchain/jdk'
    env['PATH']=env['JAVA_HOME']+'/bin'+os.pathsep+env.get('PATH','')
    result=subprocess.run([str(x) for x in args],env=env,capture_output=True,text=True)
    if result.returncode:raise RuntimeError('release_tool_failed')
    return result.stdout


def certificate(path):
    out=checked([BT/'apksigner','verify','--print-certs',path])
    match=re.search(r'certificate SHA-256 digest: ([0-9a-fA-F]{64})',out)
    if not match:raise RuntimeError('certificate_missing')
    return match[1].lower()


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--out',required=True)
    args=parser.parse_args();out=Path(args.out).resolve()
    if not str(out).startswith('/mnt/marz-lab/') or out.exists():parser.error('new LAB output required')
    props=SIGNING/'release.properties'
    for path in (props,SIGNING/'marzvpn-release.p12'):
        info=path.stat()
        if path.is_symlink() or info.st_uid!=os.getuid() or stat.S_IMODE(info.st_mode)!=0o600:
            raise RuntimeError('unsafe_signing_permissions')
    settings={line.split('=',1)[0].strip():line.split('=',1)[1].strip()
              for line in props.read_text().splitlines() if '=' in line and not line.lstrip().startswith('#')}
    key=Path(settings['MARZ_RELEASE_KEYSTORE']).resolve()
    if key!=(SIGNING/'marzvpn-release.p12').resolve():raise RuntimeError('unexpected_signing_key')
    env=dict(os.environ,MARZ_TASK_SIGN_PASSWORD=settings['MARZ_RELEASE_KEYSTORE_PASS'],
             JAVA_HOME='/mnt/marz-lab/MARZ-VPN-app/toolchain/jdk')
    version={k:v for k,v in (line.split('=',1) for line in (ROOT/'version.properties').read_text().splitlines() if '=' in line)}
    name=version['MARZ_VERSION_NAME'];code=int(version['MARZ_VERSION_CODE']);expected=certificate(RC5)
    if code<=1000005 or not re.fullmatch(r'1\.\d+\.\d+(-rc\d+)?',name):raise RuntimeError('release_version_not_incremented')
    apks=sorted((ROOT/'app/build/outputs/apk/other/release').glob('*.apk'))
    if not apks:raise RuntimeError('no_release_apks')
    out.mkdir(mode=0o700);records=[]
    for apk in apks:
        metadata=checked([BT/'aapt2','dump','badging',apk])
        if f"name='com.marzvpn.app' versionCode='{code}' versionName='{name}'" not in metadata or 'application-debuggable' in metadata:
            raise RuntimeError('wrong_release_identity')
        checked([BT/'zipalign','-c','-p','4',apk])
        target=out/apk.name.replace('-unsigned','')
        checked([BT/'apksigner','sign','--ks',key,'--ks-key-alias',settings['MARZ_RELEASE_KEY_ALIAS'],
                 '--ks-pass','env:MARZ_TASK_SIGN_PASSWORD','--key-pass','env:MARZ_TASK_SIGN_PASSWORD',
                 '--v2-signing-enabled','true','--out',target,apk],env=env)
        if certificate(target)!=expected:raise RuntimeError('certificate_changed')
        checked([BT/'apksigner','verify','--verbose',target]);checked([BT/'zipalign','-c','-p','4',target])
        with zipfile.ZipFile(target) as z:
            dex=b''.join(z.read(n) for n in z.namelist() if n.startswith('classes') and n.endswith('.dex'))
        if b'https://app-api.marzvpn.com' not in dex or any(x in dex for x in (b'http://127.0.0.1:8787',b'lab-admin-token')):
            raise RuntimeError('wrong_release_endpoint')
        records.append(dict(file=target.name,sha256=hashlib.sha256(target.read_bytes()).hexdigest(),bytes=target.stat().st_size))
    manifest=dict(version=name,versionCode=code,package='com.marzvpn.app',certificateSha256=expected,
                  rc5CertificateMatches=True,baselineVersionCode=1000008,baselineSha256=hashlib.sha256(RC5.read_bytes()).hexdigest(),productionApi=True,debuggable=False,verified=True,
                  emailLoginPublic=True,published=False,distributionApproved=False,offlineKeyBackupsOwnerConfirmed=False,
                  sourceCommit=checked(['git','rev-parse','HEAD']).strip(),artifacts=records)
    (out/'release-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    (out/'SHA256SUMS').write_text(''.join(r['sha256']+'  '+r['file']+'\n' for r in records))
    print(json.dumps({'status':'SIGNED AND VERIFIED','version':name,'count':len(records),'output':str(out),
                      'certificate_matches_rc5':True,'published':False}))


if __name__=='__main__':
    try:main()
    except Exception as exc:
        # Exception messages/child stderr may contain secret-bearing tool context.
        reason=str(exc) if isinstance(exc,RuntimeError) and re.fullmatch('[a-z_]+',str(exc)) else 'private_detail_suppressed'
        print(json.dumps({'status':'BLOCKED','error_type':type(exc).__name__,'reason':reason}));raise SystemExit(1)
