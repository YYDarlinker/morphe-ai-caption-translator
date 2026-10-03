"""Keep N30 delivery tooling read-only; adapt artifact paths for the independent N31 delivery."""
from pathlib import Path
R=Path(__file__).resolve().parents[2]
for name in ['run_composition.ps1','run_final_audits.ps1','run_combinations.ps1','verify_host_resources.py','verify_bundle.py','verify_aapt.py']:
    if (R/'tools/n31'/name).exists():continue
    s=(R/'tools/n30'/name).read_text(encoding='utf-8-sig').replace('n30','n31').replace('N30','N31')
    s=s.replace('combinations-final-03','combinations-final')
    (R/'tools/n31'/name).write_text(s,encoding='utf-8-sig' if name.endswith('.ps1') else 'utf-8')
s=(R/'tools/n30/run_tests.ps1').read_text(encoding='utf-8-sig').replace('.verification/n30/','.verification/n31/')
s=s.replace('$env:N30_EVIDENCE_DIR=$out','$env:N30_EVIDENCE_DIR=$out\n$env:N31_EVIDENCE_DIR=$out')
(R/'tools/n31/run_tests.ps1').write_text(s,encoding='utf-8-sig')
(R/'.verification/n31/delivery-records').mkdir(exist_ok=True)
print('Prepared independent N31 path adapters; original N30 tools unchanged')
