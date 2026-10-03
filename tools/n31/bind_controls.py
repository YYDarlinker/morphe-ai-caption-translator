"""Apply stable-key property bindings at known addon UI call sites."""
from pathlib import Path
import re
R=Path(__file__).resolve().parents[2];J=R/'extensions/extension/src/main/java/app/yydarlinker/deepseekcaptions'
files=['AddonSwitchPreference','ApiProfilesPreference','ApiKeyPreference','DeepSeekTextPreference','DeepSeekModelPreference','DeepSeekSliderPreference','DeepSeekActionPreference','DeepSeekDiagnosticsPreference','SubtitleStylePreview']
for name in files:
    p=J/(name+'.java');s=p.read_text(encoding='utf-8')
    s=s.replace('extends android.preference.Preference','extends CaptionUiPreference')
    s=re.sub(r'(\w+)\.setText\(CaptionStrings.settings\((getContext\(\)|c|context),\s*"([\w]+)"\)\)',r'CaptionUiViewBindings.text(\1,\2,"\3")',s)
    s=re.sub(r'(\w+)\.setHint\(CaptionStrings.settings\((getContext\(\)|c|context),\s*"([\w]+)"\)\)',r'CaptionUiViewBindings.hint(\1,\2,"\3")',s)
    s=s.replace('title.setText(getTitle())','CaptionUiViewBindings.render(title,()->getTitle())')
    s=s.replace('slider.setContentDescription(getTitle())','CaptionUiViewBindings.description(slider,getContext(),KEY_TEXT_SIZE.equals(getKey())?"size":"opacity")')
    p.write_text(s,encoding='utf-8')
p=J/'CaptionDiagnostics.java';s=p.read_text(encoding='utf-8')
s=s.replace('return uiText(context, true);','return uiText(context, false);')
s=s.replace('build=n30;','build=n31;')
p.write_text(s,encoding='utf-8')
p=R/'patches/src/main/kotlin/app/yydarlinker/patches/deepseekcaptions/DeepSeekCaptionResourcePatch.kt';s=p.read_text(encoding='utf-8')
s=s.replace('category.setAttribute("android:title", captionResourceTitle(title))','category.setAttribute("android:title", captionResourceTitle(title))\n            category.setAttribute("android:key", "cap_ui_category_" + captionResourceTitle(title).removePrefix("@string/cap_"))\n            category.setAttribute("android:persistent", "false")')
p.write_text(s,encoding='utf-8')
