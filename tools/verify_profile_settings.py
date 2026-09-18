"""Validate generated Morphe settings XML after the final composition, not a source template."""
from pathlib import Path
import xml.etree.ElementTree as E
import json,sys
root=Path(sys.argv[1]);a="{http://schemas.android.com/apk/res/android}"
found=[]
for p in root.rglob("*.xml"):
 if p.parent.name!="xml":continue
 try:r=E.parse(p).getroot()
 except E.ParseError:continue
 for node in r.iter("PreferenceCategory"):
  children=list(node);keys=[c.get(a+"key") for c in children]
  if "deepseek_caption_profiles" not in keys:continue
  assert keys[:5]==["deepseek_caption_profiles","deepseek_caption_base_url","deepseek_caption_api_key","deepseek_caption_model","deepseek_caption_test_api"],(str(p),keys)
  row=children[0]
  assert row.tag=="app.yydarlinker.deepseekcaptions.ApiProfilesPreference"
  assert row.get(a+"title")=="@string/cap_profiles_title"
  assert row.get(a+"dependency") is None
  found.append(str(p))
assert len(found)==3,("Expected all three Morphe settings layouts",found)
print(json.dumps({"settings_files":found,"profiles_first_in_api_category":True,"independent_of_engine_toggle":True},indent=2))
