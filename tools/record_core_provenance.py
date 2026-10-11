"""Record intentional translation-core changes: refresh hashes and the upstream adaptation diff.

Pinned snapshots are never rewritten. Run after a reviewed core change, then commit the result.
  python tools/record_core_provenance.py [--add PATH PURPOSE]... [--check]
"""
from pathlib import Path
import argparse, difflib, hashlib, json, sys

R = Path(__file__).resolve().parents[1]
PROVENANCE = R / 'docs/core-provenance.json'
PATCH = R / 'docs/core-upstream.patch'


def sha(path):
    return hashlib.sha256((R / path).read_bytes()).hexdigest()


def adaptation_diff(files):
    out = []
    for row in files:
        old = (R / row['snapshot']).read_text(encoding='utf-8').splitlines(keepends=True)
        new = (R / row['destination']).read_text(encoding='utf-8').splitlines(keepends=True)
        out.extend(difflib.unified_diff(old, new, row['upstream'], row['destination']))
    return ''.join(line if line.endswith('\n') else line + '\n' for line in out)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--add', nargs=2, action='append', default=[], metavar=('PATH', 'PURPOSE'))
    parser.add_argument('--check', action='store_true', help='report stale records without writing')
    args = parser.parse_args()
    data = json.loads(PROVENANCE.read_text(encoding='utf-8'))
    stale = []
    for row in data['files']:
        if sha(row['snapshot']) != row['upstream_sha256']:
            sys.exit('pinned snapshot changed: ' + row['snapshot'])
        current = sha(row['destination'])
        if current != row['sha256']: stale.append(row['destination']); row['sha256'] = current
    known = {row['path'] for row in data['new_files']}
    for path, purpose in args.add:
        if path not in known: data['new_files'].append({'path': path, 'sha256': '', 'purpose': purpose})
    for row in data['new_files']:
        current = sha(row['path'])
        if current != row['sha256']: stale.append(row['path']); row['sha256'] = current
    diff = adaptation_diff(data['files'])
    if PATCH.read_text(encoding='utf-8') != diff: stale.append(PATCH.relative_to(R).as_posix())
    if args.check:
        print('\n'.join(stale) if stale else 'provenance current')
        sys.exit(1 if stale else 0)
    PROVENANCE.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
    PATCH.write_text(diff, encoding='utf-8')
    print('\n'.join('recorded ' + s for s in stale) if stale else 'provenance current')


if __name__ == '__main__':
    main()
