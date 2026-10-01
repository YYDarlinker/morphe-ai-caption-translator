# -*- coding: utf-8 -*-
"""Merge the authored N25 UI strings into localization/catalog.json.

Only adds the new keys and extends the shared key list; existing keys, key order, locale order,
source-keys map and CRLF/LF layout are preserved. Values are never machine translated at runtime.
"""
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from n25_ui_strings import EN, PLACEHOLDERS, REUSE  # noqa: E402
from n25_ui_translations import TRANSLATIONS  # noqa: E402

root = Path(__file__).resolve().parents[1]
path = root / 'localization/catalog.json'
raw = path.read_text(encoding='utf-8')
newline = '\r\n' if '\r\n' in raw else '\n'
data = json.loads(raw)

locales = list(data['languages'])
assert locales[0] == 'en', locales

new_keys = sorted(EN)
added = [key for key in new_keys if key not in data['languages']['en']]


def value_for(locale, key, values):
    """The authored value of `key` for `locale`, read from the table that owns it."""
    if locale == 'en':
        return EN[key]
    target = REUSE.get(key)
    if target == '':
        # The catalog already carries this key's translated values; only the English source is authored.
        return values[key]
    if target:
        # Not a new string: this label is already authored, so copy that locale's value verbatim.
        return values[target]
    return TRANSLATIONS[locale][key]


for locale in locales:
    before = dict(data['languages'][locale])
    for key in new_keys:
        if key in before:
            # Already merged by an earlier run: the authored value is what the catalog must hold.
            assert before[key] == value_for(locale, key, before), (locale, key)
            continue
        value = value_for(locale, key, before)
        assert isinstance(value, str) and value.strip(), (locale, key)
        before[key] = value
    data['languages'][locale] = {k: before[k] for k in sorted(before)}

# `keys` is the curated subset the localization checker reports on; it must keep listing every key it
# listed before, plus the new ones, and may never name a key no locale defines.
data['keys'] = sorted(set(data['keys']) | set(new_keys))
assert set(data['keys']) <= set(data['languages']['en']), 'key[] must only name real locale keys'

# Placeholder contract: every locale keeps the same positional arguments as English. A literal percent
# sign (a hit rate, say) is not a placeholder, so only real positional tokens are counted. Order is the
# translator's to choose — the numbering is what pins each value to its argument.
TOKEN = re.compile(r'%(\d+)\$([sd])')
for key, kinds in PLACEHOLDERS.items():
    expected = sorted('%{}${}'.format(i + 1, kind) for i, kind in enumerate(kinds))
    english = sorted(m.group(0) for m in TOKEN.finditer(data['languages']['en'][key]))
    assert english == expected, (key, expected, english)
    for locale in locales:
        value = data['languages'][locale][key]
        found = sorted(m.group(0) for m in TOKEN.finditer(value))
        assert found == expected, (locale, key, expected, found, value)
        for index in re.findall(r'%(\d+)\$', value):
            assert 1 <= int(index) <= len(kinds), (locale, key, index)

path.write_text(json.dumps(data, ensure_ascii=False, indent=2).replace('\n', newline),
                encoding='utf-8', newline='')
print('catalog: {} locales, {} listed keys, {} keys per locale ({} new this run)'.format(
    len(locales), len(data['keys']), len(data['languages']['en']), len(added)))
