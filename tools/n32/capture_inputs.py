"""Capture the original Git tree and protect historical inputs without copying in-flight edits."""
from pathlib import Path
import hashlib,json,subprocess
from concurrent.futures import ThreadPoolExecutor
R=Path(__file__).resolve().parents[2];V=R/'.verification/n32'
V.mkdir(exist_ok=True);out=V/'input-manifest.json'
if out.exists():raise SystemExit('N32 original manifest already exists')
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=R).decode().strip()
entries=[item.split('\t',1) for item in subprocess.check_output(['git','ls-tree','-r','-z',head],cwd=R).decode().split('\0') if item]
objects=[(metadata.split()[2],path) for metadata,path in entries]
batch=subprocess.check_output(['git','cat-file','--batch'],input=('\n'.join(blob for blob,path in objects)+'\n').encode(),cwd=R)
files={};original={};offset=0
for blob,path in objects:
    end=batch.index(b'\n',offset);size=int(batch[offset:end].split()[-1]);start=end+1
    content=batch[start:start+size];offset=start+size+1
    files[path]=hashlib.sha256(content).hexdigest()
    if Path(path).name in ['CaptionUiLocale.java','CaptionLanguagesPreference.java','CaptionSettingsBindingPatch.kt','N31RuntimeUiTest.java','ProfileUiRegressionTest.java']:original[path]=content
history={row['path']:row for row in json.loads((R/'.verification/n31/input-manifest.json').read_text(encoding='utf-8'))['history']}
extra_files={}
def protect(p):
    path=p.relative_to(R).as_posix()
    if path not in history and '-n32' not in p.name and '/n32-composition' not in path:extra_files[path]=p
print('Original Git tree captured',len(files),flush=True)
folders=['build/local-test','.verification/n31/delivery-records','.verification/n31/full-final-04','.verification/n31/special-final','.verification/n31/custom-host-final','.verification/n32-device-review']
for folder in folders:
    for p in (R/folder).rglob('*'):
        if p.is_file():protect(p)
for folder in ['.verification/n31-settings-crash','.verification/n31-two-patches']:
    for p in (R/folder).iterdir():
        if p.is_file():protect(p)
for folder in (R/'build').glob('*composition*'):
    for p in folder.glob('*'):
        if p.is_file():protect(p)
for p in R.iterdir():
    if p.is_file() and (p.suffix in ['.mpp','.apk','.zip','.srt'] or p.name.startswith('caption-diagnostics-')):
        protect(p)
def row(item):
    path,p=item
    return {'path':path,'sha256':sha(p),'bytes':p.stat().st_size}
print('Hash additional historical inputs',len(extra_files),flush=True)
with ThreadPoolExecutor(max_workers=4) as pool:
    for item in pool.map(row,extra_files.items()):history[item['path']]=item
print('History capture finished',len(history),flush=True)
for path in ['extensions/extension/src/main/java/app/yydarlinker/deepseekcaptions/CaptionUiLocale.java','extensions/extension/src/main/java/app/yydarlinker/deepseekcaptions/CaptionLanguagesPreference.java','patches/src/main/kotlin/app/yydarlinker/patches/deepseekcaptions/CaptionSettingsBindingPatch.kt']:
    target=V/'original-source'/Path(path).name;target.parent.mkdir(exist_ok=True)
    target.write_bytes(original[path])
for path,content in original.items():
    if '/src/test/' in path:(V/'original-source'/Path(path).name).write_bytes(content)
states=[R/'docs/PROJECT-STATE.md',Path(r'C:\Users\14776\Documents\kimi\tasks\2026-09-29\00-35-52-ec1208d7\PROJECT-STATE.md')]
assert states[0].read_bytes()==states[1].read_bytes(),'State files differ: inspect before syncing'
out.write_text(json.dumps({'head':head,'baseline':'dc304cbe3ae995e7e0edad2754b160c959cafce3','original_tree_method':'git show HEAD:path, not in-flight working files','files':files,'history':list(history.values()),'state_sha256':sha(states[0])},indent=2),encoding='utf-8')
print('N32_CAPTURED',head,'tracked',len(files),'history',len(history),'state_same',True)
