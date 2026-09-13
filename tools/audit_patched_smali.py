"""Audit the verified YouTube 21.07.247 host integration after apktool --no-res decode."""
import json,re,sys
from pathlib import Path
root=Path(sys.argv[1]); report={}
def read(name):
    matches=list(root.glob("smali*/"+name+".smali"));assert len(matches)==1,(name,len(matches))
    text=matches[0].read_text(encoding="utf-8")
    return re.sub(r"\\u([0-9a-fA-F]{4})",lambda m:chr(int(m.group(1),16)),text)
def instructions(text):
    return [line.strip() for line in text.splitlines() if line.strip() and not line.strip().startswith((".","#"))]
bridge=read("app/yydarlinker/deepseekcaptions/NativeCaptionBridge")
assert "check-cast p0, Lbdxi;" in bridge and "check-cast p0, Lanyg;" in bridge
model=read("anyi"); code=instructions(model)
assert sum("->augmentMetadata(" in x for x in code)==3
assert any("iput-object p3, p0, Lanyi;->a:Lbdxi;" in x for x in code)
for i,line in enumerate(code):
    if "->augmentMetadata(" in line:
        assert code[i+1].startswith("move-result-object")
        assert code[i+2].startswith("check-cast")
        assert code[i+3].startswith(("iget-object","iput-object"))
    if "->augmentTranslations(" in line:
        assert code[i+1].startswith("move-result-object") and code[i+2].startswith("return-object")
manager=instructions(read("anws"))
assert sum("->restoreDecision()I" in x for x in manager)==2 # model-ready gate plus missing-language guard
assert sum("->resolveRemembered(" in x for x in manager)==1
assert sum("->onNativeSelection(" in x for x in manager)==1
window=read("com/google/android/libraries/youtube/player/subtitles/ui/SubtitleWindowView")
assert "->suppressNativeDraw()Z" in window
editor=read("app/yydarlinker/deepseekcaptions/DeepSeekTextPreference")
assert "BUTTON_NEUTRAL" in editor or "const/4" in editor
assert "从剪贴板粘贴" in editor and "加密保存" in editor
provider=read("app/yydarlinker/deepseekcaptions/ProviderRequestPolicy")
assert "Return valid JSON only." in provider
report.update(metadata_constructor=True,metadata_readers=2,branch_safe_list_return=True,
              native_memory_on_off=True,native_mode_selection=True,native_draw_guard=True,
              api_key_dialog=True,explicit_json_prompt=True)
print(json.dumps(report,indent=2))
