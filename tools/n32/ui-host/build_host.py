"""Test-only local WMS host; preserve all composed root DEX/resource bytes.

The source application/manifest is replaced by an offline fixture Activity. This
does not produce a delivery APK and never changes the composed input APK.
"""
import argparse, hashlib, json, os, pathlib, re, subprocess, zipfile

R=pathlib.Path(__file__).resolve().parents[3]
SDK=pathlib.Path(os.environ['LOCALAPPDATA'])/'Android/Sdk'
JDK=pathlib.Path('E:/morphe-ai-caption-translator-next/build/isolated-toolchains/jdk-21.0.12.1+1')
BUILD=SDK/'build-tools/36.0.0'

def run(*args):
    subprocess.run([str(x) for x in args], check=True)

def sha(data): return hashlib.sha256(data).hexdigest().upper()

def main():
    ap=argparse.ArgumentParser();ap.add_argument('source');ap.add_argument('output');a=ap.parse_args()
    source=pathlib.Path(a.source).resolve();out=pathlib.Path(a.output).resolve();
    if (out/"host-signed.apk").exists():ap.error("Use a fresh host output directory; preserve every prior before/after attempt")
    out.mkdir(parents=True,exist_ok=True)
    source_digest=sha(source.read_bytes());source_length=source.stat().st_size
    classes=out/'classes';classes.mkdir(exist_ok=True);dex=out/'dex';dex.mkdir(exist_ok=True)
    android=SDK/'platforms/android-35/android.jar'
    run(JDK/'bin/javac.exe','-source','11','-target','11','-cp',android,'-d',classes,
        R/'tools/n32/ui-host/N32UiHost.java')
    run(JDK/'bin/java.exe','-cp',BUILD/'lib/d8.jar','com.android.tools.r8.D8','--min-api','28','--lib',android,
        '--output',dex,*sorted(classes.rglob('*.class')))
    base=out/'manifest.apk'
    run(BUILD/'aapt.exe','package','-f','-M',R/'tools/n32/ui-host/AndroidManifest.xml','-I',android,'-F',base)
    unsigned=out/'host-unsigned.apk';preserved=[]
    standalone=not zipfile.is_zipfile(source)
    with zipfile.ZipFile(base) as manifest,zipfile.ZipFile(unsigned,'w',zipfile.ZIP_DEFLATED,compresslevel=1) as dst:
        dst.writestr('AndroidManifest.xml',manifest.read('AndroidManifest.xml'))
        if standalone:
            data=source.read_bytes();assert data.startswith(b'dex\n'),'Standalone fallback input must be the real extension raw DEX'
            rootdex=['classes.dex'];dst.writestr('classes.dex',data);preserved.append({'entry':'classes.dex','sha256':sha(data),'bytes':len(data)})
        else:
            with zipfile.ZipFile(source) as old:
                names=old.namelist();rootdex=[n for n in names if re.fullmatch(r'classes(?:\d+)?\.dex',n)]
                for name in names:
                    if name in rootdex or name=='resources.arsc' or name.startswith(('res/','assets/')):
                        data=old.read(name);dst.writestr(name,data,compress_type=zipfile.ZIP_STORED if name=='resources.arsc' else zipfile.ZIP_DEFLATED);preserved.append({'entry':name,'sha256':sha(data),'bytes':len(data)})
        fixture=f'classes{len(rootdex)+1}.dex';dst.writestr(fixture,(dex/'classes.dex').read_bytes())
    aligned=out/'host-aligned.apk';run(BUILD/'zipalign.exe','-f','4',unsigned,aligned)
    signed=out/'host-signed.apk'
    run(JDK/'bin/java.exe','-jar',BUILD/'lib/apksigner.jar','sign','--ks',pathlib.Path(os.environ['USERPROFILE'])/'.android/debug.keystore',
        '--ks-pass','pass:android','--key-pass','pass:android','--out',signed,aligned)
    run(JDK/'bin/java.exe','-jar',BUILD/'lib/apksigner.jar','verify',signed)
    assert sha(source.read_bytes())==source_digest,'Source changed during fixture build; preserve this attempt and retry from stable final bytes'
    (out/'host-inputs.json').write_text(json.dumps({'source':str(source),'source_sha256':source_digest,
        'source_bytes':source_length,'fixture_dex':fixture,'signed_fixture':str(signed),
        'signed_fixture_sha256':sha(signed.read_bytes()),'preserved':preserved,
        'standalone_fallback':standalone,'scope':'Local SDK35 WMS fixture only. Original composed DEX/resources unchanged. Source Application/native video startup replaced; no INTERNET permission.'},indent=2),encoding='utf-8')
    print('N32_LOCAL_HOST_BUILD_PASS',signed)

if __name__=='__main__':main()
