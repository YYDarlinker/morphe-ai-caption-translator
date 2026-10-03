"""Capture N31 inputs and immutable historical artifacts before any product edit."""
from pathlib import Path
import hashlib, json, subprocess, shutil
R = Path(__file__).resolve().parents[2]
O = R / '.verification/n31'
O.mkdir(parents=True, exist_ok=True)
target = O / 'input-manifest.json'
if target.exists():
    raise SystemExit('N31 input capture already exists; never overwrite')
def sha(p):
    with p.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()
tracked = subprocess.check_output(['git','ls-files','-z'], cwd=R).decode().split('\0')
files = {p: sha(R/p) for p in tracked if p and (R/p).is_file()}
history = {}
old = R / '.verification/n30/input-manifest.json'
if old.exists():
    for row in json.loads(old.read_text(encoding='utf-8'))['history']:
        history[row['path']] = row
for folder in ['.verification/n30/delivery-records', '.verification/n30/handoff', '.verification/n30/full-final-05', '.verification/n30/special-and-concurrency-final-02', '.verification/n31-planning', 'build/local-test']:
    for p in (R/folder).rglob('*'):
        if p.is_file():
            history[p.relative_to(R).as_posix()] = {'path': p.relative_to(R).as_posix(), 'sha256': sha(p), 'bytes':p.stat().st_size}
for name in ['YouTube-21.16.256-本地测试包-n30-unsigned.apk','structure.txt','official-defaults.txt']:
    p=R/'build/n30-composition-final'/name
    if p.exists(): history[p.relative_to(R).as_posix()]={'path':p.relative_to(R).as_posix(),'sha256':sha(p),'bytes':p.stat().st_size}
for p in R.iterdir():
    if p.is_file() and (p.suffix in ['.mpp','.apk','.zip','.srt'] or p.name.startswith('caption-diagnostics-')):
        history[p.name] = {'path':p.name,'sha256':sha(p),'bytes':p.stat().st_size}
target.write_text(json.dumps({'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=R).decode().strip(),'files':files,'history':list(history.values())},indent=2),encoding='utf-8')
shutil.copytree(R/'extensions/extension/src/test', O/'tests-before')
shutil.copytree(R/'extensions/extension/src/main/java/app/yydarlinker/deepseekcaptions', O/'source-before')
shutil.copyfile(R/'localization/catalog.json', O/'catalog-before.json')
print('Captured',len(files),'tracked inputs and',len(history),'historical files')
