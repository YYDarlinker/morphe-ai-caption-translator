"""Bind three selections and same-process repeats to their serialized final DEX."""
from pathlib import Path
import argparse, hashlib, json, re, zipfile

ROOT = Path(__file__).resolve().parents[2]
RECORDS = ROOT / '.verification/n32/delivery-records'
ROOTS = ['AI caption translator', 'Remember caption selection']


def identity(path):
    path = Path(path).resolve()
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest().upper()
    return {'path': str(path), 'bytes': path.stat().st_size, 'sha256': digest}


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def check_inputs(record):
    assert record['inputs_before'] == record['inputs_after'], 'patching input changed'
    for before in record['inputs_before']:
        actual = identity(before['path'])
        assert actual['sha256'] == before['sha256'].upper() and actual['bytes'] == before['bytes'], 'stale composition input'
    return record['inputs_before']


def dex_payload(path):
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None
        names = archive.namelist()
        assert len(names) == len(set(names)), 'duplicate ZIP entries'
        return {name: hashlib.sha256(archive.read(name)).hexdigest().upper()
                for name in names if re.fullmatch(r'classes\d*\.dex', name)}


def snapshot(label, directory, composition_log, audit_log, expected):
    directory = Path(directory)
    selection = (directory / 'selection.txt').read_text(encoding='utf-8-sig').splitlines()
    assert selection == expected, (label, selection, expected)
    audit = Path(audit_log).read_text(encoding='utf-8-sig', errors='replace')
    assert 'DEX_AUDIT_PASS classes=' in audit, 'serialized DEX was not audited'
    found, = re.findall(r'^FEATURES=\{([^}]+)\}', audit, re.M)
    flags = dict((name, int(value)) for name, value in re.findall(r'(aiInstalled|memoryInstalled)=(\d+)', found))
    assert flags == {'aiInstalled': int(ROOTS[0] in expected), 'memoryInstalled': int(ROOTS[1] in expected)}, (label, flags)
    composition = Path(composition_log).read_text(encoding='utf-8-sig', errors='replace')
    assert 'N32_PUBLIC_ROOTS_PASS roots=[AI caption translator, Remember caption selection] count=2' in composition
    if ROOTS[0] in expected:
        assert 'N32_SELECTED_TARGET_CLONE_PASS language=p1 url=p1 vss=p1 label=p1 forced_zh_hans=0' in audit
    serialized = directory / 'serialized-dex.zip'
    payload = dex_payload(serialized)
    assert payload, 'no serialized root DEX'
    return {'label': label, 'selection': selection, 'feature_flags_from_actual_dex': flags,
            'snapshot': identity(serialized), 'dex_payload_sha256': payload,
            'composition_log': identity(composition_log), 'audit_log': identity(audit_log),
            'selected_target_p1_clone': ROOTS[0] in expected}


def main():
    parser = argparse.ArgumentParser()
    kind = parser.add_mutually_exclusive_group(required=True)
    kind.add_argument('--combinations', action='store_true')
    kind.add_argument('--same-process', action='store_true')
    args = parser.parse_args()
    label = 'same-process' if args.same_process else 'combinations'
    record = load(RECORDS / (label + '-inputs.json'))
    inputs = check_inputs(record)
    expected = ([ROOTS, [ROOTS[1]], [ROOTS[0]]] if args.same_process
                else [[ROOTS[0]], [ROOTS[1]]])
    rows = record['snapshots']
    assert len(rows) == len(expected)
    proof = [snapshot(row['label'], row['directory'], row['composition_log'], row['audit_log'], names)
             for row, names in zip(rows, expected)]
    result = {'inputs': inputs, 'rows': proof, 'resource_compilation_count_in_this_runner': 0}
    if args.same_process:
        log = Path(rows[0]['composition_log']).read_text(encoding='utf-8-sig', errors='replace')
        assert len(set(row['composition_log'] for row in rows)) == 1, 'repeats did not share a process'
        for index, names in enumerate(expected[1:], 1):
            assert 'N32_SAME_PROCESS_SESSION_PASS index=' + str(index) + ' selection=' + '|'.join(names) in log
        result.update(session_count=3, same_loaded_patch_instances=True, retained_feature_state_rejected=True, nonempty_combinations=3, passed=3)
    else:
        final_record = load(RECORDS / 'composition-final-inputs.json')
        assert check_inputs(final_record) == inputs, 'final composition used different inputs'
        final_dir = ROOT / 'build/n32-composition-final'
        final = snapshot('ai-memory', final_dir, RECORDS / 'composition-final.log',
                         RECORDS / 'composition-dex-audit.log', ROOTS)
        apk = final_dir / 'YouTube-21.16.256-本地测试包-n32-unsigned.apk'
        assert dex_payload(apk) == final['dex_payload_sha256'], 'final APK and audited DEX differ'
        final['final_apk'] = identity(apk)
        proof.append(final)
        assert len({tuple(row['selection']) for row in proof}) == 3
        result.update(nonempty_combinations=3, passed=3, final_ai_memory_reused=True, resource_compilation_count_total=1)
    output = RECORDS / (label + '-contract.json')
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('N32_' + label.upper().replace('-', '_') + '_CONTRACT_PASS', 'rows=' + str(len(proof)))


if __name__ == '__main__':
    main()
