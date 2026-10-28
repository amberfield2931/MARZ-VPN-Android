#!/usr/bin/env python3
"""Fresh official-package emulator upgrade; same-key TEST ONLY probe, no production calls."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parents[2]
SDK=Path('/mnt/marz-lab/MARZ-VPN-app/toolchain/android-sdk')
BT=SDK/'build-tools/37.0.0';JDK=Path('/mnt/marz-lab/MARZ-VPN-app/toolchain/jdk')


def main():
    p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--out',required=True);p.add_argument('--candidate',required=True)
    p.add_argument('--baseline',help='Official archived universal APK, defaults to RC5')
    p.add_argument('--baseline-code',type=int,default=1000005)
    args=p.parse_args()
    if not re.fullmatch(r'emulator-\d+',args.serial):p.error('emulator only')
    out=Path(args.out).resolve()
    if out.exists() or not str(out).startswith('/mnt/marz-lab/'):p.error('new LAB directory required')
    out.mkdir(mode=0o700)
    spec=importlib.util.spec_from_file_location('release_sign',ROOT/'marz/tools/sign_candidate.py')
    sign=importlib.util.module_from_spec(spec);spec.loader.exec_module(sign)
    def adb(*parts):return sign.checked([SDK/'platform-tools/adb','-s',args.serial,*parts])
    if 'package:com.marzvpn.app' in adb('shell','pm','list','packages','com.marzvpn.app').splitlines():raise RuntimeError('target_not_fresh')
    adb('shell','am','force-stop','com.marzvpn.app.debug');adb('shell','svc','wifi','disable');adb('shell','svc','data','disable')
    classes=out/'classes';classes.mkdir();dex=out/'dex';dex.mkdir();jar=SDK/'platforms/android-37.1/android.jar'
    sign.checked([JDK/'bin/javac','-source','8','-target','8','-bootclasspath',jar,'-d',classes,ROOT/'marz/ui-checks/UpgradeProbe.java'])
    sign.checked([BT/'d8','--lib',jar,'--min-api','24','--output',dex,*classes.glob('**/*.class')])
    unsigned=out/'probe-unsigned.apk';aligned=out/'probe-aligned.apk';probe=out/'probe-test-only.apk'
    sign.checked([BT/'aapt2','link','-I',jar,'--manifest',ROOT/'marz/ui-checks/UpgradeProbe.xml','-o',unsigned])
    with zipfile.ZipFile(unsigned,'a') as z:z.write(dex/'classes.dex','classes.dex')
    sign.checked([BT/'zipalign','4',unsigned,aligned])
    settings={k:v for k,v in (line.split('=',1) for line in (sign.SIGNING/'release.properties').read_text().splitlines() if '=' in line)}
    env=dict(os.environ,MARZ_TASK_SIGN_PASSWORD=settings['MARZ_RELEASE_KEYSTORE_PASS'])
    sign.checked([BT/'apksigner','sign','--ks',settings['MARZ_RELEASE_KEYSTORE'],'--ks-key-alias',settings['MARZ_RELEASE_KEY_ALIAS'],
                  '--ks-pass','env:MARZ_TASK_SIGN_PASSWORD','--key-pass','env:MARZ_TASK_SIGN_PASSWORD','--out',probe,aligned],env=env)
    baseline=Path(args.baseline).resolve() if args.baseline else sign.RC5.parent/'MARZ-VPN-1.0.0-rc5-universal.apk';candidate=Path(args.candidate).resolve()
    if args.baseline and not str(baseline).startswith('/mnt/marz-lab/MARZ-VPN-app/dist/'):
        raise RuntimeError('official_archived_baseline_required')
    if sign.certificate(probe)!=sign.certificate(baseline) or sign.certificate(candidate)!=sign.certificate(baseline):raise RuntimeError('certificate_mismatch')
    def code(apk):
        match=re.search(r"versionCode='(\d+)'",sign.checked([BT/'aapt2','dump','badging',apk]))
        if not match:raise RuntimeError('version_missing')
        return int(match[1])
    before_code,after_code=code(baseline),code(candidate)
    if before_code!=args.baseline_code or after_code<=before_code:raise RuntimeError('wrong_upgrade_direction')
    adb('install',baseline);adb('install','-t',probe)
    versions=['-e','baseline',str(before_code),'-e','candidate',str(after_code)]
    seed=adb('shell','am','instrument','-w','-e','phase','seed',*versions,'com.marzvpn.upgradeprobe/com.marzvpn.upgradeprobe.UpgradeProbe')
    if 'result=PASS' not in seed and 'result: PASS' not in seed:raise RuntimeError('seed_failed')
    adb('install','-r',candidate)
    verify=adb('shell','am','instrument','-w','-e','phase','verify',*versions,'com.marzvpn.upgradeprobe/com.marzvpn.upgradeprobe.UpgradeProbe')
    if 'result=PASS' not in verify and 'result: PASS' not in verify:raise RuntimeError('upgrade_probe_failed')
    adb('shell','am','start','-n','com.marzvpn.app/com.marzvpn.app.MarzActivity')
    after_start=adb('shell','am','instrument','-w','-e','phase','verify',*versions,'com.marzvpn.upgradeprobe/com.marzvpn.upgradeprobe.UpgradeProbe')
    if 'result=PASS' not in after_start and 'result: PASS' not in after_start:raise RuntimeError('post_startup_probe_failed')
    result=dict(status='PASS',package='com.marzvpn.app',baselineVersionCode=before_code,candidateVersionCode=after_code,postStartup=True,certificate_same=True,uid_retained=True,
                encrypted_synthetic_session_retained=True,account_cache_retained=True,device_key_signature_valid=True,
                preferences_and_server_choice_retained=True,offline=True,real_accounts=False,physical_device=False)
    (out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
    adb('uninstall','com.marzvpn.upgradeprobe')


if __name__=='__main__':
    try:main()
    except Exception as failure:
        reason=str(failure) if isinstance(failure,RuntimeError) and re.fullmatch('[a-z_]+',str(failure)) else type(failure).__name__
        print(json.dumps({'status':'BLOCKED','reason':reason}));raise SystemExit(1)
