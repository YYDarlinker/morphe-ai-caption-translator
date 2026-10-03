"""Extract unchanged addon nodes from actual Patcher-generated Morphe XML for framework inflation."""
from pathlib import Path
import sys,hashlib,json,xml.etree.ElementTree as E
R=Path(__file__).resolve().parents[2];root=R/sys.argv[1]
paths=list(root.glob('session-*/patcher/apk/resources/package_1/res/xml/morphe_prefs.xml'))
assert len(paths)==1,paths
source=paths[0];A='{http://schemas.android.com/apk/res/android}'
tree=E.parse(source);video=next(n for n in tree.iter() if n.get(A+'key')=='morphe_settings_screen_12_video_sort_by_key')
ai=next(n for n in video if n.get(A+'key')=='morphe_vot_screen__ai_captions')
assert [n.get(A+'key') for n in video].index('morphe_vot_screen__ai_captions')==[n.get(A+'key') for n in video].index('morphe_vot_screen')+1
E.register_namespace('android','http://schemas.android.com/apk/res/android')
outer=E.Element('PreferenceScreen');parent=E.SubElement(outer,'PreferenceScreen',{A+'key':video.get(A+'key'),A+'title':'Host video settings'})
# Only unrelated official classes/resources are trimmed. All addon nodes, values and order survive.
E.SubElement(parent,'Preference',{A+'key':'morphe_vot_screen',A+'title':'Host narration'})
parent.append(ai)
out=R/'extensions/extension/src/test/res/xml/n31_morphe_prefs.xml';out.parent.mkdir(parents=True,exist_ok=True)
E.ElementTree(outer).write(out,encoding='utf-8',xml_declaration=True)
raw=E.tostring(ai,encoding='utf-8')
report={'source':str(source),'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'addon_subtree_sha256':hashlib.sha256(raw).hexdigest(),'fixture_sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'addon_nodes':sum(1 for n in ai.iter()),'trim':'unrelated official settings only; addon subtree unchanged','narration_adjacency':True}
(R/'.verification/n31/ui-fixture-provenance.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report,indent=2))
