from pathlib import Path
import json,hashlib,subprocess,shutil
R=Path(__file__).resolve().parents[2];V=R/'.verification/n31-settings-crash'
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
tracked=subprocess.check_output(['git','ls-files','-z'],cwd=R).decode().split('\0')
paths=[p for p in (R/'build/local-test').glob('*n31*') if p.is_file()]
paths += [R/'build/n31-two-patches-composition-final/YouTube-21.16.256-本地测试包-n31-two-patches-unsigned.apk',R/'patches-1.45.0.mpp']
assert not (V/'input-manifest.json').exists()
(V/'input-manifest.json').write_text(json.dumps({'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=R).decode().strip(),'tracked':{p:sha(R/p) for p in tracked if p and (R/p).is_file()},'history':[{'path':p.relative_to(R).as_posix(),'bytes':p.stat().st_size,'sha256':sha(p)} for p in paths],'installed_before_sha256':sha(V/'installed-before.apk')},indent=2),encoding='utf-8')
shutil.copyfile(R/'patches/src/main/kotlin/app/yydarlinker/patches/deepseekcaptions/CaptionSettingsBindingPatch.kt',V/'CaptionSettingsBindingPatch.kt.before')
print('N31_SETTINGS_CRASH_BASELINE_CAPTURED')