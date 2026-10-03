"""Check aapt output freshly read from the named final APK, with SHA provenance."""
from pathlib import Path
import argparse, ast, hashlib, json, os, re, struct, subprocess, zipfile

ROOT = Path(__file__).resolve().parents[2]
RECORDS = ROOT / '.verification/n32/delivery-records'
FINAL_APK = ROOT / 'build/n32-composition-final/YouTube-21.16.256-本地测试包-n32-unsigned.apk'


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest().upper()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', type=Path, default=FINAL_APK)
    parser.add_argument('--aapt', type=Path, default=Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/build-tools/36.0.0/aapt.exe')
    args = parser.parse_args()
    apk = args.apk.resolve()
    assert apk == FINAL_APK.resolve(), 'aapt must read the named delivery APK'
    RECORDS.mkdir(parents=True, exist_ok=True)
    before = digest(apk)
    commands = []

    def dump(name, *arguments):
        command = [str(args.aapt), 'dump', *map(str, arguments)]
        result = subprocess.run(command, check=True, capture_output=True)
        text = result.stdout.decode('utf-8', errors='replace')
        path = RECORDS / name
        path.write_text(text, encoding='utf-8')
        commands.append({'arguments': command, 'output': str(path), 'output_sha256': digest(path)})
        return text

    resource = dump('aapt-resources.txt', 'resources', apk)
    badging = dump('aapt-badging.txt', 'badging', apk)
    assert re.search(r"^sdkVersion:'28'$", badging, re.M), 'actual manifest minSdk is not 28'
    assert "versionName='21.16.256'" in badging, 'actual target version changed'
    ids = {name: rid for rid, name in re.findall(r'spec resource (0x[0-9a-f]+) [^\s]+:string/(cap_[a-z0-9_]+):', resource)}
    for key in ['cap_ai_summary', 'cap_ai_quick_toggle_on', 'cap_ai_quick_toggle_off', 'cap_languages_title', 'cap_languages_summary', 'cap_languages_count']:
        assert key in ids, key
    reports = []
    for variant in ['morphe_prefs', 'morphe_prefs_icons', 'morphe_prefs_icons_bold']:
        text = dump('aapt-' + variant + '.xmltree.txt', 'xmltree', apk, 'res/xml/' + variant + '.xml')
        at = text.index('morphe_vot_screen__ai_captions')
        block = text[at:text.index('E: app.yydarlinker.deepseekcaptions.CaptionFlyoutPreference', at)]
        assert 'android:summary(0x010101e9)=@' + ids['cap_ai_summary'] in block
        assert 'android:singleLineTitle(0x0101055c)=(type 0x12)0x0' in block
        assert 'deepseek_caption_enabled' in block and 'deepseek_caption_languages' in block
        assert block.index('deepseek_caption_enabled') < block.index('deepseek_caption_languages')
        assert block.count('android:order(0x010101ea)=(type 0x10)0x0') == 1
        assert block.count('android:order(0x010101ea)=(type 0x10)0x1') == 1
        assert 'android:icon(' not in block
        assert text.count('"deepseek_caption_languages" (Raw:') == 1
        reports.append({'variant': variant, 'functional_summary_resource': ids['cap_ai_summary'],
                        'enabled_order': 0, 'languages_order': 1, 'no_icon': True, 'single_line_title': False})
    # Reuse the unchanged binary pool decoder as a single AST function, without audit side effects.
    decoder = ROOT / 'tools/n29/verify_host_resources.py'
    tree = ast.parse(decoder.read_text(encoding='utf-8'))
    function, = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'read_string_pool']
    namespace = {'struct': struct}
    exec(compile(ast.Module(body=[function], type_ignores=[]), str(decoder), 'exec'), namespace)
    with zipfile.ZipFile(apk) as archive:
        arsc = archive.read('resources.arsc')
    header = struct.unpack_from('<H', arsc, 2)[0]
    pool = set(namespace['read_string_pool'](arsc, header))
    catalog = json.loads((ROOT / 'localization/catalog.json').read_text(encoding='utf-8'))
    keys = ['ai_summary', 'ai_quick_toggle_on', 'ai_quick_toggle_off', 'languages_title', 'languages_summary',
            'languages_count', 'languages_save', 'languages_existing', 'languages_new', 'languages_unavailable', 'languages_empty', 'languages_entry']
    assert len(catalog['languages']) == 14
    for locale, values in catalog['languages'].items():
        for key in keys:
            assert values[key] in pool, (locale, key)
    assert digest(apk) == before, 'APK changed while aapt evidence was captured'
    proof = {'rows': reports, 'new_ui_values_in_arsc': len(keys) * 14, 'min_sdk': 28,
             'actual_apk': str(apk), 'actual_apk_sha256': before, 'actual_apk_bytes': apk.stat().st_size,
             'aapt_tool': str(args.aapt.resolve()), 'aapt_tool_sha256': digest(args.aapt),
             'fresh_aapt_commands': commands, 'decoder_source_sha256': digest(decoder)}
    (RECORDS / 'aapt-preference-proof.json').write_text(json.dumps(proof, indent=2) + '\n', encoding='utf-8')
    print('N32_AAPT_PREFERENCE_PASS variants=3 new_values=168 order=0,1 no_icon=true actual_min_sdk=28 artifact_bound=true')


if __name__ == '__main__':
    main()
