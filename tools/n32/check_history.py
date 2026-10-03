"""Recheck every original history identity and the existing external tool/APK inputs."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import hashlib,json
R=Path(__file__).resolve().parents[2];V=R/'.verification/n32';O=V/'delivery-records'
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
rows=json.loads((V/'input-manifest.json').read_text(encoding='utf-8'))['history']
def verify(row):
    p=R/row['path'];assert p.stat().st_size==row['bytes'],row['path'];assert sha(p)==row['sha256'].lower(),row['path'];return True
with ThreadPoolExecutor(max_workers=6) as pool:assert all(pool.map(verify,rows))
extra=json.loads((R/'.verification/n30/input-manifest.json').read_text(encoding='utf-8')).get('n30_external',[])+json.loads((R/'.verification/n30/source-inputs.json').read_text(encoding='utf-8'))
for row in extra:
    p=Path(row['path']);assert p.stat().st_size==row['bytes'] and sha(p)==row['sha256'].lower(),str(p)
result={'original_historical_files':len(rows),'unchanged':len(rows),'external_inputs_unchanged':len(extra),'original_n31_and_bad_bundles_and_device_before_preserved':True}
(O/'history-after.json').write_text(json.dumps(result,indent=2)+chr(10),encoding='utf-8');print('N32_HISTORY_UNCHANGED',len(rows),'external',len(extra))
