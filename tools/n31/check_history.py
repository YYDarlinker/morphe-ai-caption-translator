from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import hashlib,json
R=Path(__file__).resolve().parents[2];V=R/'.verification/n31';O=V/'delivery-records'
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
rows=json.loads((V/'input-manifest.json').read_text(encoding='utf-8'))['history']
def verify(row):
    p=R/row['path'];assert p.stat().st_size==row['bytes'],row['path'];assert sha(p)==row['sha256'].lower(),row['path'];return True
with ThreadPoolExecutor(max_workers=8) as pool:assert all(pool.map(verify,rows))
extra=json.loads((R/'.verification/n30/input-manifest.json').read_text(encoding='utf-8')).get('n30_external',[])+json.loads((R/'.verification/n30/source-inputs.json').read_text(encoding='utf-8'))
for row in extra:
    p=Path(row['path']);assert p.stat().st_size==row['bytes'] and sha(p)==row['sha256'].lower(),str(p)
(O/'history-after.json').write_text(json.dumps({'captured_files':len(rows),'unchanged':len(rows),'external_inputs_unchanged':len(extra)},indent=2),encoding='utf-8')
print('HISTORY_UNCHANGED',len(rows),'external',len(extra))
