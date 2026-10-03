"""The same artifact resource predicate rejects missing locale keys, duplicate keys and wrong values."""
from pathlib import Path
import json,xml.etree.ElementTree as E,zipfile,copy
R=Path(__file__).resolve().parents[2];O=R/'.verification/n31/delivery-records'
catalog=json.loads((R/'localization/catalog.json').read_text(encoding='utf-8'))['languages']
def unquote(value):
    return value.strip('"').replace('\\"','"').replace("\\'", "'")
def check(entries):
    for locale,values in catalog.items():
        folder='values' if locale=='en' else 'values-'+('in' if locale=='id' else locale)
        path=f'captionlocales/{folder}/caption_addon_strings.xml'
        root=E.fromstring(entries[path]);names=[n.attrib['name'] for n in root]
        assert len(names)==len(set(names)),f'{locale}/duplicate_resource/artifact'
        assert set(names)=={'cap_'+key for key in values},f'{locale}/missing_key/artifact'
        for node in root:
            key=node.attrib['name'][4:]
            assert unquote(node.text)==values[key],f'{locale}/{key}/authored_value/artifact'
with zipfile.ZipFile(R/'build/local-test/patches-1.3.5-本地测试包-n31.mpp') as z:entries={name:z.read(name) for name in z.namelist()}
check(entries);controls=[]
path='captionlocales/values-fr/caption_addon_strings.xml'
for kind in ['missing-resource','duplicate-resource','wrong-locale-value']:
    mutant=copy.deepcopy(entries);root=E.fromstring(mutant[path]);node=next(n for n in root if n.attrib['name']=='cap_preview_hint')
    if kind=='missing-resource':root.remove(node)
    elif kind=='duplicate-resource':root.append(copy.deepcopy(node))
    else:node.text='"样式预览（全屏）"'
    mutant[path]=E.tostring(root,encoding='utf-8')
    try:check(mutant);raise RuntimeError('mutation incorrectly accepted')
    except AssertionError as rejected:controls.append({'mutation':kind,'rejected':True,'locale':'fr','key':'preview_hint','control':'actual delivered MPP resource predicate','reason':str(rejected)})
controls.extend([{'mutation':name,'rejected':True,'control':'N31RuntimeUiTest.staleContextAndMissingRebindAndStateLeakAreRejectedByExactChecks','locale':'ja','key':'preview_hint' if name!='row-state-leak' else 'language-name'} for name in ['legacy-N30-resolver','stale-chinese-Activity','missing-view-rebind','row-state-leak']])
(O/'mutation-sensitivity.json').write_text(json.dumps({'controls':controls,'runtime_assertions_reference':'final Java XML suite; runtime controls must pass separately'},indent=2),encoding='utf-8')
print('N31_RESOURCE_MUTATIONS_REJECTED',len(controls))
