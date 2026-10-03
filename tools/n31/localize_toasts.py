"""Use the same display configuration for owned Toast widgets and their stable-key text."""
from pathlib import Path
R=Path(__file__).resolve().parents[2];J=R/'extensions/extension/src/main/java/app/yydarlinker/deepseekcaptions'
for name in ['ApiProfilesPreference','DeepSeekActionPreference','DeepSeekDiagnosticsPreference','CaptionQuickToggle']:
    p=J/(name+'.java');s=p.read_text(encoding='utf-8')
    for c in ['getContext()','c','context']:
        s=s.replace('Toast.makeText('+c+',','Toast.makeText(CaptionUiLocale.context('+c+'),')
    p.write_text(s,encoding='utf-8')
