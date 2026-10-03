from pathlib import Path
import json,hashlib,zipfile,sys
R=Path(__file__).resolve().parents[2];V=R/'.verification/n31-settings-crash'
name='本地测试包-n31-settings-crash-fixed'
mpp=R/f'build/local-test/patches-1.3.5-{name}.mpp';mpe=R/f'build/local-test/extension-1.3.5-{name}.mpe'
apk=R/f'build/n31-settings-crash-fixed-composition-final/YouTube-21.16.256-{name}-unsigned.apk'
source=R/'tools/n31/verify_host_resources.py';code=source.read_text(encoding='utf-8').replace('n31-composition-final','n31-settings-crash-fixed-composition-final').replace('{name}-n31.mpp','{name}-n31-settings-crash-fixed.mpp').replace('{name}-n31.mpe','{name}-n31-settings-crash-fixed.mpe').replace('{name}-n31-unsigned.apk','{name}-n31-settings-crash-fixed-unsigned.apk').replace("records = root / '.verification/n31/delivery-records'","records = root / '.verification/n31-settings-crash'")
exec(compile(code,str(source),'exec'),{'__name__':'__main__','__file__':str(source)})
source=R/'.github/scripts/verify_bundle.py';code=source.read_text(encoding='utf-8').replace('p=Path(f"patches/build/libs/patches-{v}.mpp")','p=Path('+repr(str(mpp))+')');sys.argv=[str(source),'1.3.5'];exec(compile(code,str(source),'exec'),{'__name__':'__main__','__file__':str(source)})
with zipfile.ZipFile(mpp) as z:
    assert b'Add Simplified Chinese to auto-translate' not in z.read('classes.dex')
    assert b'simplifiedInstalled' not in z.read('extensions/extension.mpe')
    assert z.read('extensions/extension.mpe')==mpe.read_bytes()
listing=json.loads((R/'patches-list.json').read_text(encoding='utf-8'));assert {p['name'] for p in listing['patches']}=={'AI caption translator','Remember caption selection'} and len(listing['patches'])==2
(V/'public-roots-check.json').write_text(json.dumps({'public_names':[p['name'] for p in listing['patches']],'metadata_generated_from_bundle':True,'obsolete_root_dex_label_absent':True,'obsolete_runtime_flag_absent':True,'embedded_extension_exact':True},indent=2),encoding='utf-8')
print('TWO_PUBLIC_PATCHES_VALIDATED')