"""Check the generated two-root catalog and the actual independently named N32 bundle."""
from pathlib import Path
import argparse, hashlib, json, zipfile

ROOT = Path(__file__).resolve().parents[2]
EXPECTED = ['AI caption translator', 'Remember caption selection']


def validate_catalog(catalog):
    rows = catalog['patches']
    assert [row['name'] for row in rows] == EXPECTED, 'public roots must be exactly AI and Remember'
    assert catalog['version'] == '1.3.5', 'version changed'
    for row in rows:
        assert row['default'] is False, 'root must be opt-in'
        assert row['options'] == [], 'unexpected root options'
        package, = row['compatiblePackages']
        assert package['packageName'] == 'com.google.android.youtube'
        target, = package['targets']
        assert target['version'] == '21.16.256' and target['minSdk'] == 28
        assert target['isExperimental'] is False


def negative_controls(catalog):
    controls = {}
    for label in ['third_public_root', 'missing_ai', 'missing_remember']:
        bad = json.loads(json.dumps(catalog))
        if label == 'third_public_root':
            bad['patches'].append(dict(bad['patches'][0], name='Add Simplified Chinese to auto-translate'))
        elif label == 'missing_ai':
            bad['patches'].pop(0)
        else:
            bad['patches'].pop()
        try:
            validate_catalog(bad)
        except AssertionError:
            controls[label] = 'rejected'
        else:
            raise AssertionError(label + ' negative control accepted')
    return controls


def validate_runtime(runtime):
    assert runtime['ai_installed'] is True and runtime['ai_enabled'] is False
    assert runtime['api_requests'] == 0 and runtime['memory_decision'] == -1
    assert runtime['stored_before'] == runtime['stored_after'] == ['fr', 'zh-Hans'], 'stored selection was cleared or changed'
    assert sorted(runtime['added_codes']) == ['fr', 'zh-Hans'], 'selected targets missing or duplicated'
    assert sorted(runtime['metadata_codes']) == ['en', 'fr', 'zh-Hans'], 'metadata target missing or duplicated'
    assert runtime['empty_selection_returns_native_input'] is True


def runtime_negative_controls(runtime):
    controls = []
    for label in ['missing-simplified', 'duplicate-simplified', 'missing-metadata-simplified', 'duplicate-metadata-simplified', 'cleared-stored-selection']:
        mutant = json.loads(json.dumps(runtime))
        if label == 'missing-simplified':
            mutant['added_codes'].remove('zh-Hans')
        elif label == 'duplicate-simplified':
            mutant['added_codes'].append('zh-Hans')
        elif label == 'missing-metadata-simplified':
            mutant['metadata_codes'].remove('zh-Hans')
        elif label == 'duplicate-metadata-simplified':
            mutant['metadata_codes'].append('zh-Hans')
        else:
            mutant['stored_after'] = []
        try:
            validate_runtime(mutant)
        except AssertionError as rejected:
            controls.append({'mutation': label, 'rejected': True, 'reason': str(rejected),
                             'scope': 'captured production menu/metadata output with test-host clone adapter'})
        else:
            raise AssertionError(label + ' negative control accepted')
    return controls


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--catalog', type=Path, default=ROOT / 'patches-list.json')
    parser.add_argument('--bundle', type=Path, default=ROOT / 'build/local-test/patches-1.3.5-本地测试包-n32.mpp')
    parser.add_argument('--metadata-log', type=Path, default=ROOT / '.verification/n32/delivery-records/composition-final.log')
    parser.add_argument('--metadata-inputs', type=Path, default=ROOT / '.verification/n32/delivery-records/composition-final-inputs.json')
    parser.add_argument('--independent-mpe', type=Path, default=ROOT / 'extensions/extension/build/morphe/extensions/extension.mpe')
    parser.add_argument('--runtime', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / '.verification/n32/delivery-records/public-roots.json')
    args = parser.parse_args()
    catalog = json.loads(args.catalog.read_text(encoding='utf-8-sig'))
    validate_catalog(catalog)
    readme = ROOT / 'README.md'
    readme_text = readme.read_text(encoding='utf-8')
    assert all('`' + name + '`' in readme_text for name in EXPECTED), 'README omits an actual public root'
    assert '`Add Simplified Chinese to auto-translate`' not in readme_text, 'README still advertises an obsolete public root'
    result = {'public_roots': EXPECTED, 'count': 2, 'catalog_sha256': hashlib.sha256(args.catalog.read_bytes()).hexdigest(),
              'negative_controls': negative_controls(catalog),
              'readme_sha256': hashlib.sha256(readme.read_bytes()).hexdigest(), 'readme_public_roots_match': True}
    with zipfile.ZipFile(args.bundle) as bundle:
        assert bundle.testzip() is None
        assert len(bundle.namelist()) == len(set(bundle.namelist())), 'duplicate MPP archive entries'
        patch_dex = bundle.read('classes.dex')
        extension = bundle.read('extensions/extension.mpe')
        assert b'Add Simplified Chinese to auto-translate' not in patch_dex, 'obsolete public root in actual MPP DEX'
        assert b'simplifiedInstalled' not in extension, 'obsolete runtime installation flag in actual MPE'
        assert all(name.encode() in patch_dex for name in EXPECTED), 'missing actual MPP root'
        standalone = args.bundle.with_name('extension-1.3.5-本地测试包-n32.mpe')
        assert standalone.read_bytes() == extension, 'embedded and standalone MPE differ'
        assert args.independent_mpe.read_bytes() == extension, 'independently compiled build MPE and delivered MPE differ'
    result.update(bundle_sha256=hashlib.sha256(args.bundle.read_bytes()).hexdigest(), bundle_bytes=args.bundle.stat().st_size,
                  actual_root_dex=True, obsolete_flag_absent=True, embedded_standalone_mpe_equal=True,
                  independent_mpe_build_path=str(args.independent_mpe.resolve()),
                  independent_mpe_sha256=hashlib.sha256(args.independent_mpe.read_bytes()).hexdigest(),
                  independent_build_embedded_delivered_mpe_equal=True)
    if args.metadata_log:
        log = args.metadata_log.read_text(encoding='utf-8-sig', errors='replace')
        assert 'N32_PUBLIC_ROOTS_PASS roots=[AI caption translator, Remember caption selection] count=2' in log
        provenance = json.loads(args.metadata_inputs.read_text(encoding='utf-8-sig'))
        assert provenance['inputs_before'] == provenance['inputs_after'], 'metadata composition inputs changed'
        mpp_inputs = [item for item in provenance['inputs_before'] if Path(item['path']).resolve() == args.bundle.resolve()]
        mpp_input, = mpp_inputs
        assert mpp_input['sha256'].lower() == result['bundle_sha256'], 'metadata log came from another MPP'
        assert mpp_input['bytes'] == result['bundle_bytes'], 'metadata MPP size mismatch'
        logs = provenance.get('logs', [provenance.get('log')])
        metadata_record, = [row for row in logs if row and Path(row['path']).resolve() == args.metadata_log.resolve()]
        assert metadata_record['sha256'].lower() == hashlib.sha256(args.metadata_log.read_bytes()).hexdigest(), 'metadata log changed'
        result['actual_mpp_patcher_metadata_count'] = 2
        result['metadata_log_sha256'] = metadata_record['sha256']
        result['metadata_log_bound_to_actual_mpp'] = True
    if args.runtime:
        runtime = json.loads(args.runtime.read_text(encoding='utf-8-sig'))
        validate_runtime(runtime)
        result['runtime'] = runtime
        result['runtime_evidence_path'] = str(args.runtime.resolve())
        result['runtime_evidence_sha256'] = hashlib.sha256(args.runtime.read_bytes()).hexdigest()
        result['runtime_scope'] = 'production menu/metadata methods in source-derived test host; actual final clone target p1 and feature flags separately verified from serialized DEX'
        result['runtime_negative_controls'] = runtime_negative_controls(runtime)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
