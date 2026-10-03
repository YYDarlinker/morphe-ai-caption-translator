"""Replay the N30 Chinese default, preserving its old English-UI drift golden separately."""
from pathlib import Path
import subprocess,zipfile,io,shutil,json,hashlib
R=Path(__file__).resolve().parents[2];out=R/'.verification/n31/n30-chinese-tree-02'
assert not out.exists(),'Never overwrite a baseline replay'
out.mkdir()
data=subprocess.check_output(['git','archive','--format=zip','d5ca720ecf0c83349ea232d929ee09b11840c65a'],cwd=R)
with zipfile.ZipFile(io.BytesIO(data)) as z:
    for n in z.namelist():assert (out/n).resolve().is_relative_to(out.resolve()),n
    for n in z.namelist():
        if '/' not in n.rstrip('/') or n.startswith(('extensions/','patches/','gradle/','localization/','scoreboard/','tools/','docs/')):
            z.extract(n,out)
(out/'.verification/toolchain').mkdir(parents=True)
shutil.copyfile(R/'.verification/toolchain/morphe-patcher-1.14.1-all.jar',out/'.verification/toolchain/morphe-patcher-1.14.1-all.jar')
shutil.copyfile(R/'gradle.properties',out/'gradle.properties')
(R/'.verification/n31/chinese-baseline-provenance.json').write_text(json.dumps({'source_commit':'d5ca720ecf0c83349ea232d929ee09b11840c65a','archive_sha256':hashlib.sha256(data).hexdigest(),'condition':'Chinese host resources; original golden methods and every source/sample/assertion unchanged; separate probe annotation','historical_english_ui_drift_golden':'.verification/n30/full-final-05/legacy-region-golden.json'},indent=2),encoding='utf-8')
print('Prepared isolated N30 Chinese replay from exact source commit')
